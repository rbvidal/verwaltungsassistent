package verwaltungsassistent.web.planning;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2B.6 — Zuordnungs-Konsistenz und Nebenläufigkeit (Level 2):
 *
 * <p>Invarianten (Architektur-Kontrakt):</p>
 * <ol>
 *   <li>Ein Fall hat höchstens EINE zuständige Mitarbeiterin (ownerId).</li>
 *   <li>workState ACTIVE ⇒ workState.employee == ownerId.</li>
 *   <li>Kein geteilter ACTIVE-Zustand (Alice UND Bob gleichzeitig aktiv).</li>
 *   <li>Zuordnung ist explizit; die Empfehlung ändert ownerId nie.</li>
 *   <li>Die Empfehlung wird nie persistiert — jede Lesart leitet neu ab.</li>
 *   <li>Aufwand gehört zum Fall: Alice 20 Min + Bob 15 Min = 35 Min Beobachtung,
 *       keine Mitarbeiter-Leistungswerte.</li>
 * </ol>
 *
 * <p>Szenarien A–E (deterministisch, keine Zufalls-Stresstests):</p>
 * <ul>
 *   <li>A — zwei gleichzeitige Zuordnungen → nie Split-Brain (owner/employee).</li>
 *   <li>B — Zuordnung bei ACTIVE → Intervall abgeschlossen, neue Mitarbeiterin
 *       startet nur explizit.</li>
 *   <li>C — Abschluss vs. Zuordnung → nie CLOSED mit ACTIVE-Arbeitszustand.</li>
 *   <li>D — Pause/Übergabe/Fortsetzen → akkumulierte Zeit bleibt Fall-Zeit,
 *       Wartezeit zählt nicht (10 + 120 + 15 = 25).</li>
 *   <li>E — Empfehlungs-Lesart während Zuordnungs-Mutationen → nie halber Zustand.</li>
 * </ul>
 */
@SpringBootTest
class CaseAssignmentServiceTest {

    @Autowired
    private CaseAssignmentService assignmentService;
    @Autowired
    private CaseWorkStateService caseWorkStateService;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private NextBestWorkService nextBestWorkService;
    @Autowired
    private CaseClosureService caseClosureService;

    /**
     * Deterministische Uhr NUR für diesen Test (kein @TestConfiguration —
     * verschachtelte Test-Konfigurationen würden in ALLE Test-Kontexte
     * komponenten-scannen und z. B. den System-Clock anderer Tests ersetzen).
     */
    private static final AtomicReference<Instant> NOW =
            new AtomicReference<>(Instant.parse("2026-08-31T09:00:00Z"));

