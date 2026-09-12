package verwaltungsassistent.web.service;

import reasoning.common.model.IngestionStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.api.IngestionJobPage;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.workspace.api.TimelineEventEntity;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.api.WorkspaceStepEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.model.TimelineEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the case timeline: it must be assembled from existing
 * records (case creation, attachments, ingestion jobs, analysis runs, phase
 * steps, stored events) and must never fabricate history for empty cases.
 */
class CaseTimelineServiceTest {

    private final String caseId = UUID.randomUUID().toString();
    private final UUID docId = UUID.randomUUID();

    private WorkspaceService workspaceService() {
        WorkspaceService ws = mock(WorkspaceService.class);
        WorkspaceEntity entity = new WorkspaceEntity("WS-001", "Testfall Bauvorhaben",
                "Beschreibung", "GENERAL", "user@example.com");
        when(ws.findById(caseId)).thenReturn(Optional.of(entity));
        when(ws.getWorkspaceDocuments(caseId)).thenReturn(List.of());
        when(ws.listAnalysisRuns(caseId)).thenReturn(List.of());
        when(ws.getCompletedSteps(caseId)).thenReturn(List.of());
        when(ws.getTimeline(caseId)).thenReturn(List.of());
        return ws;
    }

    private DocumentFacade documentFacade() {
        DocumentFacade facade = mock(DocumentFacade.class);
        when(facade.findIngestionJobs(any(IngestionJobFilter.class))).thenReturn(
                new IngestionJobPage(List.of(), 0, 10, 0, 0));
        return facade;
    }

    @Test
    void fullCase_historyShowsAllRealEventsInOrder() {
        WorkspaceService ws = workspaceService();
        Instant created = Instant.now().minus(10, ChronoUnit.DAYS);
        ws.findById(caseId).orElseThrow().setCreatedAt(created);

        WorkspaceDocumentLinkEntity link = new WorkspaceDocumentLinkEntity(
                UUID.randomUUID().toString(), caseId, docId.toString(), null,
                reasoning.common.model.DocumentCategory.CONTRACT, "general");
        link.setUploadedAt(created.plus(1, ChronoUnit.DAYS));
        when(ws.getWorkspaceDocuments(caseId)).thenReturn(List.of(link));

        WorkspaceAnalysisRunEntity run = new WorkspaceAnalysisRunEntity(
                UUID.randomUUID(), UUID.fromString(caseId), 1,
                "COMPLETED", "user@example.com", created.plus(3, ChronoUnit.DAYS));
        run.setCompletedAt(created.plus(3, ChronoUnit.DAYS).plusSeconds(40));
        run.setDocumentCount(1);
        when(ws.listAnalysisRuns(caseId)).thenReturn(List.of(run));

        WorkspaceStepEntity step = new WorkspaceStepEntity();
        step.setStepName("Advanced to ANALYSIS");
        step.setStatus("COMPLETED");
        step.setCompletedAt(created.plus(2, ChronoUnit.DAYS));
        when(ws.getCompletedSteps(caseId)).thenReturn(List.of(step));

        TimelineEventEntity stored = new TimelineEventEntity(null, caseId,
                java.time.LocalDate.now(), "Notiz aus der Bearbeitung", null,
                TimelineEventType.EVENT, null, 0.9, true);
        when(ws.getTimeline(caseId)).thenReturn(List.of(stored));

        DocumentFacade facade = documentFacade();
        when(facade.findIngestionJobs(any(IngestionJobFilter.class))).thenReturn(
                new IngestionJobPage(
                        List.of(new DocumentIngestionJob(UUID.randomUUID(), docId,
                                IngestionStatus.COMPLETED, "upload", "user@example.com", "tenant",
                                null, created.plus(1, ChronoUnit.DAYS).plusSeconds(30),
                                created.plus(1, ChronoUnit.DAYS).plusSeconds(90),
                                created.plus(1, ChronoUnit.DAYS).plusSeconds(90), 1)),
                        0, 1, 1, 1));

        var entries = new CaseTimelineService(ws, facade).build(caseId);

        List<String> titles = entries.stream().map(CaseTimelineService.TimelineEntry::title).toList();
        assertTrue(titles.contains("Vorgang angelegt"), "case creation is a real recorded event");
        assertTrue(titles.contains("Dokument hinzugefügt"), "attachment is a real recorded event");
        assertTrue(titles.contains("Dokument ingestiert"), "completed ingestion job is a real event");
        assertTrue(titles.contains("Phase: Analyse"), "phase transition is a real event");
        assertTrue(titles.contains("Analyse durchgeführt"), "completed analysis run is a real event");
        assertTrue(titles.contains("Notiz aus der Bearbeitung"), "stored timeline event is preserved");
        assertEquals(titles.get(0), "Vorgang angelegt", "timeline must be chronological");
    }

    @Test
    void newCase_withoutActivity_showsOnlyCaseCreation() {
        WorkspaceService ws = workspaceService();
        var entries = new CaseTimelineService(ws, documentFacade()).build(caseId);

        assertEquals(1, entries.size(), "no fabricated history for an empty case");
        assertEquals("Vorgang angelegt", entries.get(0).title());
    }

    @Test
    void failedIngestion_isReportedHonestly() {
        WorkspaceService ws = workspaceService();
        Instant created = ws.findById(caseId).orElseThrow().getCreatedAt();

        WorkspaceDocumentLinkEntity link = new WorkspaceDocumentLinkEntity(
                UUID.randomUUID().toString(), caseId, docId.toString(), null,
                reasoning.common.model.DocumentCategory.CONTRACT, "general");
        link.setUploadedAt(created);
        when(ws.getWorkspaceDocuments(caseId)).thenReturn(List.of(link));

        DocumentFacade facade = documentFacade();
        when(facade.findIngestionJobs(any(IngestionJobFilter.class))).thenReturn(
                new IngestionJobPage(
                        List.of(new DocumentIngestionJob(UUID.randomUUID(), docId,
                                IngestionStatus.FAILED, "upload", "user@example.com", "tenant",
                                "OCR fehlgeschlagen", created.plusSeconds(60),
                                created.plusSeconds(120), created.plusSeconds(120), 1)),
                        0, 1, 1, 1));

        var entries = new CaseTimelineService(ws, facade).build(caseId);

        assertTrue(entries.stream().anyMatch(e -> e.title().contains("Indexierung fehlgeschlagen")),
                "failed ingestion must be shown, not hidden");
    }

    @Test
    void unknownCase_returnsEmptyTimeline() {
        WorkspaceService ws = mock(WorkspaceService.class);
        when(ws.findById(caseId)).thenReturn(Optional.empty());
        var entries = new CaseTimelineService(ws, documentFacade()).build(caseId);
        assertTrue(entries.isEmpty());
    }
}
