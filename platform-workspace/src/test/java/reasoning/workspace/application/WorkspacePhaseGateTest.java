package reasoning.workspace.application;

import reasoning.common.model.DocumentCategory;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.TextExtractionService;
import reasoning.document.model.Document;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.infrastructure.persistence.JpaTimelineEventRepository;
import reasoning.workspace.infrastructure.persistence.JpaWorkspaceAnalysisRunRepository;
import reasoning.workspace.infrastructure.persistence.JpaWorkspaceDocumentLinkRepository;
import reasoning.workspace.infrastructure.persistence.JpaWorkspaceRepository;
import reasoning.workspace.infrastructure.persistence.JpaWorkspaceStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Deterministic phase-transition gate rules in WorkspaceService:
 * a phase may only advance when its persisted completion state permits it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspacePhaseGateTest {

    @Mock
    private JpaWorkspaceRepository workspaceRepo;
    @Mock
    private JpaWorkspaceDocumentLinkRepository docLinkRepo;
    @Mock
    private JpaTimelineEventRepository timelineRepo;
    @Mock
    private JpaWorkspaceStepRepository stepRepo;
    @Mock
    private JpaWorkspaceAnalysisRunRepository analysisRunRepo;
    @Mock
    private DocumentFacade documentFacade;
    @Mock
    private TimelineExtractionService timelineExtractor;
    @Mock
    private ObjectProvider<TextExtractionService> textExtractionService;

    private WorkspaceService service;
    private WorkspaceEntity entity;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(workspaceRepo, docLinkRepo, timelineRepo, stepRepo, analysisRunRepo,
                documentFacade, timelineExtractor, textExtractionService);
        entity = new WorkspaceEntity("WS-TEST", "Testfall", "Testbeschreibung", "CASE", "admin@verwaltungsassistent.local");
        entity.setId(id.toString());
        when(workspaceRepo.findById(id)).thenReturn(Optional.of(entity));
        when(workspaceRepo.save(any(WorkspaceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void phase(WorkspacePhase p) {
        entity.setPhase(p);
    }

    private void phaseData(String json) {
        entity.setPhaseData(json);
    }

    private Document doc(DocumentStatus status) {
        return new Document(UUID.randomUUID(), null, null, status, 1, null, null, null, null, List.of());
    }

    private void attachDocument(DocumentStatus status) {
        UUID docId = UUID.randomUUID();
        when(docLinkRepo.findByWorkspaceId(id)).thenReturn(List.of(
                new WorkspaceDocumentLinkEntity(null, id.toString(), docId.toString(), "Antrag.pdf",
                        DocumentCategory.CONTRACT, "general")));
        when(documentFacade.getDocument(docId, "system")).thenReturn(doc(status));
    }

    // ── Phase 1 → 2 ──

    @Test
    void setupPhase_advanceIsAllowed() {
        phase(WorkspacePhase.SETUP);
        assertNull(service.advanceBlockReason(id.toString()));
        assertEquals(WorkspacePhase.INGESTION, service.advancePhase(id.toString()).getPhase());
    }

    // ── Phase 2: Ingestion ──

    @Test
    void ingestion_zeroDocuments_isBlockedUntilResolved() {
        phase(WorkspacePhase.INGESTION);
        when(docLinkRepo.findByWorkspaceId(id)).thenReturn(List.of());

        String reason = service.advanceBlockReason(id.toString());
        assertNotNull(reason);
        assertTrue(reason.contains("Keine Dokumente"));
        assertThrows(IllegalStateException.class, () -> service.advancePhase(id.toString()));

        // Explicit human acknowledgment unblocks without pretending documents exist.
        // It ALSO advances straight into Analyse ("Ohne Dokumente fortfahren" lands
        // in the Analyse phase with the single "Analyse starten" action) — the
        // Analyse gate (completed analysis required) applies from there on.
        service.resolveIngestionWithoutDocuments(id.toString());
        assertEquals(WorkspacePhase.ANALYSIS,
                service.findById(id.toString()).orElseThrow().getPhase());
        assertNotNull(service.advanceBlockReason(id.toString()));
        assertTrue(service.advanceBlockReason(id.toString()).contains("Analyse"));
    }

    @Test
    void ingestion_readyDocument_allowsAdvance() {
        phase(WorkspacePhase.INGESTION);
        attachDocument(DocumentStatus.READY);

        assertNull(service.advanceBlockReason(id.toString()));
        assertEquals(WorkspacePhase.ANALYSIS, service.advancePhase(id.toString()).getPhase());
    }

    @Test
    void ingestion_onlyProcessingDocuments_isBlocked() {
        phase(WorkspacePhase.INGESTION);
        attachDocument(DocumentStatus.INGESTING);

        assertNotNull(service.advanceBlockReason(id.toString()));
        assertThrows(IllegalStateException.class, () -> service.advancePhase(id.toString()));
    }

    @Test
    void ingestion_onlyFailedDocuments_isBlocked() {
        phase(WorkspacePhase.INGESTION);
        attachDocument(DocumentStatus.FAILED);

        assertNotNull(service.advanceBlockReason(id.toString()));
    }

    // ── Phase 3: Analyse ──

    @Test
    void analysis_notStarted_isBlocked() {
        phase(WorkspacePhase.ANALYSIS);
        phaseData("{}");

        String reason = service.advanceBlockReason(id.toString());
        assertNotNull(reason);
        assertTrue(reason.contains("Analyse muss zuerst"));
        assertThrows(IllegalStateException.class, () -> service.advancePhase(id.toString()));
    }

    @Test
    void analysis_completed_allowsAdvance() {
        phase(WorkspacePhase.ANALYSIS);
        phaseData("{\"analysis\":{\"status\":\"COMPLETED\",\"sourceCount\":6}}");

        assertNull(service.advanceBlockReason(id.toString()));
        assertEquals(WorkspacePhase.REVIEW, service.advancePhase(id.toString()).getPhase());
    }

    @Test
    void analysis_completedWithZeroFindings_isStillComplete() {
        phase(WorkspacePhase.ANALYSIS);
        phaseData("{\"analysis\":{\"status\":\"COMPLETED\",\"sourceCount\":0}}");

        assertNull(service.advanceBlockReason(id.toString()));
    }

    @Test
    void analysis_failed_isBlocked() {
        phase(WorkspacePhase.ANALYSIS);
        phaseData("{\"analysis\":{\"status\":\"FAILED\"}}");

        String reason = service.advanceBlockReason(id.toString());
        assertNotNull(reason);
        assertTrue(reason.contains("fehlgeschlagen"));
        assertThrows(IllegalStateException.class, () -> service.advancePhase(id.toString()));
    }

    @Test
    void analysis_running_isBlocked() {
        phase(WorkspacePhase.ANALYSIS);
        phaseData("{\"analysis\":{\"status\":\"RUNNING\"}}");

        assertNotNull(service.advanceBlockReason(id.toString()));
    }

    // ── Phase 4: Überprüfung ──

    @Test
    void review_incompleteChecklist_isBlocked() {
        phase(WorkspacePhase.REVIEW);
        phaseData("{\"checklist\":["
                + "{\"id\":\"rev-1\",\"label\":\"a\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-2\",\"label\":\"b\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-3\",\"label\":\"c\",\"completed\":false,\"notRequired\":false},"
                + "{\"id\":\"rev-4\",\"label\":\"d\",\"completed\":false,\"notRequired\":false}]}");

        assertNotNull(service.advanceBlockReason(id.toString()));
        assertThrows(IllegalStateException.class, () -> service.advancePhase(id.toString()));
    }

    @Test
    void review_completeChecklist_allowsAdvance() {
        phase(WorkspacePhase.REVIEW);
        phaseData("{\"checklist\":["
                + "{\"id\":\"rev-1\",\"label\":\"a\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-2\",\"label\":\"b\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-3\",\"label\":\"c\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-4\",\"label\":\"d\",\"completed\":true,\"notRequired\":false}]}");

        assertNull(service.advanceBlockReason(id.toString()));
        assertEquals(WorkspacePhase.COMPLETE, service.advancePhase(id.toString()).getPhase());
    }

    @Test
    void review_notRequiredItems_countAsComplete() {
        phase(WorkspacePhase.REVIEW);
        phaseData("{\"checklist\":["
                + "{\"id\":\"rev-1\",\"label\":\"a\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-2\",\"label\":\"b\",\"completed\":true,\"notRequired\":false},"
                + "{\"id\":\"rev-3\",\"label\":\"c\",\"completed\":false,\"notRequired\":true},"
                + "{\"id\":\"rev-4\",\"label\":\"d\",\"completed\":false,\"notRequired\":true}]}");

        assertNull(service.advanceBlockReason(id.toString()));
    }

    // ── Backward navigation ──

    @Test
    void previousPhase_alwaysAllowed_andDoesNotEraseState() {
        phase(WorkspacePhase.ANALYSIS);
        // The case was resolved without documents before analysis, and the analysis completed.
        phaseData("{\"ingestionResolved\":true,\"analysis\":{\"status\":\"COMPLETED\",\"sourceCount\":3}}");
        when(docLinkRepo.findByWorkspaceId(id)).thenReturn(List.of());

        assertEquals(WorkspacePhase.INGESTION, service.previousPhase(id.toString()).getPhase());
        // Forward again: the resolved-ingestion flag and the completed analysis marker survive
        assertEquals(WorkspacePhase.ANALYSIS, service.advancePhase(id.toString()).getPhase());
        assertNull(service.advanceBlockReason(id.toString()));
    }
}
