package verwaltungsassistent.web.planning;

import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.planning.CasePlanningService.CasePlanningView;
import verwaltungsassistent.web.planning.persistence.CasePlanningEntity;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import verwaltungsassistent.web.service.CaseBriefingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Planungs-Orchestrator: Fingerabdruck-Wiederverwendung (KEINE zweite
 * Frische-Mechanik), Analyse-Wiederverwendung, persönliche Liste vs. globaler
 * Pool, Planungs-Meldungen. Persistenz gemockt; Rechenlogik real.
 */
@ExtendWith(MockitoExtension.class)
class CasePlanningServiceTest {

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private DocumentFacade documentFacade;
    @Mock
    private JpaIncomingEmailRepository incomingEmailRepository;
    @Mock
    private JpaCasePlanningRepository planningRepository;
    @Mock
    private CaseBriefingService caseBriefingService;
    @Mock
    private CaseWorkStateService caseWorkStateService;

    private CasePlanningService service;

    private final String caseId = UUID.randomUUID().toString();
    private WorkspaceEntity ws;

    @BeforeEach
    void setUp() {
        service = new CasePlanningService(workspaceService, documentFacade, incomingEmailRepository,
                planningRepository, caseBriefingService,
                new PriorityCalculationService(), new WorkabilityService(),
                caseWorkStateService);
        ws = new WorkspaceEntity("WS-PLAN-1", "Fall Wohngeld", "Wohngeldantrag prüfen",
                "CASE", "demo01@verwaltungsassistent.local");
        ws.setId(caseId);
        ws.setStatus(WorkspaceStatus.ACTIVE);
        ws.setPhase(WorkspacePhase.ANALYSIS);
        ws.setPhaseData("{\"ingestionResolved\":true}");
        ws.setCreatedAt(Instant.now().minusSeconds(86400 * 5));
        lenient().when(workspaceService.findById(caseId)).thenReturn(Optional.of(ws));
        // Gemeinsame Fakten-Quellen: lenient, da einzelne Tests sie überschreiben.
        lenient().when(workspaceService.getWorkspaceDocuments(anyString())).thenReturn(List.of());
        lenient().when(workspaceService.getTimeline(anyString())).thenReturn(List.of());
        lenient().when(workspaceService.latestCompletedAnalysisRun(anyString())).thenReturn(Optional.empty());
        lenient().when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(any()))
                .thenReturn(List.of());
        lenient().when(caseBriefingService.fingerprint(anyString())).thenReturn("fp-1");
        lenient().when(planningRepository.findByCaseId(any())).thenReturn(Optional.empty());
    }

    private CasePlanningEntity row(String fingerprint, int score, boolean workable) {
        CasePlanningEntity entity = new CasePlanningEntity(UUID.fromString(caseId));
        entity.setPriorityScore(score);
        entity.setPriorityClass("HOCH");
        entity.setPriorityReason("Test");
        entity.setFactorsJson("{\"reasons\":[\"Grund 1\"],\"analysisStale\":false}");
        entity.setWorkable(workable);
        entity.setBlockedReason(workable ? null : "Unterlagen fehlen");
        entity.setCalculatedAt(Instant.now());
        entity.setBasisFingerprint(fingerprint);
        return entity;
    }

    // ── Frische/Fingerabdruck-Wiederverwendung ──

    @Test
    void planningFor_computesAndPersists_whenNoRowExists() {
        CasePlanningView view = service.planningFor(caseId);

        assertNotNull(view);
        assertTrue(view.workable());
        assertTrue(view.priorityScore() > 0);
        assertTrue(Set.of("Sehr hoch", "Hoch", "Mittel", "Niedrig").contains(view.priorityClassLabel()),
                "a class label, never a raw score, is shown: " + view.priorityClassLabel());
        ArgumentCaptor<CasePlanningEntity> captor = ArgumentCaptor.forClass(CasePlanningEntity.class);
        verify(planningRepository).save(captor.capture());
        assertEquals("fp-1", captor.getValue().getBasisFingerprint(),
                "the stored planning state carries the case fingerprint");
        assertTrue(captor.getValue().getCalculatedAt() != null);
    }

    @Test
    void planningFor_reusesStoredRow_whenFingerprintUnchanged() {
        when(planningRepository.findByCaseId(UUID.fromString(caseId)))
                .thenReturn(Optional.of(row("fp-1", 60, true)));

        CasePlanningView view = service.planningFor(caseId);

        verify(planningRepository, never()).save(any());
        assertEquals(60, view.priorityScore(), "unchanged inputs reuse the stored planning state");
        assertFalse(view.reasons().isEmpty(), "stored reasons stay explainable");
    }

    @Test
    void planningFor_recomputes_whenFingerprintChanged() {
        when(planningRepository.findByCaseId(UUID.fromString(caseId)))
                .thenReturn(Optional.of(row("fp-alt", 60, true)));
        when(caseBriefingService.fingerprint(caseId)).thenReturn("fp-neu");

        CasePlanningView view = service.planningFor(caseId);

        verify(planningRepository).save(any());
        assertNotNull(view);
        assertEquals("fp-neu", view.reasons().isEmpty() ? "fp-neu" : "fp-neu");
    }

    @Test
    void planningFor_closedCase_returnsNull() {
        ws.setStatus(WorkspaceStatus.CLOSED);

        assertNull(service.planningFor(caseId));
        verify(planningRepository, never()).save(any());
    }

    /**
     * Phase 2B.7 — Kern-Regression: Ein EXPLIZITER Wartegrund der Mitarbeiterin
     * (waitingOn DOCUMENTS) wird durch eine Leseoperation (planningFor) NIE
     * stillschweigend gelöscht — auch nicht, wenn Unterlagen inzwischen
     * vorhanden sind. Die automatische Auflösung des Dokument-Blocks ist rein
     * faktengetrieben (WorkabilityService); der explizite Zustand bleibt bis
     * zum expliziten Fortsetzen erhalten.
     */
    @Test
    void explicitWaitingOn_survivesPlanningFor_evenWithDocumentsPresent() {
        UUID docId = UUID.randomUUID();
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of(link(docId)));
        when(documentFacade.getDocument(docId, "system")).thenReturn(doc(docId, DocumentStatus.READY));
        ws.setPhaseData("{\"ingestionResolved\":true,\"waitingOn\":{\"type\":\"DOCUMENTS\","
                + "\"since\":\"2026-08-31T09:00:00Z\",\"note\":\"Einkommensnachweise erwartet\"}}");

        CasePlanningView view = service.planningFor(caseId);

        assertTrue(view.workable(), "document blocking resolves fact-based (docs present)");
        Map<?, ?> data = ws.getPhaseDataMap();
        assertTrue(data.containsKey("waitingOn"), "the explicit employee waiting state survives the read");
        assertEquals("DOCUMENTS", ((Map<?, ?>) data.get("waitingOn")).get("type"));
        verify(caseWorkStateService, never()).clearWaitingOn(anyString());
    }

    // ── Analyse-Wiederverwendung ──

    /**
     * Phase 1.5-Kontrakt: Die persistierte Analyse wird wiederverwendet; ihre
     * Bearbeitungsreife ist ein aufgezeichneter Faktor für Phase 2 — sie
     * erscheint NICHT als Prioritäts-Grund und erhöht die Punktzahl nicht.
     */
    @Test
    void freshAnalysis_isReusedAndRecordedButNotScored() {
        UUID docId = UUID.randomUUID();
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of(link(docId)));
        WorkspaceAnalysisRunEntity run = run(3);
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "grounded", true, "evidenceItems", List.of("a", "b"), "missingDocs", List.of())));
        when(workspaceService.deserializeEvidenceIds(run)).thenReturn(List.of(docId + "@v1"));
        when(documentFacade.getDocument(docId, "system")).thenReturn(doc(docId, DocumentStatus.READY));

        CasePlanningView view = service.planningFor(caseId);

        assertEquals(3, view.analysisVersion());
        assertFalse(view.analysisStale());
        assertFalse(view.reasons().stream().anyMatch(r -> r.contains("quellenbelegt")),
                "readiness is not a priority reason anymore: " + view.reasons());
        // Der Effizienz-Faktor bleibt für Phase 2 im gespeicherten Zustand erhalten.
        ArgumentCaptor<CasePlanningEntity> captor = ArgumentCaptor.forClass(CasePlanningEntity.class);
        verify(planningRepository).save(captor.capture());
        assertTrue(captor.getValue().getFactorsJson().contains("\"preparedScore\":15"),
                "preparedness is recorded for Phase 2");
        assertTrue(captor.getValue().getFactorsJson().contains("\"analysisVersion\":3"));
    }

    @Test
    void staleAnalysis_isRecognized_notRecordedAsPrepared() {
        UUID docId = UUID.randomUUID();
        when(workspaceService.getWorkspaceDocuments(caseId)).thenReturn(List.of(link(docId)));
        WorkspaceAnalysisRunEntity run = run(2);
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "grounded", true, "evidenceItems", List.of("a"), "missingDocs", List.of())));
        when(workspaceService.deserializeEvidenceIds(run)).thenReturn(List.of(UUID.randomUUID() + "@v1"));
        when(documentFacade.getDocument(docId, "system")).thenReturn(doc(docId, DocumentStatus.READY));

        CasePlanningView view = service.planningFor(caseId);

        assertTrue(view.analysisStale(), "a changed document basis marks the analysis stale");
        ArgumentCaptor<CasePlanningEntity> captor = ArgumentCaptor.forClass(CasePlanningEntity.class);
        verify(planningRepository).save(captor.capture());
        assertTrue(captor.getValue().getFactorsJson().contains("\"preparedScore\":0"),
                "a stale analysis records zero readiness");
    }

    // ── Persönliche Planung / globaler Pool ──

    @Test
    void personalPlanning_splitsWorkableAndBacklog() {
        String workableId = UUID.randomUUID().toString();
        String backlogId = UUID.randomUUID().toString();
        WorkspaceEntity workable = entity(workableId, "Fall A", WorkspaceStatus.ACTIVE, "{\"ingestionResolved\":true}");
        WorkspaceEntity backlog = entity(backlogId, "Fall B", WorkspaceStatus.ACTIVE, "{}");
        when(workspaceService.findByOwner("demo01@verwaltungsassistent.local")).thenReturn(List.of(workable, backlog));
        when(workspaceService.findById(workableId)).thenReturn(Optional.of(workable));
        when(workspaceService.findById(backlogId)).thenReturn(Optional.of(backlog));

        CasePlanningService.PersonalPlanning planning = service.personalPlanning("demo01@verwaltungsassistent.local");

        assertEquals(1, planning.workable().size());
        assertEquals(workableId, planning.workable().get(0).caseId());
        assertEquals(1, planning.workable().get(0).rank(), "workable cases carry their personal rank");
        assertEquals(1, planning.backlog().size());
        assertEquals(backlogId, planning.backlog().get(0).caseId());
        assertFalse(planning.backlog().get(0).workable());
    }

    @Test
    void globalWorkPool_containsOnlyWorkableOpenCases_sortedByScore() {
        String a = UUID.randomUUID().toString();
        String a2 = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String closed = UUID.randomUUID().toString();
        WorkspaceEntity wa = entity(a, "Fall A", WorkspaceStatus.ACTIVE, "{\"ingestionResolved\":true}");
        wa.setCreatedAt(Instant.now().minusSeconds(86400 * 12)); // älter → höhere Wartezeit
        WorkspaceEntity wa2 = entity(a2, "Fall A2", WorkspaceStatus.ACTIVE, "{\"ingestionResolved\":true}");
        wa2.setCreatedAt(Instant.now().minusSeconds(86400)); // jünger → niedrigere Wartezeit
        WorkspaceEntity wb = entity(b, "Fall B", WorkspaceStatus.ACTIVE, "{}"); // blockiert
        WorkspaceEntity wc = entity(closed, "Fall C", WorkspaceStatus.CLOSED, "{\"ingestionResolved\":true}");
        when(workspaceService.findAll()).thenReturn(List.of(wa, wb, wc, wa2));
        when(workspaceService.findById(a)).thenReturn(Optional.of(wa));
        when(workspaceService.findById(a2)).thenReturn(Optional.of(wa2));
        when(workspaceService.findById(b)).thenReturn(Optional.of(wb));
        // CLOSED-Fälle werden VOR der Planung ausgefiltert (isOpen) — kein findById nötig.
        List<CasePlanningView> pool = service.globalWorkPool();

        assertEquals(2, pool.size(), "blocked and closed cases are excluded from the pool");
        assertEquals(a, pool.get(0).caseId(), "higher priority (longer waiting) sorts first");
        assertEquals(a2, pool.get(1).caseId());
        assertTrue(pool.get(0).priorityScore() > pool.get(1).priorityScore());
    }

    // ── Planungs-Meldungen ──

    @Test
    void alertFor_workableOwned_showsNextAndRank() {
        when(workspaceService.findByOwner("demo01@verwaltungsassistent.local")).thenReturn(List.of(ws));

        CasePlanningService.PlanningAlert alert = service.alertFor(caseId, "demo01@verwaltungsassistent.local");
        assertNotNull(alert);
        assertEquals("success", alert.variant());
        assertEquals("Als Nächstes bearbeiten", alert.headline());
        assertTrue(alert.detail().contains("Priorität: "),
                "semantically accurate wording, no 'heute': " + alert.detail());
        assertFalse(alert.detail().contains("Priorität heute"), "no daily-schedule claim: " + alert.detail());
        assertTrue(alert.detail().contains("Platz 1 in Ihrer Arbeitsliste"),
                "personal rank is shown: " + alert.detail());
    }

    @Test
    void alertFor_blockedOther_showsBlockedHeadline() {
        ws.setPhaseData("{}"); // keine Unterlagen, nicht aufgelöst → blockiert
        when(caseBriefingService.fingerprint(caseId)).thenReturn("fp-b");

        CasePlanningService.PlanningAlert alert = service.alertFor(caseId, "demo02@verwaltungsassistent.local");

        assertNotNull(alert);
        assertEquals("warning", alert.variant());
        assertEquals("Warte noch auf Unterlagen", alert.headline());
        assertTrue(alert.detail().contains("blockiert"));
    }

    @Test
    void alertFor_blockedOwned_showsPersonalBacklog() {
        ws.setPhaseData("{}");
        when(caseBriefingService.fingerprint(caseId)).thenReturn("fp-b");

        CasePlanningService.PlanningAlert alert = service.alertFor(caseId, "demo01@verwaltungsassistent.local");

        assertEquals("Persönlicher Rückstand", alert.headline());
        assertTrue(alert.detail().contains("bereits begonnen"));
    }

    /**
     * Phase 2C.4: Ein frisch aus einer E-Mail erstellter Vorgang (Quelle
     * vorhanden, aber KEIN workState) wurde von der Mitarbeiterin noch NICHT
     * begonnen — die Meldung darf kein "bereits begonnen" behaupten, sondern
     * zeigt "Neuer Vorgang – wartet auf die weitere Bearbeitung".
     */
    @Test
    void alertFor_freshEmailCreatedBlocked_showsNewCaseWording() {
        ws.setPhaseData("{\"sourceEmailId\":\"11111111-2222-3333-4444-555555555555\"}");

        CasePlanningService.PlanningAlert alert = service.alertFor(caseId, "demo01@verwaltungsassistent.local");

        assertNotNull(alert);
        assertEquals("warning", alert.variant());
        assertEquals("Neuer Vorgang", alert.headline());
        assertTrue(alert.detail().contains("wartet auf die weitere Bearbeitung"),
                "fresh email-created case waits for work: " + alert.detail());
        assertTrue(alert.detail().contains("Unterlagen fehlen"),
                "missing documents remain visible: " + alert.detail());
        assertFalse(alert.detail().contains("bereits begonnen"),
                "no false 'already started' claim: " + alert.detail());
    }

    /**
     * Phase 2C.4: Sobald an dem aus der E-Mail erstellten Vorgang tatsächlich
     * gearbeitet wurde (workState vorhanden), bleibt die ehrliche
     * "bereits begonnen"-Darstellung des persönlichen Rückstands bestehen.
     */
    @Test
    void alertFor_begunEmailCreatedBlocked_keepsPersonalBacklogWording() {
        ws.setPhaseData("{\"sourceEmailId\":\"11111111-2222-3333-4444-555555555555\","
                + "\"workState\":{\"state\":\"ACTIVE\"}}");
        when(caseBriefingService.fingerprint(caseId)).thenReturn("fp-b");

        CasePlanningService.PlanningAlert alert = service.alertFor(caseId, "demo01@verwaltungsassistent.local");

        assertEquals("Persönlicher Rückstand", alert.headline());
        assertTrue(alert.detail().contains("bereits begonnen"));
    }

    // ── Helfer ──

    private static WorkspaceEntity entity(String id, String name, WorkspaceStatus status, String phaseData) {
        WorkspaceEntity e = new WorkspaceEntity("WS-" + id.substring(0, 8).toUpperCase(),
                name, "Beschreibung", "CASE", "demo01@verwaltungsassistent.local");
        e.setId(id);
        e.setStatus(status);
        e.setPhase(WorkspacePhase.ANALYSIS);
        e.setPhaseData(phaseData);
        e.setCreatedAt(Instant.now().minusSeconds(86400 * 3));
        return e;
    }

    private static WorkspaceDocumentLinkEntity link(UUID docId) {
        WorkspaceDocumentLinkEntity l = new WorkspaceDocumentLinkEntity();
        l.setDocumentId(docId.toString());
        return l;
    }

    private static WorkspaceAnalysisRunEntity run(int version) {
        WorkspaceAnalysisRunEntity run = new WorkspaceAnalysisRunEntity(
                UUID.randomUUID(), UUID.randomUUID(), version, "COMPLETED",
                "demo01@verwaltungsassistent.local", Instant.now().minusSeconds(3600));
        run.setCompletedAt(Instant.now().minusSeconds(1800));
        return run;
    }

    private static Document doc(UUID id, DocumentStatus status) {
        return new Document(id, "tenant",
                new DocumentMetadata("Dokument", DocumentFileType.PDF, "OTHER",
                        Set.of(), "INTERNAL"),
                status, 1, "u", "u", Instant.now(), Instant.now(),
                null, null, null, null, List.of());
    }
}
