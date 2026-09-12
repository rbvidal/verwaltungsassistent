package verwaltungsassistent.web.planning;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.planning.CasePlanningService.CasePlanningView;
import verwaltungsassistent.web.planning.CasePlanningService.PersonalPlanning;
import verwaltungsassistent.web.planning.NextBestWorkService.ExcludedCandidate;
import verwaltungsassistent.web.planning.NextBestWorkService.Recommendation;
import verwaltungsassistent.web.planning.NextBestWorkService.RecommendationResult;
import verwaltungsassistent.web.planning.NextBestWorkService.TraceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Next-Best-Work-Engine: harte Eligibility (Zustände, Zugriff, aktiv von
 * anderer Person), Signale → Score → Reihung (kritische Frist-Bande),
 * Kapazität als weiche Grenze, Erklärungen + Trace, Unabhängigkeit der
 * Mitarbeiterinnen. Persistenz gemockt; Politik und Schätzung real.
 */
class NextBestWorkServiceTest {

    private final CasePlanningService casePlanningService = mock(CasePlanningService.class);
    private final CaseWorkStateService caseWorkStateService = mock(CaseWorkStateService.class);
    private final EffortEstimator effortEstimator = mock(EffortEstimator.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);

    private NextBestWorkService service;

    private final String me = "demo01@verwaltungsassistent.local";
    private final String other = "demo02@verwaltungsassistent.local";
    private final Map<String, Integer> remainingByCategory = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new NextBestWorkService(casePlanningService, caseWorkStateService,
                effortEstimator, new NextBestWorkScoringPolicy(), workspaceService);
        when(effortEstimator.remainingEffortMinutes(anyString(), any(), anyInt(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(inv -> remainingByCategory.getOrDefault(inv.getArgument(0), 30));
        when(caseWorkStateService.currentState(anyString())).thenReturn(Map.of());
        when(casePlanningService.globalWorkPool()).thenReturn(List.of());
        when(casePlanningService.personalPlanning(anyString()))
                .thenReturn(new PersonalPlanning(List.of(), List.of()));
    }

    // ── Harte Eligibility ──

    /**
     * Phase 2C.5: Das Leitungs-Konto gehört nicht zur Mitarbeiter-Universum —
     * recommendFor liefert ihm KEINE Empfehlung und KEINE Kandidaten, auch
     * wenn Vorgänge verfügbar wären.
     */
    @Test
    void supervisoryAccount_neverReceivesARecommendation() {
        // Selbst ein gefüllter persönlicher Pool darf für die Leitung nicht
        // ausgewertet werden — die Früh-Rückkehr verhindert jede Bewertung.
        when(casePlanningService.personalPlanning("admin@verwaltungsassistent.local"))
                .thenReturn(new PersonalPlanning(List.of(
                        view(UUID.randomUUID().toString(), 95, true, "READY_TO_WORK")), List.of()));

        RecommendationResult result = service.recommendFor(user("admin@verwaltungsassistent.local", true));

        assertNull(result.recommended());
        assertTrue(result.rankedCandidates().isEmpty());
        assertTrue(result.excludedCandidates().isEmpty());
    }

    @Test
    void blockedStates_areExcluded_withGermanReasons() {
        String docs = UUID.randomUUID().toString();
        String citizen = UUID.randomUUID().toString();
        String completed = UUID.randomUUID().toString();
        personal(me, view(docs, 80, false, "WAITING_FOR_DOCUMENTS"),
                view(citizen, 70, false, "WAITING_FOR_CITIZEN"),
                view(completed, 90, false, "COMPLETED"));
        register(docs, me);
        register(citizen, me);
        register(completed, me);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNull(result.recommended());
        assertEquals(3, result.excludedCandidates().size());
        assertEquals("Wartet auf Unterlagen.", reasonOf(result, docs));
        assertEquals("Wartet auf Rückmeldung der Bürgerin bzw. des Bürgers.", reasonOf(result, citizen));
        assertEquals("Fall abgeschlossen bzw. archiviert.", reasonOf(result, completed));
    }

    @Test
    void caseActiveByAnotherEmployee_isExcluded() {
        String id = UUID.randomUUID().toString();
        personal(me, view(id, 90, true, "READY_TO_WORK"));
        register(id, me);
        when(caseWorkStateService.currentState(id)).thenReturn(activeBy(other, 0));

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNull(result.recommended());
        assertEquals("Wird derzeit von " + other + " bearbeitet.", reasonOf(result, id));
    }

    @Test
    void nonAdminWithoutOwnership_hasNoAccess() {
        String id = UUID.randomUUID().toString();
        pool(view(id, 90, true, "READY_TO_WORK"));
        register(id, other);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNull(result.recommended());
        assertEquals("Kein Zugriff auf diesen Fall.", reasonOf(result, id));
    }

    /**
     * Phase 2C.5: Das Leitungs-Konto erhält keine Empfehlung — der allgemeine
     * Arbeitspool gehört den Mitarbeiterinnen. Ein Pool-Vorgang (unzugewiesen,
     * owner null) ist für jede Mitarbeiterin Empfehlungs-Kandidat.
     */
    @Test
    void employee_doesSeeUnassignedPoolCases() {
        String id = UUID.randomUUID().toString();
        pool(view(id, 90, true, "READY_TO_WORK"));
        register(id, null);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNotNull(result.recommended());
        assertEquals(id, result.recommended().caseId());
    }

    /**
     * Allgemeiner Arbeitspool (Phase 2B): admin-owned, bearbeitbare Vorgänge
     * sind für JEDE Mitarbeiterin Kandidaten — die Empfehlung selbst ändert
     * die Zuordnung NIE (explizite Übernahme bleibt die einzige Aktion).
     */
    @Test
    void nonAdmin_canSeeGeneralPoolCases_withoutAutoAssignment() {
        String id = UUID.randomUUID().toString();
        pool(view(id, 90, true, "READY_TO_WORK"));
        register(id, verwaltungsassistent.web.service.DemoDataService.DEMO_OWNER);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNotNull(result.recommended(), "Pool-Vorgang muss für die Mitarbeiterin empfohlen werden");
        assertEquals(id, result.recommended().caseId());
        // Die Empfehlung schreibt nicht: Eigentümer bleibt der Pool.
        assertEquals(verwaltungsassistent.web.service.DemoDataService.DEMO_OWNER,
                workspaceService.findById(id).orElseThrow().getOwnerId());
    }

    @Test
    void pausedCase_reEntersAfterResume() {
        String id = UUID.randomUUID().toString();
        personal(me, view(id, 90, false, "WAITING_FOR_CITIZEN"));
        register(id, me);

        RecommendationResult blocked = service.recommendFor(user(me, false));
        assertEquals("Wartet auf Rückmeldung der Bürgerin bzw. des Bürgers.", reasonOf(blocked, id));

        // Fortgesetzt: wieder bearbeitbar und aktiv von derselben Mitarbeiterin.
        personal(me, view(id, 90, true, "READY_TO_WORK"));
        when(caseWorkStateService.currentState(id)).thenReturn(activeBy(me, 0));
        register(id, me, "ummeldung", WorkspacePhase.INGESTION, 0, null);

        RecommendationResult resumed = service.recommendFor(user(me, false));
        assertNotNull(resumed.recommended());
        assertEquals(id, resumed.recommended().caseId());
        assertTrue(resumed.excludedCandidates().isEmpty());
    }

    // ── Reihung ──

    @Test
    void criticalDeadline_bandProtectsTheUrgentCase() {
        String urgent = UUID.randomUUID().toString();
        String shortWin = UUID.randomUUID().toString();
        remainingByCategory.put("gewerbeanmeldung", 120);
        remainingByCategory.put("ummeldung", 10);
        personal(me, view(urgent, 70, true, "READY_TO_WORK"),
                view(shortWin, 90, true, "READY_TO_WORK"));
        register(urgent, me, "gewerbeanmeldung", WorkspacePhase.ANALYSIS, 5, 0);
        register(shortWin, me, "ummeldung", WorkspacePhase.REVIEW, 0, null);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNotNull(result.recommended());
        assertEquals(urgent, result.recommended().caseId(),
                "a deadline today outranks a high-priority quick win");
        assertTrue(result.recommended().reasons().contains("Frist heute"));
        assertTrue(result.recommended().reasons().contains("Bürger wartet seit 5 Tagen"));
        assertEquals(1, result.trace().stream()
                .filter(t -> t.caseId().equals(urgent)).findFirst().orElseThrow().rank());
    }

    @Test
    void deadlineInFuture_isExplained() {
        String id = UUID.randomUUID().toString();
        personal(me, view(id, 70, true, "READY_TO_WORK"));
        register(id, me, "allgemein", WorkspacePhase.ANALYSIS, 0, 3);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNotNull(result.recommended());
        assertTrue(result.recommended().reasons().contains("Frist in 3 Tagen"));
    }

    @Test
    void continuity_beingWorkedByMe_winsOverUntouchedCase() {
        String mine = UUID.randomUUID().toString();
        String untouched = UUID.randomUUID().toString();
        remainingByCategory.put("ummeldung", 15);
        pool(view(mine, 70, true, "READY_TO_WORK"), view(untouched, 70, true, "READY_TO_WORK"));
        register(mine, me, "ummeldung", WorkspacePhase.INGESTION, 0, null);
        register(untouched, null, "ummeldung", WorkspacePhase.INGESTION, 0, null);
        when(caseWorkStateService.currentState(mine)).thenReturn(activeBy(me, 0));

        RecommendationResult result = service.recommendFor(user(me, false));

        assertEquals(mine, result.recommended().caseId(), "equal urgency and effort → continuity decides");
        assertTrue(result.recommended().reasons().contains("Aktuell von Ihnen bearbeitet"));
        assertEquals(2, result.rankedCandidates().size(), "both remain candidates; the untouched case ranks second");
        assertEquals(untouched, result.rankedCandidates().get(1).caseId());
    }

    // ── Kapazität (weich) ──

    @Test
    void capacity_limitsTodayWithExplainableReason_notExclusion() {
        String big = UUID.randomUUID().toString();
        String small = UUID.randomUUID().toString();
        remainingByCategory.put("gewerbeanmeldung", 400);
        remainingByCategory.put("ummeldung", 15);
        pool(view(big, 70, true, "READY_TO_WORK"), view(small, 70, true, "READY_TO_WORK"));
        register(big, me, "gewerbeanmeldung", WorkspacePhase.ANALYSIS, 0, null);
        register(small, me, "ummeldung", WorkspacePhase.INGESTION, 0, null);
        when(caseWorkStateService.currentState(big)).thenReturn(activeBy(me, 0));

        RecommendationResult result = service.recommendFor(user(me, false));

        Recommendation bigRec = result.rankedCandidates().stream()
                .filter(r -> r.caseId().equals(big)).findFirst().orElseThrow();
        Recommendation smallRec = result.rankedCandidates().stream()
                .filter(r -> r.caseId().equals(small)).findFirst().orElseThrow();
        assertFalse(bigRec.canFinishToday(), "400 min do not fit into the remaining capacity");
        assertTrue(bigRec.reasons().contains("Übersteigt voraussichtlich die heute verfügbare Restkapazität"));
        assertTrue(smallRec.canFinishToday(), "15 min fit");
        assertTrue(smallRec.reasons().contains("Kann voraussichtlich heute abgeschlossen werden"));
        assertEquals(2, result.rankedCandidates().size(), "capacity is soft, never an exclusion");
    }

    // ── Unabhängigkeit der Mitarbeiterinnen ──

    @Test
    void recommendations_areIndependentPerEmployee() {
        String mine = UUID.randomUUID().toString();
        String others = UUID.randomUUID().toString();
        String third = UUID.randomUUID().toString();
        remainingByCategory.put("gewerbeanmeldung", 400);
        remainingByCategory.put("ummeldung", 15);
        pool(view(mine, 70, true, "READY_TO_WORK"),
                view(others, 70, true, "READY_TO_WORK"),
                view(third, 70, true, "READY_TO_WORK"));
        register(mine, me, "gewerbeanmeldung", WorkspacePhase.ANALYSIS, 0, null);
        register(others, other, "ummeldung", WorkspacePhase.INGESTION, 0, null);
        register(third, other, "ummeldung", WorkspacePhase.INGESTION, 0, null);
        when(caseWorkStateService.currentState(mine)).thenReturn(activeBy(me, 0));
        when(caseWorkStateService.currentState(others)).thenReturn(activeBy(other, 0));

        RecommendationResult mineResult = service.recommendFor(user(me, false));
        RecommendationResult otherResult = service.recommendFor(user(other, false));

        assertEquals(mine, mineResult.recommended().caseId(), "each employee is recommended their own active case");
        assertEquals(others, otherResult.recommended().caseId());
        assertTrue(mineResult.excludedCandidates().stream().anyMatch(e -> e.caseId().equals(others)),
                "another employee's active case is excluded, not silently stolen");
        assertTrue(otherResult.excludedCandidates().stream().anyMatch(e -> e.caseId().equals(mine)));
    }

    // ── Erklärbarkeit ──

    @Test
    void tradeOffs_explainWhyNotTheAlternatives() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        remainingByCategory.put("ummeldung", 15);
        remainingByCategory.put("gewerbeanmeldung", 200);
        personal(me, view(a, 70, true, "READY_TO_WORK"), view(b, 70, true, "READY_TO_WORK"));
        register(a, me, "ummeldung", WorkspacePhase.INGESTION, 0, null);
        register(b, me, "gewerbeanmeldung", WorkspacePhase.ANALYSIS, 0, null);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertNotNull(result.recommended());
        assertTrue(result.recommended().reasons().contains("Bereits von Ihnen begonnen"));
        assertFalse(result.recommended().tradeOffs().isEmpty());
        assertTrue(result.recommended().tradeOffs().get(0).contains("Restaufwand"),
                "trade-off names the actual factor: " + result.recommended().tradeOffs());
    }

    @Test
    void trace_recordsEligibleAndExcludedEntries() {
        String eligibleId = UUID.randomUUID().toString();
        String blockedId = UUID.randomUUID().toString();
        personal(me, view(eligibleId, 70, true, "READY_TO_WORK"),
                view(blockedId, 90, false, "WAITING_FOR_DOCUMENTS"));
        register(eligibleId, me, "allgemein", WorkspacePhase.ANALYSIS, 0, null);
        register(blockedId, me);

        RecommendationResult result = service.recommendFor(user(me, false));

        TraceEntry eligible = result.trace().stream()
                .filter(t -> t.caseId().equals(eligibleId)).findFirst().orElseThrow();
        assertTrue(eligible.eligible());
        assertEquals(Integer.valueOf(1), eligible.rank());
        assertNull(eligible.note());
        TraceEntry blocked = result.trace().stream()
                .filter(t -> t.caseId().equals(blockedId)).findFirst().orElseThrow();
        assertFalse(blocked.eligible());
        assertEquals("Wartet auf Unterlagen.", blocked.note());
    }

    @Test
    void nullUser_yieldsEmptyResult() {
        RecommendationResult result = service.recommendFor(null);
        assertNull(result.recommended());
        assertTrue(result.rankedCandidates().isEmpty());
        assertTrue(result.excludedCandidates().isEmpty());
        assertTrue(result.trace().isEmpty());
    }

    // ── Phase 2B.7: blockierte Erklärungen + keine Mutations-Nebenwirkung ──

    @Test
    void blockedBacklogCase_explainsTradeOff_butIsNeverRanked() {
        String workable = UUID.randomUUID().toString();
        String blocked = UUID.randomUUID().toString();
        when(casePlanningService.personalPlanning(me)).thenReturn(new PersonalPlanning(
                List.of(view(workable, 65, true, "READY_TO_WORK")),
                List.of(view(blocked, 70, false, "WAITING_FOR_DOCUMENTS"))));
        register(workable, me, "baugenehmigung", WorkspacePhase.ANALYSIS, 0, null);
        register(blocked, me);

        RecommendationResult result = service.recommendFor(user(me, false));

        assertEquals(workable, result.recommended().caseId(), "the blocked Very High case must not win");
        assertTrue(result.rankedCandidates().stream().noneMatch(r -> r.caseId().equals(blocked)),
                "blocked cases are NEVER ranked");
        assertTrue(result.recommended().tradeOffs().stream().anyMatch(t -> t.contains("Priorität")
                        && t.contains("blockiert")),
                "the blocked case appears only as an explanation: " + result.recommended().tradeOffs());
    }

    @Test
    void recommendFor_neverMutatesOwnership() {
        String id = UUID.randomUUID().toString();
        personal(me, view(id, 70, true, "READY_TO_WORK"));
        register(id, me, "allgemein", WorkspacePhase.ANALYSIS, 0, null);

        service.recommendFor(user(me, false));

        org.mockito.Mockito.verify(workspaceService, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
        assertEquals(me, workspaceService.findById(id).orElseThrow().getOwnerId(),
                "recommendation is derived — ownership stays untouched");
    }

    // ── Helfer ──

    private AuthenticatedUser user(String email, boolean admin) {
        return new AuthenticatedUser(UUID.randomUUID(), email, "Demo",
                admin ? Set.of("ADMIN") : Set.of("USER"));
    }

    private void personal(String email, CasePlanningView... views) {
        when(casePlanningService.personalPlanning(email))
                .thenReturn(new PersonalPlanning(List.of(views), List.of()));
    }

    private void pool(CasePlanningView... views) {
        when(casePlanningService.globalWorkPool()).thenReturn(List.of(views));
    }

    private CasePlanningView view(String id, int priority, boolean workable, String state) {
        return new CasePlanningView(id, priority, "Hoch", "Testgrund", workable,
                workable ? null : "blockiert", state, null, false, List.of(), null, Instant.now());
    }

    /** Registriert einen Kandidaten ohne Arbeitszustand (Fakten + Zugriff). */
    private void register(String id, String owner) {
        register(id, owner, "allgemein", WorkspacePhase.ANALYSIS, 0, null);
    }

    private void register(String id, String owner, String category, WorkspacePhase phase,
                          int waitingDays, Integer deadlineDays) {
        when(workspaceService.findById(id)).thenReturn(Optional.of(ws(id, owner)));
        when(casePlanningService.factsFor(id)).thenReturn(facts(id, category, phase,
                waitingDays, deadlineDays));
    }

    private WorkspaceEntity ws(String id, String owner) {
        WorkspaceEntity e = new WorkspaceEntity("WS-" + id.substring(0, Math.min(8, id.length())).toUpperCase(),
                "Fall " + id, "Beschreibung", "CASE", owner);
        e.setId(id);
        return e;
    }

    private CaseFacts facts(String id, String category, WorkspacePhase phase,
                            int waitingDays, Integer deadlineDays) {
        return new CaseFacts(id, "Fall " + id, "Beschreibung", category,
                WorkspaceStatus.ACTIVE, phase, "Phase", Instant.now(),
                1, 0, 0, 1, true, "COMPLETED", 1, false,
                true, 2, List.of(), waitingDays, "warten", deadlineDays, "", "", false, null);
    }

    private Map<String, Object> activeBy(String email, int accumulated) {
        return Map.of("workState", Map.of("state", "ACTIVE", "employee", email,
                "since", "2026-08-31T09:00:00Z", "accumulatedMinutes", accumulated));
    }

    private static String reasonOf(RecommendationResult result, String caseId) {
        return result.excludedCandidates().stream()
                .filter(e -> e.caseId().equals(caseId))
                .map(ExcludedCandidate::exclusionReason)
                .findFirst().orElse("NOT EXCLUDED");
    }
}
