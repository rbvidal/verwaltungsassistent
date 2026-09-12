package reasoning.workspace.application;

import reasoning.document.api.DocumentFacade;
import reasoning.document.api.TextExtractionService;
import reasoning.common.model.DocumentStatus;
import reasoning.document.model.Document;
import reasoning.workspace.api.*;
import reasoning.workspace.infrastructure.persistence.*;
import reasoning.workspace.model.*;
import reasoning.common.model.DocumentCategory;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.common.model.WorkspaceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Core service managing workspace CRUD, document attachment, timeline events, phase transitions, and analysis. */
@Service
@Transactional
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JpaWorkspaceRepository workspaceRepo;
    private final JpaWorkspaceDocumentLinkRepository docLinkRepo;
    private final JpaTimelineEventRepository timelineRepo;
    private final JpaWorkspaceStepRepository stepRepo;
    private final JpaWorkspaceAnalysisRunRepository analysisRunRepo;
    private final DocumentFacade documentFacade;
    private final TimelineExtractionService timelineExtractor;
    private final ObjectProvider<TextExtractionService> textExtractionService;
    private final WorkspaceOrchestrator orchestrator;

    public WorkspaceService(JpaWorkspaceRepository workspaceRepo,
                            JpaWorkspaceDocumentLinkRepository docLinkRepo,
                            JpaTimelineEventRepository timelineRepo,
                            JpaWorkspaceStepRepository stepRepo,
                            JpaWorkspaceAnalysisRunRepository analysisRunRepo,
                            DocumentFacade documentFacade,
                            TimelineExtractionService timelineExtractor,
                            ObjectProvider<TextExtractionService> textExtractionService) {
        this.workspaceRepo = workspaceRepo;
        this.docLinkRepo = docLinkRepo;
        this.timelineRepo = timelineRepo;
        this.stepRepo = stepRepo;
        this.analysisRunRepo = analysisRunRepo;
        this.documentFacade = documentFacade;
        this.timelineExtractor = timelineExtractor;
        this.textExtractionService = textExtractionService;
        this.orchestrator = new WorkspaceOrchestrator();
    }

    /** Creates a new workspace from the given command. */
    public WorkspaceEntity createWorkspace(CreateWorkspaceCommand cmd) {
        return createWorkspace(cmd, null);
    }

    /**
     * Erstellt einen Vorgang mit vorgegebenem, deterministischem Aktenzeichen
     * (Phase 2C.3b-Mailbox-Intake: {@code WS-<sha256(messageId) 8 HEX>} — über
     * Resets stabil und in Folge-E-Mails referenzierbar). {@code null} → wie
     * beim Standardaufruf wird eine zufällige {@code WS-XXXXXXXX}-Nummer
     * erzeugt.
     */
    public WorkspaceEntity createWorkspace(CreateWorkspaceCommand cmd, String workspaceCode) {
        String code = workspaceCode != null && !workspaceCode.isBlank()
                ? workspaceCode
                : "WS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String name = cmd.name() != null && !cmd.name().isBlank()
                ? cmd.name()
                : code;
        WorkspaceEntity entity = new WorkspaceEntity(
                code, name, cmd.description(), cmd.workspaceType(), cmd.createdBy());
        WorkspaceEntity saved = workspaceRepo.save(entity);
        recordStep(saved.getId(), WorkspacePhase.SETUP, "Workspace setup completed");
        log.info("Created workspace {} (code: {}, name: {})", saved.getId(),
                saved.getWorkspaceCode(), saved.getName());
        return saved;
    }

    /** Persists changes to an existing workspace entity. */
    public WorkspaceEntity save(WorkspaceEntity entity) {
        entity.setUpdatedAt(Instant.now());
        return workspaceRepo.save(entity);
    }

    /** Finds a workspace by its ID. */
    @Transactional(readOnly = true)
    public Optional<WorkspaceEntity> findById(String workspaceId) {
        return workspaceRepo.findById(UUID.fromString(workspaceId));
    }

    /** Finds workspaces owned by a given user. */
    @Transactional(readOnly = true)
    public List<WorkspaceEntity> findByOwner(String ownerId) {
        return workspaceRepo.findByOwnerId(ownerId);
    }

    /** Returns all workspaces. */
    @Transactional(readOnly = true)
    public List<WorkspaceEntity> findAll() {
        return workspaceRepo.findAll();
    }

    /** Sets the workspace phase explicitly to the given value. */
    public WorkspaceEntity setPhase(String workspaceId, WorkspacePhase phase) {
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        entity.setPhase(phase);
        entity.setUpdatedAt(Instant.now());
        recordStep(workspaceId, phase, "Phase set to " + phase.name());
        return workspaceRepo.save(entity);
    }

    /** Advances the workspace to the next sequential phase, if the current phase is complete. */
    public WorkspaceEntity advancePhase(String workspaceId) {
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        String block = advanceBlockReason(entity);
        if (block != null) {
            throw new IllegalStateException(block);
        }
        WorkspacePhase current = entity.getPhase() != null ? entity.getPhase() : WorkspacePhase.SETUP;
        WorkspacePhase next = switch (current) {
            case SETUP -> WorkspacePhase.INGESTION;
            case INGESTION -> WorkspacePhase.ANALYSIS;
            case ANALYSIS -> WorkspacePhase.REVIEW;
            case REVIEW -> WorkspacePhase.COMPLETE;
            case COMPLETE -> WorkspacePhase.COMPLETE;
        };
        entity.setPhase(next);
        entity.setUpdatedAt(Instant.now());
        recordStep(workspaceId, next, "Advanced to " + next.name());
        return workspaceRepo.save(entity);
    }

    /**
     * Returns the reason why the workspace cannot advance from its current phase,
     * or {@code null} when the transition is permitted. Deterministic rules only:
     * phase completion is derived from persisted application state, never from an LLM.
     */
    public String advanceBlockReason(String workspaceId) {
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        return advanceBlockReason(entity);
    }

    String advanceBlockReason(WorkspaceEntity entity) {
        WorkspacePhase current = entity.getPhase() != null ? entity.getPhase() : WorkspacePhase.SETUP;
        return switch (current) {
            case INGESTION -> ingestionBlockReason(entity);
            case ANALYSIS -> analysisBlockReason(entity);
            case REVIEW -> reviewBlockReason(entity);
            default -> null;
        };
    }

    /** Ingestion is complete when documents were processed successfully, or explicitly resolved without them. */
    private String ingestionBlockReason(WorkspaceEntity entity) {
        List<WorkspaceDocumentLinkEntity> links = docLinkRepo.findByWorkspaceId(UUID.fromString(entity.getId()));
        int total = links.size();
        int ready = 0;
        for (WorkspaceDocumentLinkEntity link : links) {
            try {
                if (documentFacade.getDocument(UUID.fromString(link.getDocumentId()), "system")
                        .status() == DocumentStatus.READY) {
                    ready++;
                }
            } catch (Exception e) {
                log.debug("Document status lookup failed for link {}: {}", link.getDocumentId(), e.getMessage());
            }
        }
        if (total > 0 && ready > 0) {
            return null;
        }
        if (total == 0) {
            Map<String, Object> data = parsePhaseData(entity.getPhaseData());
            if (Boolean.TRUE.equals(data.get("ingestionResolved"))) {
                return null;
            }
            return "Keine Dokumente zugeordnet. Bitte Dokumente verwalten oder „Ohne Dokumente fortfahren“ wählen.";
        }
        return "Die zugeordneten Dokumente sind noch nicht erfolgreich verarbeitet.";
    }

    /** Analysis must have reached its terminal COMPLETED state before Review becomes available. */
    private String analysisBlockReason(WorkspaceEntity entity) {
        Map<String, Object> data = parsePhaseData(entity.getPhaseData());
        Object status = data.get("analysis") instanceof Map<?, ?> m ? m.get("status") : null;
        if ("COMPLETED".equals(status)) {
            return null;
        }
        if ("FAILED".equals(status)) {
            return "Die Analyse ist fehlgeschlagen. Bitte erneut versuchen.";
        }
        if ("RUNNING".equals(status)) {
            return "Die Analyse läuft noch.";
        }
        return "Die Analyse muss zuerst durchgeführt werden.";
    }

    /** Review is complete when every checklist item is completed or explicitly not required. */
    private String reviewBlockReason(WorkspaceEntity entity) {
        Map<String, Object> data = parsePhaseData(entity.getPhaseData());
        Object raw = data.get("checklist");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return "Alle Prüfpunkte müssen abgeschlossen sein.";
        }
        long open = items.stream()
                .filter(i -> i instanceof Map<?, ?> m
                        && !Boolean.TRUE.equals(m.get("completed"))
                        && !Boolean.TRUE.equals(m.get("notRequired")))
                .count();
        if (open == 0) {
            return null;
        }
        return "Offene Prüfpunkte: " + open + ". Bitte alle Prüfpunkte abschließen.";
    }

    /**
     * Records the explicit human acknowledgment that Ingestion is resolved even though
     * no case documents exist. Persisted per case; makes the Ingestion → Analyse
     * transition available without pretending documents were ingested.
     *
     * <p>The acknowledgment also ADVANCES the phase straight into Analyse: the
     * button promises "Ohne Dokumente fortfahren" — with the ingestion gate
     * resolved, the Analyse phase is exactly where the user lands. Otherwise a
     * second "Analyse →" phase button would appear in front of the actual
     * "Analyse starten" action (confusing double analysis entry point).</p>
     */
    public WorkspaceEntity resolveIngestionWithoutDocuments(String workspaceId) {
        updatePhaseData(workspaceId, Map.of("ingestionResolved", true));
        recordStep(workspaceId, WorkspacePhase.INGESTION, "Ingestion resolved without documents");
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        if (entity.getPhase() == WorkspacePhase.INGESTION) {
            entity.setPhase(WorkspacePhase.ANALYSIS);
            entity.setUpdatedAt(Instant.now());
            recordStep(workspaceId, WorkspacePhase.ANALYSIS,
                    "Advanced to ANALYSIS (ingestion resolved without documents)");
            entity = workspaceRepo.save(entity);
        }
        return entity;
    }

    /**
     * Records the per-case analysis status written by the decision pipeline
     * (RUNNING / COMPLETED / FAILED) together with optional result details
     * (e.g. sourceCount). Source of truth for the Analyse phase state.
     */
    public void recordAnalysisStatus(String workspaceId, String status, Map<String, Object> details) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("status", status);
        analysis.put("updatedAt", Instant.now().toString());
        if (details != null) {
            analysis.putAll(details);
        }
        updatePhaseData(workspaceId, Map.of("analysis", analysis));
    }

    /** Moves the workspace back to the previous phase. */
    public WorkspaceEntity previousPhase(String workspaceId) {
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        WorkspacePhase current = entity.getPhase() != null ? entity.getPhase() : WorkspacePhase.SETUP;
        WorkspacePhase prev = switch (current) {
            case COMPLETE -> WorkspacePhase.REVIEW;
            case REVIEW -> WorkspacePhase.ANALYSIS;
            case ANALYSIS -> WorkspacePhase.INGESTION;
            case INGESTION -> WorkspacePhase.SETUP;
            case SETUP -> WorkspacePhase.SETUP;
        };
        entity.setPhase(prev);
        entity.setUpdatedAt(Instant.now());
        recordStep(workspaceId, prev, "Returned to " + prev.name());
        return workspaceRepo.save(entity);
    }

    /** Merges the given data map into the workspace's phase data JSON. */
    public void updatePhaseData(String workspaceId, Map<String, Object> data) {
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        Map<String, Object> existing = parsePhaseData(entity.getPhaseData());
        existing.putAll(data);
        try {
            entity.setPhaseData(mapper.writeValueAsString(existing));
        } catch (JsonProcessingException e) {
            entity.setPhaseData("{}");
        }
        entity.setUpdatedAt(Instant.now());
        workspaceRepo.save(entity);
    }

    /** Attaches a document to a workspace. */
    public WorkspaceDocumentLinkEntity attachDocument(AttachDocumentCommand cmd) {
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(cmd.workspaceId()))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + cmd.workspaceId()));
        String category = cmd.documentCategory() != null ? cmd.documentCategory() : "general";
        WorkspaceDocumentLinkEntity link = new WorkspaceDocumentLinkEntity(
                null, cmd.workspaceId(), cmd.documentId(), cmd.notes(),
                cmd.documentType(), category);
        WorkspaceDocumentLinkEntity saved = docLinkRepo.save(link);
        entity.setUpdatedAt(Instant.now());
        workspaceRepo.save(entity);
        return saved;
    }

    /** Corrects the document type of an existing workspace-document link. */
    public void updateDocumentLinkType(String linkId, reasoning.common.model.DocumentCategory documentType) {
        docLinkRepo.findById(UUID.fromString(linkId)).ifPresent(link -> {
            link.setDocumentType(documentType);
            docLinkRepo.save(link);
        });
    }

    /** Detaches a document link from a workspace by deleting the link. */
    public void detachDocument(String workspaceId, String linkId) {
        docLinkRepo.deleteById(UUID.fromString(linkId));
        WorkspaceEntity entity = workspaceRepo.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        entity.setUpdatedAt(Instant.now());
        workspaceRepo.save(entity);
        log.info("Detached document link {} from workspace {}", linkId, workspaceId);
    }

    /** Returns all document links for a workspace. */
    @Transactional(readOnly = true)
    public List<WorkspaceDocumentLinkEntity> getWorkspaceDocuments(String workspaceId) {
        return docLinkRepo.findByWorkspaceId(UUID.fromString(workspaceId));
    }

    /** Adds a single timeline event to a workspace. */
    public TimelineEventEntity addTimelineEvent(String workspaceId, LocalDate eventDate, String title,
                                                  String description, TimelineEventType type,
                                                  String sourceDocumentId, double confidence, boolean aiGenerated) {
        TimelineEventEntity event = new TimelineEventEntity(
                null, workspaceId, eventDate, title, description, type, sourceDocumentId, confidence, aiGenerated);
        return timelineRepo.save(event);
    }

    /** Deletes existing timeline events and saves the given replacement list. */
    public void replaceTimeline(String workspaceId, List<TimelineEventEntity> events) {
        timelineRepo.deleteByWorkspaceId(UUID.fromString(workspaceId));
        events.forEach(e -> {
            e.setId(null);
            e.setWorkspaceId(workspaceId);
        });
        timelineRepo.saveAll(events);
    }

    /** Returns timeline events for a workspace in deterministic order (event date ASC, createdAt ASC — Phase 2D.6). */
    @Transactional(readOnly = true)
    public List<TimelineEventEntity> getTimeline(String workspaceId) {
        return timelineRepo.findByWorkspaceIdOrderByEventDateAscCreatedAtAsc(UUID.fromString(workspaceId));
    }

    /** Returns completed steps for a workspace ordered by completion time. */
    @Transactional(readOnly = true)
    public List<WorkspaceStepEntity> getCompletedSteps(String workspaceId) {
        return stepRepo.findByWorkspaceIdOrderByCompletedAt(UUID.fromString(workspaceId));
    }

    /** Analyzes all documents in a workspace, extracting timeline events via {@link TimelineExtractionService}. */
    @Transactional
    public AnalysisResult analyzeDocuments(String workspaceId) {
        List<WorkspaceDocumentLinkEntity> docLinks = docLinkRepo.findByWorkspaceId(UUID.fromString(workspaceId));
        if (docLinks.isEmpty()) {
            return new AnalysisResult("NO_DOCUMENTS", 0, 0, 0, new ArrayList<>());
        }

        List<TimelineExtractionService.DocInfo> docInfos = new ArrayList<>();
        int processing = 0;
        int ready = 0;

        for (WorkspaceDocumentLinkEntity link : docLinks) {
            try {
                UUID docUuid = UUID.fromString(link.getDocumentId());
                Document doc = documentFacade.getDocument(docUuid, "system");
                boolean isProcessing = doc.status() == DocumentStatus.INGESTION_PENDING
                        || doc.status() == DocumentStatus.INGESTING
                        || doc.status() == DocumentStatus.DRAFT;
                if (isProcessing) {
                    processing++;
                } else if (doc.status() == DocumentStatus.READY) {
                    ready++;
                }
                String text = null;
                reasoning.document.model.DocumentVersion currentVersion = doc.versions().stream()
                        .filter(v -> v.versionNumber() == doc.currentVersion())
                        .findFirst().orElse(null);
                if (currentVersion != null) {
                    try {
                        TextExtractionService extractor = getTextExtractor();
                        if (extractor != null) {
                            text = extractor.extractText(doc.metadata().type(), currentVersion);
                        }
                    } catch (Exception e) {
                        log.debug("Text extraction skipped for doc {}: {}", doc.id(), e.getMessage());
                    }
                }
                docInfos.add(new TimelineExtractionService.DocInfo(
                        link.getDocumentId(), doc.status().name(), text));
            } catch (IllegalArgumentException e) {
                log.debug("Skipping doc link {}: {}", link.getDocumentId(), e.getMessage());
            }
        }

        if (processing > 0 && ready == 0 && docInfos.stream().allMatch(d -> d.text() == null)) {
            return new AnalysisResult("PROCESSING", docLinks.size(), processing, 0, new ArrayList<>());
        }

        // Remove previous AI-generated events before re-extracting
        List<TimelineEventEntity> existing = timelineRepo.findByWorkspaceIdOrderByEventDateAscCreatedAtAsc(UUID.fromString(workspaceId));
        existing.stream().filter(TimelineEventEntity::isAiGenerated).forEach(e -> timelineRepo.delete(e));

        TimelineExtractionService.ExtractionResult result = timelineExtractor.extractFromDocuments(docInfos);

        List<String> createdEventIds = new ArrayList<>();
        for (TimelineExtractionService.ExtractedEvent ee : result.events()) {
            TimelineEventEntity event = addTimelineEvent(workspaceId, ee.eventDate(), ee.title(),
                    ee.description(), ee.eventType(), ee.sourceDocumentId(), ee.confidence(), true);
            createdEventIds.add(event.getId());
        }

        String status = result.hasProcessingDocs() ? "EXTRACTING" : "COMPLETED";
        return new AnalysisResult(status, docLinks.size(), result.docsProcessing(), result.events().size(), createdEventIds);
    }

    /** Analysis result containing status, document counts, extracted event count, and event IDs. */
    public record AnalysisResult(String status, int totalDocs, int docsProcessing,
                                  int eventsExtracted, List<String> eventIds) {
        /** Returns {@code true} when all documents are still processing. */
        public boolean isProcessing() { return "PROCESSING".equals(status); }
        /** Returns {@code true} when extraction is still in progress. */
        public boolean isExtracting() { return "EXTRACTING".equals(status); }
        /** Returns {@code true} when analysis completed successfully. */
        public boolean isCompleted() { return "COMPLETED".equals(status); }
        /** Returns {@code true} when the workspace has no documents. */
        public boolean isNoDocuments() { return "NO_DOCUMENTS".equals(status); }
    }

    private TextExtractionService getTextExtractor() {
        return textExtractionService.getIfAvailable();
    }

    /** Converts a workspace entity to its full DTO representation including documents and timeline. */
    @Transactional(readOnly = true)
    public WorkspaceDto toDto(WorkspaceEntity entity) {
        List<WorkspaceDocumentLinkEntity> docLinks = docLinkRepo.findByWorkspaceId(entity.getUuid());
        List<TimelineEventEntity> timeline = timelineRepo.findByWorkspaceIdOrderByEventDateAscCreatedAtAsc(entity.getUuid());

        return new WorkspaceDto(
                entity.getId(), entity.getWorkspaceCode(),
                entity.getName(), entity.getDescription(),
                entity.getWorkspaceType(), entity.getStatus(), entity.getPhase(),
                entity.getOwnerId(), parsePhaseData(entity.getPhaseData()),
                docLinks.stream().map(this::toDocDto).collect(Collectors.toList()),
                timeline.stream().map(this::toTimelineDto).collect(Collectors.toList()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private WorkspaceDocumentDto toDocDto(WorkspaceDocumentLinkEntity link) {
        return new WorkspaceDocumentDto(
                link.getId(), link.getWorkspaceId(), link.getDocumentId(), link.getDocumentName(),
                link.getDocumentType(), link.getDocumentCategory(),
                parsePhaseData(link.getExtractedMetadata()), link.getUploadedAt());
    }

    private TimelineEventDto toTimelineDto(TimelineEventEntity e) {
        return new TimelineEventDto(
                e.getId(), e.getWorkspaceId(), e.getEventDate(), e.getTitle(), e.getDescription(),
                e.getEventType(), e.getSourceDocumentId(), e.getConfidence(), e.isAiGenerated());
    }

    private void recordStep(String workspaceId, WorkspacePhase phase, String stepName) {
        WorkspaceStepEntity step = new WorkspaceStepEntity(null, workspaceId, phase, stepName, "COMPLETED");
        stepRepo.save(step);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePhaseData(String json) {
        return fromJson(json);
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    // ── Persisted analysis runs ──────────────────────────────────────────────

    /**
     * Creates a new analysis run for the workspace and returns its version
     * (1, 2, 3, …). The run is persisted immediately so it survives restarts;
     * the result is attached by {@link #completeAnalysisRun}.
     */
    public int startAnalysisRun(String workspaceId, String triggeredBy) {
        UUID id = UUID.fromString(workspaceId);
        workspaceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        int version = (int) analysisRunRepo.countByWorkspaceId(id) + 1;
        analysisRunRepo.save(new WorkspaceAnalysisRunEntity(
                UUID.randomUUID(), id, version, "RUNNING", triggeredBy, Instant.now()));
        return version;
    }

    /** Marks the run COMPLETED and persists the structured analysis result. */
    public void completeAnalysisRun(String workspaceId, int version, int documentCount,
                                    List<String> evidenceIds, Object result) {
        WorkspaceAnalysisRunEntity run = analysisRunRepo
                .findByWorkspaceIdAndVersion(UUID.fromString(workspaceId), version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Analysis run not found: " + workspaceId + " v" + version));
        run.setStatus("COMPLETED");
        run.setCompletedAt(Instant.now());
        run.setDocumentCount(documentCount);
        run.setEvidenceIds(evidenceIds == null ? null : toJson(evidenceIds));
        run.setResultJson(toJson(result));
        analysisRunRepo.save(run);
    }

    /** Deserializes the persisted evidence identities of a run, or null. */
    public List<String> deserializeEvidenceIds(WorkspaceAnalysisRunEntity run) {
        if (run == null || run.getEvidenceIds() == null || run.getEvidenceIds().isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(run.getEvidenceIds(),
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Could not deserialize evidence ids: {}", e.getMessage());
            return null;
        }
    }

    /** Marks the run FAILED (no result is stored). */
    public void failAnalysisRun(String workspaceId, int version) {
        analysisRunRepo.findByWorkspaceIdAndVersion(UUID.fromString(workspaceId), version)
                .ifPresent(run -> {
                    run.setStatus("FAILED");
                    run.setCompletedAt(Instant.now());
                    analysisRunRepo.save(run);
                });
    }

    /** Removes all analysis runs of the workspace (demo reset / legacy-run cleanup). */
    public void deleteAnalysisRuns(String workspaceId) {
        analysisRunRepo.deleteAll(
                analysisRunRepo.findByWorkspaceIdOrderByVersionAsc(UUID.fromString(workspaceId)));
    }

    /** Hard-deletes a workspace and its derived records (demo-namespace cleanup). */
    public void deleteWorkspace(String workspaceId) {
        UUID id = UUID.fromString(workspaceId);
        deleteAnalysisRuns(workspaceId);
        docLinkRepo.deleteAll(docLinkRepo.findByWorkspaceId(id));
        timelineRepo.deleteByWorkspaceId(id);
        stepRepo.deleteByWorkspaceId(id);
        workspaceRepo.deleteById(id);
    }

    /** The most recent COMPLETED analysis run, if any. */
    public Optional<WorkspaceAnalysisRunEntity> latestCompletedAnalysisRun(String workspaceId) {
        return analysisRunRepo.findByWorkspaceIdOrderByVersionAsc(UUID.fromString(workspaceId))
                .stream()
                .filter(run -> "COMPLETED".equals(run.getStatus()))
                .reduce((first, second) -> second);
    }

    /** All analysis runs of the workspace, oldest first (history). */
    public List<WorkspaceAnalysisRunEntity> listAnalysisRuns(String workspaceId) {
        return analysisRunRepo.findByWorkspaceIdOrderByVersionAsc(UUID.fromString(workspaceId));
    }

    /** A single analysis run by version, if present. */
    public Optional<WorkspaceAnalysisRunEntity> findAnalysisRun(String workspaceId, int version) {
        return analysisRunRepo.findByWorkspaceIdAndVersion(UUID.fromString(workspaceId), version);
    }

    /** Deserializes the persisted structured result of a completed run. */
    public Map<String, Object> deserializeAnalysisResult(WorkspaceAnalysisRunEntity run) {
        if (run == null || run.getResultJson() == null || run.getResultJson().isBlank()) {
            return null;
        }
        return fromJson(run.getResultJson());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize analysis result: {}", e.getMessage());
            return null;
        }
    }
}
