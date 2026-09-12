package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.WorkspacePhase;
import reasoning.document.api.DocumentFacade;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.security.CaseAccessGuard;
import verwaltungsassistent.web.service.AnalysisResultSanitizer;
import verwaltungsassistent.web.service.DecisionPdfExporter;
import verwaltungsassistent.web.service.JobProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused test for the single "Analyse starten" action from the Ingestion
 * phase: with advance=true the analyse start BOTH advances the phase
 * (Ingestion → Analyse) AND starts the pipeline (progress panel + redirect to
 * the decision workspace). Without the parameter no phase advance happens.
 */
class DecisionWorkspaceControllerTest {

    private WorkspaceService workspaceService;
    private CaseAccessGuard caseAccessGuard;
    private verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;
    private DecisionWorkspaceController controller;

    private final AuthenticatedUser admin = new AuthenticatedUser(
            UUID.randomUUID(), "admin@verwaltungsassistent.local", "Admin", Set.of("ADMIN"));
    private final String caseId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        caseAccessGuard = mock(CaseAccessGuard.class);
        incomingEmailRepository = mock(verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository.class);
        controller = new DecisionWorkspaceController(
                workspaceService,
                mock(DocumentFacade.class),
                new AnalysisResultSanitizer(),
                incomingEmailRepository,
                mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class),
                mock(verwaltungsassistent.web.planning.CaseWorkStateService.class),
                mock(verwaltungsassistent.web.service.CaseBriefingService.class),
                mock(AiFacade.class),
                null, null, null, null, null, null, null,
                new JobProgressService(),
                mock(SpringTemplateEngine.class),
                mock(DecisionPdfExporter.class),
                caseAccessGuard,
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class),
                "",
                "uploads");
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung für ein Carport.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.INGESTION);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.advanceBlockReason(caseId)).thenReturn(null);
        when(workspaceService.advancePhase(caseId)).thenAnswer(inv -> {
            entity.setPhase(WorkspacePhase.ANALYSIS);
            return entity;
        });
    }

    @Test
    void analyseStart_withAdvance_advancesPhaseAndStartsPipeline() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        String view = controller.startAnalysis(caseId, admin, null, null, null, "true", "true", response, null, model);

        verify(workspaceService).advancePhase(caseId);
        assertEquals("/cases/" + caseId + "/decision", response.getHeader("HX-Redirect"),
                "the browser is led to the decision workspace showing the progress");
        assertEquals("fragments/progress :: progressPanel", view);
        assertNotNull(model.getAttribute("pollUrl"));
    }

    @Test
    void analyseStart_withoutAdvance_doesNotChangePhase() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        controller.startAnalysis(caseId, admin, null, null, null, "true", null, response, null, model);

        verify(workspaceService, never()).advancePhase(any());
        assertEquals("/cases/" + caseId + "/decision", response.getHeader("HX-Redirect"));
    }

    /**
     * Phase 2D.7: Analyse-Start auf einem ABGESCHLOSSENEN Vorgang wird
     * serverseitig abgewiesen (Endzustand — Arbeitsaufnahme/neuer Lauf erst
     * nach der expliziten Wiederaufnahme).
     */
    @Test
    void analyseStart_onClosedCase_isRejected() {
        WorkspaceEntity closed = new WorkspaceEntity(
                "WS-CLOSED", "Baugenehmigung Carport", "Antrag auf Baugenehmigung.",
                "CASE", admin.email());
        closed.setPhase(reasoning.common.model.WorkspacePhase.INGESTION);
        closed.setStatus(reasoning.common.model.WorkspaceStatus.CLOSED);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(closed));

        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.startAnalysis(caseId, admin, null, null, null,
                        "true", "true", response, null, model));
    }

    @Test
    void analyseStart_withAdvanceButBlockedGate_doesNotAdvance() {
        when(workspaceService.advanceBlockReason(caseId)).thenReturn("Keine Dokumente zugeordnet.");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        controller.startAnalysis(caseId, admin, null, null, null, "true", "true", response, null, model);

        verify(workspaceService, never()).advancePhase(any());
        assertEquals("/cases/" + caseId + "/decision", response.getHeader("HX-Redirect"));
    }

    /**
     * Der Export-Endpunkt (Produktions-Pfad der PDF-Erzeugung) verweigert die
     * PDF ohne nutzbares abgeschlossenes Analyse-Ergebnis mit 404 — es gibt
     * keinen Weg, für einen unanalysierten Fall eine "Entscheidungsvorlage"
     * zu erzeugen.
     */
    @Test
    void exportPdf_withoutUsableAnalysis_returnsNotFound() {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung für ein Carport.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.SETUP);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.SETUP, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());

        try {
            controller.exportDecisionPdf(caseId, null, null, admin);
            assertTrue(false, "expected ResponseStatusException 404");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assertEquals(404, e.getStatusCode().value());
        }
    }

    /**
     * Issue 4: Der Kopf der Entscheidungs-Seite zeigt die Anzahl der tatsächlich
     * zugeordneten E-Mails (workspace_id) — 0, 1 und mehrere.
     */
    @Test
    void decisionPage_emailCount_reflectsWorkspaceAssociation() {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Verkehrsschild beschädigt", "Beschädigtes Verkehrsschild am Bahnhof.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.ANALYSIS);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Verkehrsschild beschädigt", "Beschädigtes Verkehrsschild am Bahnhof.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.ANALYSIS, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());

        // 0 E-Mails
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of());
        Model model0 = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, null, model0);
        assertEquals(0, model0.getAttribute("caseEmailCount"));

        // 1 E-Mail
        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity email =
                new verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity(
                        UUID.randomUUID(), "Verkehrsschild beschädigt", "Klaus Neumann",
                        "klaus.neumann@example.de", "Betreff: Verkehrsschild beschädigt",
                        java.time.Instant.now(),
                        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo.GENERAL,
                        "kontakt@verwaltungs-demo.de");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(email));
        Model model1 = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, null, model1);
        assertEquals(1, model1.getAttribute("caseEmailCount"));

        // mehrere E-Mails
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(email, email, email));
        Model model3 = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, null, model3);
        assertEquals(3, model3.getAttribute("caseEmailCount"));
    }

    /**
     * Issue 2: "PDF öffnen" (inline=true) liefert die PDF mit Content-Disposition
     * inline (Browser-Anzeige), ohne inline=true bleibt es beim Download
     * (attachment). Der Browsereffekt selbst ist ohne Browser nicht prüfbar.
     */
    @Test
    void exportPdf_inlineParameter_switchesDisposition() throws Exception {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung für ein Carport.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.ANALYSIS);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.ANALYSIS, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                new reasoning.workspace.api.WorkspaceAnalysisRunEntity(
                        UUID.randomUUID(), UUID.fromString(caseId), 1, "COMPLETED",
                        admin.email(), java.time.Instant.now());
        run.setCompletedAt(java.time.Instant.now());
        run.setResultJson("{}");
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(new java.util.LinkedHashMap<>());
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of());
        verwaltungsassistent.web.service.DecisionPdfExporter pdfExporter =
                mock(verwaltungsassistent.web.service.DecisionPdfExporter.class);
        when(pdfExporter.export(any())).thenReturn(new byte[] { 1, 2, 3 });
        controller = new DecisionWorkspaceController(
                workspaceService, mock(DocumentFacade.class), new AnalysisResultSanitizer(),
                incomingEmailRepository,
                mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class),
                mock(verwaltungsassistent.web.planning.CaseWorkStateService.class),
                mock(verwaltungsassistent.web.service.CaseBriefingService.class),
                mock(AiFacade.class),
                null, null, null, null, null, null, null,
                new JobProgressService(), mock(SpringTemplateEngine.class), pdfExporter,
                caseAccessGuard,
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class), "",
                "uploads");

        var inlineResponse = controller.exportDecisionPdf(caseId, null, "true", admin);
        assertTrue(inlineResponse.getHeaders().getFirst("Content-Disposition").startsWith("inline"),
                "inline=true must serve the PDF for browser display");

        var downloadResponse = controller.exportDecisionPdf(caseId, null, null, admin);
        assertTrue(downloadResponse.getHeaders().getFirst("Content-Disposition").startsWith("attachment"),
                "without inline the PDF is a download");
    }

    /**
     * Issue 2: Ohne Fall-Analyse zeigt die Entscheidungs-Seite die vorläufige
     * E-Mail-Analyse (Triage) des auslösenden E-Mails als Kontext — die bereits
     * geleistete Analyse wird wiederverwendet statt verworfen.
     */
    @Test
    void decisionPage_showsPreliminaryEmailAnalysis_whenCaseCameFromEmail() {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Verkehrsschild beschädigt", "Beschädigtes Verkehrsschild am Bahnhof.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.SETUP);
        UUID analysisId = UUID.randomUUID();
        entity.setPhaseData("{\"sourceEmailId\":\"" + analysisId + "\"}");
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Verkehrsschild beschädigt", "Beschädigtes Verkehrsschild am Bahnhof.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.SETUP, admin.email(),
                Map.of("sourceEmailId", analysisId.toString()), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());
        verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository analysisRepo =
                mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class);
        verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity stored =
                new verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity(
                        analysisId, admin.email(), "Betreff: Verkehrsschild beschädigt",
                        "Verkehrsschild beschädigt", "Allgemeines Anliegen", "test",
                        "{\"aiAnswer\":\"Die Reparatur ist zu veranlassen.\",\"aiGrounded\":false,"
                                + "\"aiConfidence\":52,\"aiEvidence\":[]}",
                        java.time.Instant.now());
        when(analysisRepo.findById(analysisId)).thenReturn(Optional.of(stored));
        controller = new DecisionWorkspaceController(
                workspaceService, mock(DocumentFacade.class), new AnalysisResultSanitizer(),
                incomingEmailRepository, analysisRepo,
                mock(verwaltungsassistent.web.planning.CaseWorkStateService.class),
                mock(verwaltungsassistent.web.service.CaseBriefingService.class), mock(AiFacade.class),
                null, null, null, null, null, null, null,
                new JobProgressService(), mock(SpringTemplateEngine.class),
                mock(DecisionPdfExporter.class), caseAccessGuard,
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class), "",
                "uploads");

        Model model = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, null, model);

        Object preliminary = model.getAttribute("preliminaryEmailAnalysis");
        assertTrue(preliminary instanceof DecisionWorkspaceController.PreliminaryEmailAnalysis p
                        && p.aiAnswer().contains("Reparatur"),
                "the preliminary email analysis is exposed as case context");
    }

    @Test
    void decisionPage_withoutEmailSource_showsNoPreliminaryAnalysis() {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung für ein Carport.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.SETUP);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.SETUP, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.empty());

        Model model = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, null, model);

        assertEquals(null, model.getAttribute("preliminaryEmailAnalysis"));
    }

    /**
     * Issue 6: Der Export baut E-Mail-Referenzen ("E-Mail: <Betreff>") in das
     * PDF-Modell — die Entscheidungsvorlage dokumentiert die zugeordnete
     * Bürgerkommunikation im Abschnitt DOKUMENTE IM VORGANG.
     */
    @Test
    void exportPdf_includesEmailReferencesInModel() throws Exception {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Verkehrsschild beschädigt", "Beschädigtes Verkehrsschild am Bahnhof.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.ANALYSIS);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Verkehrsschild beschädigt", "Beschädigtes Verkehrsschild am Bahnhof.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.ANALYSIS, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                new reasoning.workspace.api.WorkspaceAnalysisRunEntity(
                        UUID.randomUUID(), UUID.fromString(caseId), 1, "COMPLETED",
                        admin.email(), java.time.Instant.now());
        run.setCompletedAt(java.time.Instant.now());
        run.setResultJson("{}");
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(new java.util.LinkedHashMap<>());
        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity email =
                new verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity(
                        UUID.randomUUID(), "Verkehrsschild beschädigt", "Klaus Neumann",
                        "klaus.neumann@example.de", "Betreff: Verkehrsschild beschädigt",
                        java.time.Instant.now(),
                        verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo.GENERAL,
                        "kontakt@verwaltungs-demo.de");
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of(email));
        verwaltungsassistent.web.service.DecisionPdfExporter pdfExporter =
                mock(verwaltungsassistent.web.service.DecisionPdfExporter.class);
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> modelCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        when(pdfExporter.export(modelCaptor.capture())).thenReturn(new byte[] { 1, 2, 3 });
        controller = new DecisionWorkspaceController(
                workspaceService, mock(DocumentFacade.class), new AnalysisResultSanitizer(),
                incomingEmailRepository,
                mock(verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository.class),
                mock(verwaltungsassistent.web.planning.CaseWorkStateService.class),
                mock(verwaltungsassistent.web.service.CaseBriefingService.class),
                mock(AiFacade.class),
                null, null, null, null, null, null, null,
                new JobProgressService(), mock(SpringTemplateEngine.class), pdfExporter,
                caseAccessGuard,
                mock(reasoning.auth.infrastructure.persistence.UserAccountRepository.class), "",
                "uploads");

        controller.exportDecisionPdf(caseId, null, null, admin);

        java.util.Map<String, Object> captured = modelCaptor.getValue();
        Object refs = captured.get("emailReferences");
        assertTrue(refs instanceof List<?> list && list.size() == 1,
                "the export model carries the associated email reference");
        assertTrue(((List<?>) refs).get(0).toString().contains("E-Mail: Verkehrsschild beschädigt"));
    }

    /**
     * Issue 1 (Dokument-Gruppierung): mehrere Beleg-Abschnitte DESSELBEN
     * Dokuments bilden EINE Gruppe (eine Überschrift, alle Abschnitte
     * einzeln erhalten, Reihenfolge unverändert); verschiedene Dokumente
     * bleiben getrennte Gruppen. Reine Präsentation — keine Umordnung.
     */
    @Test
    void groupEvidence_groupsChunksByDocument_preservingOrder() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        List<Map<String, Object>> items = List.of(
                evidence(docA, "info_gewerbe_anmelden.pdf", 1.0),
                evidence(docB, "andere.pdf", 0.9),
                evidence(docA, "info_gewerbe_anmelden.pdf", 0.87),
                evidence(docA, "info_gewerbe_anmelden.pdf", 0.87));

        List<Map<String, Object>> groups = DecisionWorkspaceController.groupEvidence(items);

        assertEquals(2, groups.size(), "one group per document");
        assertEquals(docA.toString(), groups.get(0).get("documentId"));
        assertEquals(docB.toString(), groups.get(1).get("documentId"));
        assertEquals("info_gewerbe_anmelden.pdf", groups.get(0).get("title"));
        assertEquals(3, ((List<?>) groups.get(0).get("items")).size(),
                "all three passages of document A stay individually visible");
        assertEquals(1, ((List<?>) groups.get(1).get("items")).size());
        // Reihung innerhalb der Gruppe = Reihung des Retrievals (1.0, 0.87, 0.87)
        assertEquals(1.0, ((List<?>) groups.get(0).get("items")).get(0) instanceof Map<?, ?> m0
                ? m0.get("confidenceRaw") : null);
        assertEquals(0.87, ((List<?>) groups.get(0).get("items")).get(1) instanceof Map<?, ?> m1
                ? m1.get("confidenceRaw") : null);
    }

    @Test
    void groupEvidence_ignoresNonMapEntries_andEmptyInput() {
        assertTrue(DecisionWorkspaceController.groupEvidence(null).isEmpty());
        assertTrue(DecisionWorkspaceController.groupEvidence(List.of("kein-map")).isEmpty());
    }

    private static Map<String, Object> evidence(UUID documentId, String title, double confidence) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("documentId", documentId.toString());
        item.put("title", title);
        item.put("confidenceRaw", confidence);
        item.put("tier", "Primär");
        return item;
    }

    /**
     * Issue 2 (frischer Abschluss, verspätete Ankunft): Wurde der Analyse-Lauf
     * in DIESER Sitzung gestartet und ist kurz zuvor abgeschlossen, öffnet die
     * Seite die fertige PDF genau EINMAL automatisch. Bei späteren Besuchen
     * ist das Sitzungs-Flag verbraucht — kein zweiter Tab (Pass-8-Verhalten
     * bleibt erhalten).
     */
    @Test
    void decisionPage_sessionStartedRun_autoOpensPdfOnce() {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung für ein Carport.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.ANALYSIS);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.ANALYSIS, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                new reasoning.workspace.api.WorkspaceAnalysisRunEntity(
                        UUID.randomUUID(), UUID.fromString(caseId), 7, "COMPLETED",
                        admin.email(), java.time.Instant.now().minusSeconds(120));
        run.setCompletedAt(java.time.Instant.now().minusSeconds(30));
        run.setResultJson("{\"jobId\":\"job-x\"}");
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("decisionAnswer", "KURZANTWORT\nErgebnis");
        result.put("jobId", "job-x");
        result.put("evidenceItems", List.of());
        result.put("confidence", Map.of(
                "sourceConfidence", 0.7, "semanticConfidence", 0.6,
                "structuralConfidence", 0.5, "completenessConfidence", 0.8,
                "overallConfidence", 0.65, "explanation", "geprüft"));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(result);
        when(incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)))
                .thenReturn(List.of());

        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("verwaltungsassistent.decisionAutoOpenRun",
                Map.of("version", 7, "startedAt", java.time.Instant.now()));

        Model model = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, session, model);
        assertEquals(Boolean.TRUE, model.getAttribute("autoOpenPdf"),
                "the run started in this session auto-opens the PDF once");
        assertEquals(null, session.getAttribute("verwaltungsassistent.decisionAutoOpenRun"),
                "the one-shot flag is consumed");

        // Zweiter Besuch: kein erneutes automatisches Öffnen.
        Model model2 = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, session, model2);
        assertEquals(null, model2.getAttribute("autoOpenPdf"),
                "a later visit must never auto-open the PDF again");
    }

    /**
     * Die Entscheidungs-Seite zeigt den Zustand "Analyse vorhanden" (und damit
     * den "PDF herunterladen"-Button) NUR, wenn der abgeschlossene Lauf ein
     * abrufbares Ergebnis trägt. Ein COMPLETED-Lauf ohne Ergebnis fällt in den
     * ehrlichen "nicht verfügbar"-Zustand — kein toter "Zum Fall"-Pfad.
     */
    @Test
    void decisionPage_completedRunWithoutResult_isNotShownAsUsableAnalysis() {
        WorkspaceEntity entity = new WorkspaceEntity(
                "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung für ein Carport.",
                "CASE", admin.email());
        entity.setPhase(WorkspacePhase.ANALYSIS);
        when(caseAccessGuard.requireAccess(caseId, admin)).thenReturn(entity);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(workspaceService.toDto(any())).thenReturn(new reasoning.workspace.api.WorkspaceDto(
                caseId, "WS-TEST", "Baugenehmigung Carport", "Antrag auf Baugenehmigung.",
                "CASE", reasoning.common.model.WorkspaceStatus.ACTIVE,
                WorkspacePhase.ANALYSIS, admin.email(), Map.of(), List.of(), List.of(),
                java.time.Instant.now(), java.time.Instant.now()));
        reasoning.workspace.api.WorkspaceAnalysisRunEntity run =
                new reasoning.workspace.api.WorkspaceAnalysisRunEntity(
                        UUID.randomUUID(), UUID.fromString(caseId), 1, "COMPLETED",
                        admin.email(), java.time.Instant.now());
        run.setCompletedAt(java.time.Instant.now());
        run.setResultJson(null); // abgeschlossen, aber ohne abrufbares Ergebnis
        when(workspaceService.latestCompletedAnalysisRun(caseId)).thenReturn(Optional.of(run));
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(null);

        Model model = new ExtendedModelMap();
        controller.decisionWorkspace(caseId, admin, null, model);

        assertEquals(null, model.getAttribute("latestAnalysis"),
                "a run without a usable result must NOT expose the completed-analysis state");
        assertEquals(null, model.getAttribute("decisionAnswer"),
                "no result content is exposed for an unusable run");
    }
}
