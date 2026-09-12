package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2A: workState ACTIVE/PAUSED, waitingOn und die Messung der AKTIVEN
 * Bearbeitungszeit (Summe der ACTIVE-Intervalle; pausierte Zeit zählt nie).
 * Deterministisch über eine feste Uhr.
 */
class CaseWorkStateServiceTest {

    private WorkspaceService workspaceService;
    private CaseWorkStateService service;
    private WorkspaceEntity ws;
    private Instant now;

    private final String caseId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        now = Instant.parse("2026-08-31T09:10:00Z");
        service = new CaseWorkStateService(workspaceService,
                Clock.fixed(now, ZoneOffset.UTC));
        ws = new WorkspaceEntity("WS-WORK-1", "Fall Wohngeld", "Beschreibung",
                "CASE", "demo01@verwaltungsassistent.local");
        ws.setId(caseId);
        ws.setStatus(WorkspaceStatus.ACTIVE);
        ws.setPhase(WorkspacePhase.ANALYSIS);
        ws.setPhaseData("{}");
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(ws));
    }

    private void advance(long minutes) {
        now = now.plusSeconds(minutes * 60);
        service = new CaseWorkStateService(workspaceService, Clock.fixed(now, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> workState() {
        Object raw = ws.getPhaseDataMap().get("workState");
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @Test
    void markActive_createsActiveWithSinceAndZeroAccumulation() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");

        assertEquals("ACTIVE", workState().get("state"));
        assertEquals("demo01@verwaltungsassistent.local", workState().get("employee"));
        assertEquals(now.toString(), workState().get("since"));
        assertEquals(0L, ((Number) workState().get("accumulatedMinutes")).longValue());
    }

    @Test
    void markActive_whileActiveBySameEmployee_isNoOp() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        String since = (String) workState().get("since");
        advance(30);

        service.markActive(caseId, "demo01@verwaltungsassistent.local");

        assertEquals(since, workState().get("since"),
                "repeated 'active' must not restart the interval");
        assertEquals(0L, ((Number) workState().get("accumulatedMinutes")).longValue());
    }

    @Test
    void markPaused_stopsAccumulation_andStoresWaitingOn() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(22);

        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "DOCUMENTS", "Einkommensnachweise");

        assertEquals("PAUSED", workState().get("state"));
        assertEquals(22L, ((Number) workState().get("accumulatedMinutes")).longValue(),
                "the ACTIVE interval is accumulated when pausing");
        Map<?, ?> waiting = (Map<?, ?>) ws.getPhaseDataMap().get("waitingOn");
        assertEquals("DOCUMENTS", waiting.get("type"));
        assertEquals("Einkommensnachweise", waiting.get("note"));
        assertEquals(now.toString(), waiting.get("since"));
    }

    @Test
    void resume_clearsWaitingOn_andRestartsActiveInterval() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(22);
        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "DOCUMENTS", "");
        advance(60); // Pausezeit zählt NICHT
        service.resume(caseId, "demo01@verwaltungsassistent.local");

        assertEquals("ACTIVE", workState().get("state"));
        assertEquals(22L, ((Number) workState().get("accumulatedMinutes")).longValue(),
                "paused time is not accumulated");
        assertFalse(ws.getPhaseDataMap().containsKey("waitingOn"), "resume removes the waiting hint");
    }

    @Test
    void completeOnClose_sumsMultipleActiveIntervals_andClearsMarkers() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(22);
        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "CITIZEN", "");
        advance(300);
        service.resume(caseId, "demo01@verwaltungsassistent.local");
        advance(14);
        long observed = service.completeOnClose(caseId);

        assertEquals(36L, observed, "22 + 14 active minutes, pause excluded");
        assertFalse(ws.getPhaseDataMap().containsKey("workState"));
        assertFalse(ws.getPhaseDataMap().containsKey("waitingOn"));
    }

    @Test
    void completeOnClose_withoutActiveWork_returnsZero() {
        assertEquals(0L, service.completeOnClose(caseId),
                "a case that was never actively worked produces no observation");
    }

    @Test
    void completeOnClose_secondCall_returnsZero() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(10);
        assertEquals(10L, service.completeOnClose(caseId));
        assertEquals(0L, service.completeOnClose(caseId),
                "duplicate completion cannot produce a second duration");
    }

    @Test
    void markActive_byNonOwner_isNoOp() {
        // Invariante (Phase 2B.6): ACTIVE ⇒ employee == ownerId. Eine
        // Nicht-Zuständige (z. B. Admin-Analyse an fremdem Fall) setzt KEINEN
        // ACTIVE-Marker und übernimmt den Fall nicht.
        service.markActive(caseId, "demo02@verwaltungsassistent.local");

        assertFalse(ws.getPhaseDataMap().containsKey("workState"),
                "a non-owner cannot claim the case as ACTIVE");
    }

    @Test
    void resume_byNonOwner_isNoOp() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(22);
        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "DOCUMENTS", "");
        advance(60);

        service.resume(caseId, "demo02@verwaltungsassistent.local");

        assertEquals("PAUSED", workState().get("state"),
                "only the responsible employee can resume");
    }

    @Test
    void handoffTo_accumulatesActiveInterval_andPausesForNewOwner() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(15);
        // Übergabe: ownerId-Änderung passiert VOR dem Aufruf (CaseAssignmentService).
        ws.setOwnerId("demo02@verwaltungsassistent.local");
        service.handoffTo(caseId, "demo02@verwaltungsassistent.local");

        assertEquals("PAUSED", workState().get("state"));
        assertEquals("demo02@verwaltungsassistent.local", workState().get("employee"));
        assertEquals(15L, ((Number) workState().get("accumulatedMinutes")).longValue(),
                "the case-level active time survives the handoff");
        Map<?, ?> waiting = (Map<?, ?>) ws.getPhaseDataMap().get("waitingOn");
        assertEquals("OTHER", waiting.get("type"));
        assertTrue(String.valueOf(waiting.get("note")).contains("Übergeben an"));
    }

    @Test
    void handoffTo_unstartedCase_keepsNoWorkState() {
        ws.setOwnerId("demo02@verwaltungsassistent.local");
        service.handoffTo(caseId, "demo02@verwaltungsassistent.local");

        assertFalse(ws.getPhaseDataMap().containsKey("workState"),
                "an unstarted case stays unstarted after the handoff");
        assertFalse(ws.getPhaseDataMap().containsKey("waitingOn"));
    }

    @Test
    void handoffTo_afterPause_preservesAccumulatedTime_andReplacesWaitingReason() {
        service.markActive(caseId, "demo01@verwaltungsassistent.local");
        advance(10);
        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "CITIZEN", "Rückmeldung ausstehend");
        advance(120);
        ws.setOwnerId("demo02@verwaltungsassistent.local");
        service.handoffTo(caseId, "demo02@verwaltungsassistent.local");

        assertEquals("PAUSED", workState().get("state"));
        assertEquals(10L, ((Number) workState().get("accumulatedMinutes")).longValue(),
                "waiting time never counts");
        assertEquals("OTHER", ((Map<?, ?>) ws.getPhaseDataMap().get("waitingOn")).get("type"),
                "the handoff replaces the waiting reason with the explicit transfer note");
    }

    @Test
    void clearWaitingOn_removesOnlyTheHint() {
        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "DOCUMENTS", "");
        service.clearWaitingOn(caseId);

        assertFalse(ws.getPhaseDataMap().containsKey("waitingOn"));
        assertNotNull(workState().get("state"), "the work-state itself stays");
    }

    @Test
    void pauseWhileNeverActive_setsPausedWithoutAccumulation() {
        service.markPaused(caseId, "demo01@verwaltungsassistent.local", "OTHER", "interne Rückmeldung");

        assertEquals("PAUSED", workState().get("state"));
        assertEquals(0L, ((Number) workState().get("accumulatedMinutes")).longValue());
        assertEquals("OTHER", ((Map<?, ?>) ws.getPhaseDataMap().get("waitingOn")).get("type"));
    }
}