    @MockBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        NOW.set(Instant.parse("2026-08-31T09:00:00Z"));
        org.mockito.Mockito.when(clock.instant()).thenAnswer(inv -> NOW.get());
        org.mockito.Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private String createCase(String name, String owner) {
        WorkspaceEntity ws = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(name, "Beschreibung", "CASE", owner));
        ws.setPhase(WorkspacePhase.ANALYSIS);
        ws.setPhaseData("{\"ingestionResolved\":true}");
        workspaceService.save(ws);
        return ws.getId();
    }

    private void advance(long minutes) {
        NOW.set(NOW.get().plus(Duration.ofMinutes(minutes)));
    }

    private static AuthenticatedUser user(String email) {
        return new AuthenticatedUser(UUID.randomUUID(), email, email, Set.of("USER"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workStateOf(WorkspaceEntity ws) {
        Object raw = ws.getPhaseDataMap().get("workState");
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    // ── Zustandsmaschine der Zuordnung ──

    @Test
    void assign_handoffUnstarted_switchesOwnerOnly() {
        String id = createCase("Handoff", "demo01@verwaltungsassistent.local");

        WorkspaceEntity result = assignmentService.assign(id, "demo02@verwaltungsassistent.local", false);

        assertEquals("demo02@verwaltungsassistent.local", result.getOwnerId());
        assertFalse(workspaceService.findById(id).orElseThrow()
                .getPhaseDataMap().containsKey("workState"),
                "a handoff never silently starts the new owner");
    }

    @Test
    void assign_takeoverStartTrue_claimsAndStarts() {
        String id = createCase("Takeover", "demo01@verwaltungsassistent.local");

        assignmentService.assign(id, "demo02@verwaltungsassistent.local", true);

        WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
        assertEquals("demo02@verwaltungsassistent.local", ws.getOwnerId());
        Map<String, Object> work = workStateOf(ws);
        assertEquals("ACTIVE", work.get("state"));
        assertEquals("demo02@verwaltungsassistent.local", work.get("employee"),
                "explicit takeover = assignment + explicit start (invariant 2)");
    }

    @Test
    void assign_whileActive_accumulatesAndPausesForNewOwner() {
        String id = createCase("ActiveHandoff", "demo01@verwaltungsassistent.local");
        caseWorkStateService.markActive(id, "demo01@verwaltungsassistent.local");
        advance(10);

        assignmentService.assign(id, "demo02@verwaltungsassistent.local", false);

        WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
        assertEquals("demo02@verwaltungsassistent.local", ws.getOwnerId());
        Map<String, Object> work = workStateOf(ws);
        assertEquals("PAUSED", work.get("state"),
                "assignment never claims the new owner as working");
        assertEquals(10L, ((Number) work.get("accumulatedMinutes")).longValue(),
                "Alice's active interval is accumulated and stays case time");
        assertEquals("OTHER", ((Map<?, ?>) ws.getPhaseDataMap().get("waitingOn")).get("type"));
        // Invariante 2 bleibt auch nach der Übergabe gültig.
        assertEquals("demo02@verwaltungsassistent.local", work.get("employee"));
    }

    @Test
    void assign_closedCase_isRejected() {
        String id = createCase("Closed", "demo01@verwaltungsassistent.local");
        WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
        ws.setStatus(WorkspaceStatus.CLOSED);
        workspaceService.save(ws);

        assertThrows(ResponseStatusException.class,
                () -> assignmentService.assign(id, "demo02@verwaltungsassistent.local", false));
        assertEquals("demo01@verwaltungsassistent.local",
                workspaceService.findById(id).orElseThrow().getOwnerId(),
                "a rejected assignment must not change the state");
    }

    @Test
    void assign_sameOwner_isIdempotent() {
        String id = createCase("Same", "demo01@verwaltungsassistent.local");
        caseWorkStateService.markActive(id, "demo01@verwaltungsassistent.local");

        WorkspaceEntity result = assignmentService.assign(id, "demo01@verwaltungsassistent.local", false);

        assertEquals("demo01@verwaltungsassistent.local", result.getOwnerId());
        assertEquals("ACTIVE", workStateOf(result).get("state"),
                "no state change when the owner stays the same");
    }

    @Test
    void markActive_byNonOwner_isNoOp_evenViaWorkStateService() {
        String id = createCase("Foreign", "demo01@verwaltungsassistent.local");
        caseWorkStateService.markActive(id, "demo02@verwaltungsassistent.local");
        assertFalse(workspaceService.findById(id).orElseThrow()
                .getPhaseDataMap().containsKey("workState"),
                "no ACTIVE claim on a foreign case (invariant 2 enforced)");
    }

    // ── Szenario A — zwei gleichzeitige Zuordnungen ──

    @Test
    void concurrentAssigns_neverProduceSplitBrain() throws Exception {
        String id = createCase("Concurrent", "demo01@verwaltungsassistent.local");
        for (int round = 0; round < 8; round++) {
            assignmentService.assign(id, "demo01@verwaltungsassistent.local", false);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> toBob = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    assignmentService.assign(id, "demo02@verwaltungsassistent.local", true);
                } catch (RuntimeException e) {
                    // Optimistische Sperre: der Verlierer scheitert sauber — der
                    // Gewinner bestimmt den konsistenten Endzustand.
                }
            });
            Future<?> toCarol = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    assignmentService.assign(id, "demo03@verwaltungsassistent.local", true);
                } catch (RuntimeException e) {
                    // siehe oben
                }
            });
            start.countDown();
            toBob.get(30, TimeUnit.SECONDS);
            toCarol.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
            String owner = ws.getOwnerId();
            assertTrue(owner.equals("demo02@verwaltungsassistent.local") || owner.equals("demo03@verwaltungsassistent.local"),
                    "final owner is exactly one of the two: " + owner);
            Map<String, Object> work = workStateOf(ws);
            if ("ACTIVE".equals(work.get("state"))) {
                assertEquals(owner, work.get("employee"),
                        "never owner=Bob with ACTIVE=Carol (split-brain)");
            }
        }
    }

    // ── Szenario C — Abschluss vs. Zuordnung ──

    @Test
    void completionVsAssignment_neverClosedWithActiveWork() throws Exception {
        for (int round = 0; round < 8; round++) {
            String id = createCase("Race" + round, "demo01@verwaltungsassistent.local");
            caseWorkStateService.markActive(id, "demo01@verwaltungsassistent.local");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> closer = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    caseClosureService.close(id, "demo01@verwaltungsassistent.local");
                } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                    // Optimistische Sperre: Abschluss verliert gegen Übergabe — ok.
                }
            });
            Future<?> assigner = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    assignmentService.assign(id, "demo02@verwaltungsassistent.local", true);
                } catch (ResponseStatusException e) {
                    assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
                } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                    // Optimistische Sperre: Übergabe verliert gegen Abschluss — ok.
                }
            });
            start.countDown();
            closer.get(30, TimeUnit.SECONDS);
            assigner.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
            Map<String, Object> work = workStateOf(ws);
            if (ws.getStatus() == WorkspaceStatus.CLOSED) {
                assertFalse("ACTIVE".equals(work.get("state")),
                        "a closed case must never carry an ACTIVE worker");
                assertTrue(work.isEmpty() || "PAUSED".equals(work.get("state")),
                        "closed case work state: " + work);
            } else {
                assertEquals("demo02@verwaltungsassistent.local", ws.getOwnerId());
                assertEquals("ACTIVE", work.get("state"));
                assertEquals("demo02@verwaltungsassistent.local", work.get("employee"));
            }
        }
    }

    // ── Szenario D — Pause/Übergabe/Fortsetzen: Wartezeit zählt nie ──

    @Test
    void pauseHandoffResumeClose_observationCountsOnlyActiveTime() {
        String id = createCase("PauseHandoff", "demo01@verwaltungsassistent.local");
        caseWorkStateService.markActive(id, "demo01@verwaltungsassistent.local");
        advance(10);
        caseWorkStateService.markPaused(id, "demo01@verwaltungsassistent.local", "CITIZEN", "Rückmeldung ausstehend");
        advance(120); // Wartezeit — zählt NIE
        assignmentService.assign(id, "demo02@verwaltungsassistent.local", false);
        caseWorkStateService.resume(id, "demo02@verwaltungsassistent.local");
        advance(15);
        long observed = caseWorkStateService.completeOnClose(id);

        assertEquals(25L, observed, "10 active + 15 active, 120 waiting excluded");
        WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
        assertFalse(ws.getPhaseDataMap().containsKey("workState"));
        assertFalse(ws.getPhaseDataMap().containsKey("waitingOn"));
    }

    // ── Szenario E — Empfehlungs-Lesart während Mutationen ──

    @Test
    void recommendationReads_duringConcurrentAssigns_neverObserveSplitBrain() throws Exception {
        String id = createCase("ReadStorm", "demo01@verwaltungsassistent.local");
        caseWorkStateService.markActive(id, "demo01@verwaltungsassistent.local");
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        Runnable storm = () -> {
            try {
                start.await();
                while (!stop.get()) {
                    try {
                        assignmentService.assign(id, "demo02@verwaltungsassistent.local", true);
                    } catch (Exception ignored) {
                    }
                    try {
                        assignmentService.assign(id, "demo03@verwaltungsassistent.local", true);
                    } catch (Exception ignored) {
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicBoolean splitBrainSeen = new AtomicBoolean(false);
        Runnable reader = () -> {
            try {
                start.await();
                while (!stop.get()) {
                    nextBestWorkService.recommendFor(user("demo01@verwaltungsassistent.local"));
                    nextBestWorkService.recommendFor(user("demo02@verwaltungsassistent.local"));
                    for (WorkspaceEntity ws : workspaceService.findAll()) {
                        Map<String, Object> work = workStateOf(ws);
                        if ("ACTIVE".equals(work.get("state"))
                                && ws.getOwnerId() != null
                                && !ws.getOwnerId().equalsIgnoreCase(String.valueOf(work.get("employee")))) {
                            splitBrainSeen.set(true);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(storm);
        pool.submit(reader);
        pool.submit(reader);
        start.countDown();
        Thread.sleep(1500);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertFalse(splitBrainSeen.get(),
                "a recommendation read must never observe owner/ACTIVE mismatch");

        WorkspaceEntity ws = workspaceService.findById(id).orElseThrow();
        String owner = ws.getOwnerId();
        Map<String, Object> work = workStateOf(ws);
        if ("ACTIVE".equals(work.get("state"))) {
            assertEquals(owner, work.get("employee"));
        }
        assertNotNull(owner);
    }

    // ── Invariante 6 — Aufwand gehört zum Fall ──

    @Test
    void effortIsCaseLevel_notEmployeeProductivity() {
        String id = createCase("Effort", "demo01@verwaltungsassistent.local");
        caseWorkStateService.markActive(id, "demo01@verwaltungsassistent.local");
        advance(20);
        caseWorkStateService.markPaused(id, "demo01@verwaltungsassistent.local", "CITIZEN", "");
        assignmentService.assign(id, "demo02@verwaltungsassistent.local", false);
        caseWorkStateService.resume(id, "demo02@verwaltungsassistent.local");
        advance(15);

        long observed = caseWorkStateService.completeOnClose(id);
        assertEquals(35L, observed,
                "20 min Alice + 15 min Bob = 35 min case observation — never per-employee data");
    }
}
