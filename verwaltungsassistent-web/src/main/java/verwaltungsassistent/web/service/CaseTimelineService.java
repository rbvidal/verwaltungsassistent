package verwaltungsassistent.web.service;

import reasoning.document.api.DocumentFacade;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.workspace.api.TimelineEventEntity;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.api.WorkspaceStepEntity;
import reasoning.workspace.application.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the case timeline from records the system already persists:
 * case creation, attached documents (with their ingestion jobs), analysis
 * runs, phase transitions and stored timeline events. Nothing is fabricated:
 * entries appear only where the underlying record exists. Future activity
 * (new analysis runs, attachments, ingestions, exports) shows up
 * automatically.
 */
@Service
public class CaseTimelineService {

    private static final Logger log = LoggerFactory.getLogger(CaseTimelineService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;

    public CaseTimelineService(WorkspaceService workspaceService, DocumentFacade documentFacade) {
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
    }

    public List<TimelineEntry> build(String workspaceId) {
        List<TimelineEntry> entries = new ArrayList<>();
        WorkspaceEntity ws = workspaceService.findById(workspaceId).orElse(null);
        if (ws == null) return List.of();

        if (ws.getCreatedAt() != null) {
            entries.add(entry(ws.getCreatedAt(), "Vorgang angelegt",
                    "Fall: " + (ws.getName() != null ? ws.getName() : "—"),
                    "CASE_CREATED", false, 0, ws.getOwnerId()));
        }

        List<WorkspaceDocumentLinkEntity> links =
                nullable(workspaceService.getWorkspaceDocuments(workspaceId));
        for (WorkspaceDocumentLinkEntity link : links) {
            String title = documentTitle(link);
            if (link.getUploadedAt() != null) {
                entries.add(entry(link.getUploadedAt(), "Dokument hinzugefügt", title,
                        "DOCUMENT_ADDED", false, 0, null));
            }
            DocumentIngestionJob job = latestIngestionJob(link.getDocumentUuid());
            if (job != null && job.completedAt() != null) {
                boolean ok = "COMPLETED".equalsIgnoreCase(String.valueOf(job.status()));
                entries.add(entry(job.completedAt(),
                        ok ? "Dokument ingestiert" : "Indexierung fehlgeschlagen",
                        title + (ok ? "" : " (" + job.failureReason() + ")"),
                        ok ? "DOCUMENT_INGESTED" : "DOCUMENT_FAILED", false, 0,
                        job.requestedBy()));
            }
        }

        for (WorkspaceAnalysisRunEntity run : nullable(workspaceService.listAnalysisRuns(workspaceId))) {
            Instant at = run.getCompletedAt() != null ? run.getCompletedAt() : run.getCreatedAt();
            boolean ok = "COMPLETED".equalsIgnoreCase(run.getStatus());
            entries.add(entry(at, ok ? "Analyse durchgeführt" : "Analyse fehlgeschlagen",
                    run.getDocumentCount() + " Dokument(e) ausgewertet",
                    ok ? "ANALYSIS" : "ANALYSIS_FAILED", true, 0,
                    run.getTriggeredBy()));
        }

        for (WorkspaceStepEntity step : nullable(workspaceService.getCompletedSteps(workspaceId))) {
            if (step.getCompletedAt() == null || step.getStepName() == null) continue;
            entries.add(entry(step.getCompletedAt(), phaseLabel(step.getStepName()),
                    null, "PHASE", false, 0, null));
        }

        for (TimelineEventEntity ev : nullable(workspaceService.getTimeline(workspaceId))) {
            Instant at = ev.getCreatedAt() != null ? ev.getCreatedAt()
                    : (ev.getEventDate() != null ? ev.getEventDate().atStartOfDay(ZoneId.systemDefault()).toInstant() : null);
            if (at == null) continue;
            entries.add(entry(at, ev.getTitle(), ev.getDescription(),
                    ev.getEventType() != null ? ev.getEventType().name() : "EVENT",
                    ev.isAiGenerated(), ev.getConfidence(), null));
        }

        entries.sort(Comparator.comparing(TimelineEntry::at));
        return entries;
    }

    private String documentTitle(WorkspaceDocumentLinkEntity link) {
        if (link.getDocumentName() != null && !link.getDocumentName().isBlank()) {
            return link.getDocumentName();
        }
        if (link.getDocumentUuid() != null) {
            try {
                Document doc = documentFacade.getDocument(link.getDocumentUuid(), "system");
                if (doc != null && doc.metadata() != null && doc.metadata().title() != null
                        && !doc.metadata().title().isBlank()) {
                    return doc.metadata().title();
                }
            } catch (Exception e) {
                log.debug("Document title lookup failed for {}: {}", link.getDocumentUuid(), e.getMessage());
            }
        }
        return "Dokument";
    }

    private DocumentIngestionJob latestIngestionJob(UUID documentId) {
        if (documentId == null) return null;
        try {
            var page = documentFacade.findIngestionJobs(new IngestionJobFilter(documentId, null, null, 0, 10));
            return page.jobs().stream()
                    .max(Comparator.comparing(DocumentIngestionJob::createdAt))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Ingestion job lookup failed for {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    /** User-facing German label for the recorded phase-transition step names. */
    private static String phaseLabel(String stepName) {
        String s = stepName.toLowerCase(Locale.ROOT);
        if (s.contains("setup")) return "Vorgang eingerichtet";
        if (s.contains("advanced to ingestion")) return "Phase: Dokumentenerfassung";
        if (s.contains("advanced to analysis")) return "Phase: Analyse";
        if (s.contains("advanced to review")) return "Phase: Prüfung";
        if (s.contains("advanced to complete")) return "Phase: Abschluss";
        if (s.contains("returned to setup")) return "Zurück zur Anlage";
        return stepName;
    }

    private static <T> List<T> nullable(List<T> list) {
        return list != null ? list : List.of();
    }

    private static TimelineEntry entry(Instant at, String title, String detail,
                                       String type, boolean aiGenerated, double confidence,
                                       String user) {
        return new TimelineEntry("tl-" + type + "-" + at.toEpochMilli(),
                at, DATE_FMT.format(at.atZone(ZoneId.systemDefault())),
                title, detail, type, aiGenerated, confidence, user);
    }

    /** One event on the case timeline. */
    public record TimelineEntry(String id, Instant at, String date, String title,
                                String detail, String type, boolean aiGenerated,
                                double confidence, String user) {}
}
