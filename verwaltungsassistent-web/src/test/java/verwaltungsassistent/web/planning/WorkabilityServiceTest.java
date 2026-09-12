package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explizite Bearbeitbarkeit: blockierte vs. bearbeitbare Fälle — reine
 * Rechenlogik aus strukturierten Fakten, kein LLM.
 */
class WorkabilityServiceTest {

    private final WorkabilityService service = new WorkabilityService();

    private CaseFacts facts(WorkspaceStatus status, int ready, int processing, int failed, int total,
                            boolean ingestionResolved, String analysisStatus, boolean beingWorked) {
        return new CaseFacts(
                "case-1", "Fall", "Beschreibung", "Allgemein",
                status, WorkspacePhase.ANALYSIS, "Analyse", java.time.Instant.now(),
                ready, processing, failed, total, ingestionResolved,
                analysisStatus, null, false, null, null, List.of(),
                0, "—", null, null, "", beingWorked, null);
    }

    @Test
    void closedCase_isNotWorkable() {
        var w = service.determine(facts(WorkspaceStatus.CLOSED, 1, 0, 0, 1, true, "COMPLETED", false));
        assertFalse(w.workable());
        assertTrue(w.blockedReason().contains("abgeschlossen"));
    }

    @Test
    void documentsProcessing_isBlocked() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 0, 2, 0, 2, false, "", false));
        assertFalse(w.workable());
        assertTrue(w.blockedReason().contains("Dokumentverarbeitung läuft"));
    }

    @Test
    void allDocumentsFailed_isBlocked() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 2, 2, false, "", false));
        assertFalse(w.workable());
        assertTrue(w.blockedReason().contains("fehlgeschlagen"));
    }

    @Test
    void missingDocuments_isBlockedUntilResolved() {
        var blocked = service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 0, 0, false, "", false));
        assertFalse(blocked.workable());
        assertTrue(blocked.blockedReason().contains("Unterlagen fehlen"));

        var resolved = service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 0, 0, true, "", false));
        assertTrue(resolved.workable(), "explicit 'ohne Unterlagen fortfahren' unblocks the case");
    }

    @Test
    void analysisRunning_isBlocked() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 0, 0, 1, true, "RUNNING", false));
        assertFalse(w.workable());
        assertTrue(w.blockedReason().contains("Analyse läuft"));
    }

    @Test
    void preparedCase_isWorkable() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 0, 0, 1, true, "COMPLETED", false));
        assertTrue(w.workable());
    }

    @Test
    void analysisFailed_isWorkable() {
        // Fehlgeschlagene Analyse = Aufgabe (Neustart), keine Blockade.
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 0, 0, 1, true, "FAILED", false));
        assertTrue(w.workable());
    }

    @Test
    void partiallyProcessedDocs_isWorkable() {
        // Einige Dokumente bereit, weitere in Verarbeitung: mit den bereiten
        // kann weitergearbeitet werden.
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 1, 0, 2, false, "COMPLETED", false));
        assertTrue(w.workable());
    }

    // ── Strukturierter Zustand (Phase 1.5: Scheduler-Vertrag ohne UI-Text-Parsing) ──

    @Test
    void state_completed_forClosedCases() {
        var w = service.determine(facts(WorkspaceStatus.CLOSED, 1, 0, 0, 1, true, "COMPLETED", false));
        assertEquals(WorkabilityState.COMPLETED, w.state());
    }

    @Test
    void state_documentProcessing() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 0, 2, 0, 2, false, "", false));
        assertEquals(WorkabilityState.DOCUMENT_PROCESSING, w.state());
    }

    @Test
    void state_waitingForDocuments_forFailedOrMissingDocs() {
        assertEquals(WorkabilityState.WAITING_FOR_DOCUMENTS,
                service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 2, 2, false, "", false)).state());
        assertEquals(WorkabilityState.WAITING_FOR_DOCUMENTS,
                service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 0, 0, false, "", false)).state());
        assertEquals(WorkabilityState.READY_TO_WORK,
                service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 0, 0, true, "", false)).state(),
                "explicitly resolved ingestion is ready to work");
    }

    @Test
    void state_analysisRunning() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 0, 0, 1, true, "RUNNING", false));
        assertEquals(WorkabilityState.ANALYSIS_RUNNING, w.state());
    }

    @Test
    void state_alreadyBeingWorked_whenEmailInProgress() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 0, 0, 1, true, "COMPLETED", true));
        assertTrue(w.workable(), "being worked remains workable (continuation), not blocked");
        assertEquals(WorkabilityState.ALREADY_BEING_WORKED, w.state());
    }

    @Test
    void state_readyToWork_default() {
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 1, 0, 0, 1, true, "COMPLETED", false));
        assertEquals(WorkabilityState.READY_TO_WORK, w.state());
    }

    @Test
    void blockedState_doesNotDependOnPriority() {
        // Hohe Dringlichkeit ändert den Zustand nicht: die Blockade bleibt eine
        // Blockade — Priorität und Bearbeitbarkeit sind unabhängig.
        var w = service.determine(facts(WorkspaceStatus.ACTIVE, 0, 0, 0, 0, false, "", false));
        assertFalse(w.workable());
        assertEquals(WorkabilityState.WAITING_FOR_DOCUMENTS, w.state());
    }

    // ── Phase 2A: expliziter Wartehinweis (waitingOn) ──

    private CaseFacts withWaiting(String type, boolean docsAvailable) {
        return new CaseFacts(
                "case-1", "Fall", "Beschreibung", "Allgemein",
                WorkspaceStatus.ACTIVE, WorkspacePhase.ANALYSIS, "Analyse", java.time.Instant.now(),
                docsAvailable ? 1 : 0, 0, 0, docsAvailable ? 1 : 0, docsAvailable,
                "COMPLETED", null, false, null, null, List.of(),
                0, "—", null, null, "", false, type);
    }

    @Test
    void waitingOn_citizen_blocksAsWaitingForCitizen() {
        var w = service.determine(withWaiting("CITIZEN", true));
        assertFalse(w.workable());
        assertEquals(WorkabilityState.WAITING_FOR_CITIZEN, w.state());
        assertTrue(w.blockedReason().contains("Bürgerin"));
    }

    @Test
    void waitingOn_external_blocksAsWaitingForExternal() {
        var w = service.determine(withWaiting("EXTERNAL", true));
        assertEquals(WorkabilityState.WAITING_FOR_EXTERNAL, w.state());
        assertFalse(w.workable());
    }

    @Test
    void waitingOn_other_blocksAsWaitingForOther() {
        var w = service.determine(withWaiting("OTHER", true));
        assertEquals(WorkabilityState.WAITING_FOR_OTHER, w.state());
        assertFalse(w.workable());
    }

    @Test
    void waitingOn_documents_withDocsAvailable_doesNotBlock() {
        // Fakten haben Vorrang (Phase 2B.7): Unterlagen sind da → die
        // automatische DOKUMENT-Blockade löst sich faktengetrieben auf
        // (READY_TO_WORK). Der EXPLIZITE Wartehinweis der Mitarbeiterin wird
        // dabei nicht gelöscht — er bleibt Zustand/Historie bis zum
        // expliziten Fortsetzen (siehe CasePlanningServiceTest).
        var w = service.determine(withWaiting("DOCUMENTS", true));
        assertTrue(w.workable());
        assertEquals(WorkabilityState.READY_TO_WORK, w.state());
    }

    @Test
    void waitingOn_documents_withDocsMissing_staysFactBased() {
        // Ohne Unterlagen gilt ohnehin WAITING_FOR_DOCUMENTS — der Hinweis
        // fügt nichts hinzu.
        var w = service.determine(withWaiting("DOCUMENTS", false));
        assertEquals(WorkabilityState.WAITING_FOR_DOCUMENTS, w.state());
        assertFalse(w.workable());
    }
}
