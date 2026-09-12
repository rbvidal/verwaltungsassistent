package verwaltungsassistent.web.mailbox;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.InferenceMetadata;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.ai.model.RetrievalScope;
import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceDossier;
import reasoning.document.api.DocumentFacade;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.JobProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2C.3c: vollständige Kommunikations-Analyse über die BESTEHENDE
 * KI-Pipeline — der E-Mail-Body ist erstklassiger Eingabe-Bestandteil, der
 * Retrieval-Aufruf ist fallbezogen (CURRENT_WORKSPACE), das strukturierte
 * LLM-Ergebnis ersetzt die Klassifikation nur, wenn es lesbar ist (sonst
 * deterministischer Fallback), und Identität/Zuständigkeit bleiben
 * unverändert.
 */
class IncomingCommunicationAnalysisServiceTest {

    private AiFacade aiFacade;
    private WorkspaceService workspaceService;
    private DocumentFacade documentFacade;
    private JpaIncomingEmailRepository emailRepository;
    private JpaEmailAnalysisRepository analysisRepository;
    private JobProgressService progressService;
    private IncomingCommunicationAnalysisService service;

    private final UUID emailId = UUID.randomUUID();
    private final UUID analysisId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private IncomingEmailEntity email;
    private EmailAnalysisEntity analysis;
    private WorkspaceEntity workspace;
    private List<IncomingEmailEntity> previousEmails;

    @BeforeEach
    void setUp() {
        aiFacade = mock(AiFacade.class);
        workspaceService = mock(WorkspaceService.class);
        documentFacade = mock(DocumentFacade.class);
        emailRepository = mock(JpaIncomingEmailRepository.class);
        analysisRepository = mock(JpaEmailAnalysisRepository.class);
        progressService = mock(JobProgressService.class);
        service = new IncomingCommunicationAnalysisService(
                aiFacade, workspaceService, documentFacade, emailRepository,
                analysisRepository, progressService);

        email = new IncomingEmailEntity(emailId,
                "AW: [WS-1A2B3C4D] Wohngeld – Nachfrage zu meinem Antrag",
                "Erika Schulze", "erika.schulze@example.de",
                "Eine Frage habe ich noch: Muss ich die Unterlagen persönlich einreichen?",
                Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, "info@verwaltungs-demo.de");
        email.setWorkspaceId(workspaceId);
        email.setAnalysisId(analysisId);

        analysis = new EmailAnalysisEntity(analysisId, "mailbox", "Text", "Betreff",
                "Wohngeld", null, null, Instant.now());
        analysis.setResultJson("{\"subject\":\"Betreff\",\"topicLabel\":\"Wohngeld\","
                + "\"matchedCases\":[],\"relevantDocuments\":[],\"missingDocuments\":[],\"steps\":[],"
                + "\"intake\":{\"mode\":\"EXISTING_CASE\",\"caseCode\":\"WS-1A2B3C4D\","
                + "\"caseName\":\"Wohngeld – Nachfrage zu meinem Antrag\",\"reviewRequired\":false,"
                + "\"invalidCaseId\":null,\"assignedTo\":null,"
                + "\"classification\":{\"communicationType\":\"FOLLOW_UP\",\"requiresResponse\":true,"
                + "\"requiresAdministrativeWork\":true,\"suggestedAction\":\"Auf dem Vorgang antworten.\","
                + "\"responseMode\":\"ACKNOWLEDGEMENT\"}}}");

        workspace = new WorkspaceEntity("WS-1A2B3C4D", "Wohngeld – Nachfrage zu meinem Antrag",
                "Beschreibung", "CASE", null);
        workspace.setPhaseData("{\"caseCategory\":\"Wohngeld\"}");

        previousEmails = List.of();

        when(emailRepository.findById(emailId)).thenReturn(Optional.of(email));
        when(analysisRepository.findById(analysisId)).thenReturn(Optional.of(analysis));
        when(workspaceService.findById(workspaceId.toString())).thenReturn(Optional.of(workspace));
        when(workspaceService.getWorkspaceDocuments(workspaceId.toString())).thenReturn(List.of());
        when(emailRepository.findByWorkspaceIdOrderByReceivedAtDesc(workspaceId)).thenReturn(previousEmails);
        when(progressService.activeJob(anyString())).thenReturn(null);
        when(progressService.create(any(), any())).thenAnswer(i -> newJob("job-1"));
    }

    /** Job-Konstruktor ist paketprivat — für den Test über Reflektion. */
    private static JobProgressService.Job newJob(String jobId) {
        try {
            var ctor = JobProgressService.Job.class.getDeclaredConstructor(
                    String.class, JobProgressService.Kind.class, String.class, Instant.class);
            ctor.setAccessible(true);
            return ctor.newInstance(jobId, JobProgressService.Kind.ASSISTANT, "Betreff", Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReasonedAnswer answer(String text) {
        return new ReasonedAnswer(text, List.of(
                new SourceCitation(UUID.randomUUID(), UUID.randomUUID(), 1,
                        "Wohngeldgesetz (WoGG)", 2, 10, 60, "§ 2 WoGG – Antragstellung", 0.91,
                        SourceCitation.SourceTier.PRIMARY)),
                List.of(), null, new SourceDossier(java.util.Map.of(), List.of("WoGG § 2"), List.of(), 0.9,
                        "Vollständig"),
                new ConfidenceProfile(0.9, 0.9, 0.9, 0.9, 0.9, "Test"), true, 0.9, false);
    }

    private static AiResponse response(String text) {
        Instant now = Instant.now();
        return new AiResponse(answer(text), new InferenceMetadata("qwen", "hybrid", now, now,
                "test", "test", "test", "HYBRID", List.of(), 0.9));
    }

    /** Direkter (synchroner) Aufruf der Analyse — der Executor-Pfad ist durch die Ingestion-Tests abgedeckt. */
    private void runAnalysis() {
        service.analyze("job-1", emailId.toString(), workspaceId.toString());
    }

    @Test
    void submitAnalysis_registersJobAndIsIdempotent() {
        when(aiFacade.answer(any())).thenReturn(response("Antwort."));
        service.submitAnalysis(email, workspaceId.toString());
        org.mockito.Mockito.verify(progressService).create(
                org.mockito.ArgumentMatchers.eq(JobProgressService.Kind.ASSISTANT), anyString());
        org.mockito.Mockito.verify(progressService).registerActive(
                org.mockito.ArgumentMatchers.eq("email:" + emailId), anyString());
    }

    @Test
    void promptContainsFullEmailAndCaseContext() {
        when(aiFacade.answer(any())).thenReturn(response("Antwort.\n\n{\"communicationType\":\"FOLLOW_UP\"}"));
        runAnalysis();

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade).answer(captor.capture());
        String prompt = captor.getValue().question();
        assertTrue(prompt.contains("Eine Frage habe ich noch: Muss ich die Unterlagen persönlich einreichen?"),
                "der VOLLSTÄNDIGE E-Mail-Body ist Eingabe-Bestandteil");
        assertTrue(prompt.contains("AW: [WS-1A2B3C4D] Wohngeld – Nachfrage zu meinem Antrag"), "Betreff");
        assertTrue(prompt.contains("Erika Schulze") && prompt.contains("erika.schulze@example.de"), "Absender");
        assertTrue(prompt.contains("info@verwaltungs-demo.de"), "Empfänger");
        assertTrue(prompt.contains("Wohngeld – Nachfrage zu meinem Antrag"), "Vorgangskontext (Name)");
        assertTrue(prompt.contains("WS-1A2B3C4D"), "Vorgangsnummer im Kontext");
        assertTrue(prompt.contains("Kategorie: Wohngeld"), "Fall-Kategorie");
    }

    @Test
    void promptListsAttachmentsAndPreviousCommunications() {
        UUID docId = UUID.randomUUID();
        WorkspaceDocumentLinkEntity link = new WorkspaceDocumentLinkEntity(null,
                workspaceId.toString(), docId.toString(), null,
                reasoning.common.model.DocumentCategory.OTHER, "OTHER");
        when(workspaceService.getWorkspaceDocuments(workspaceId.toString())).thenReturn(List.of(link));
        when(documentFacade.getDocument(any(), any())).thenReturn(new reasoning.document.model.Document(
                docId, "default",
                new reasoning.document.model.DocumentMetadata(
                        "Mietvertrag.pdf", reasoning.common.model.DocumentFileType.PDF,
                        "OTHER", java.util.Set.of(), "INTERNAL"),
                reasoning.common.model.DocumentStatus.READY, 1,
                "x", "x", Instant.now(), Instant.now(), List.of()));
        IncomingEmailEntity previous = new IncomingEmailEntity(UUID.randomUUID(),
                "Frage zum Wohngeld", "Erika Schulze", "erika.schulze@example.de",
                "Welche Unterlagen muss ich für meinen Wohngeldantrag einreichen?",
                Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, "info@verwaltungs-demo.de");
        when(emailRepository.findByWorkspaceIdOrderByReceivedAtDesc(workspaceId))
                .thenReturn(List.of(previous));
        when(aiFacade.answer(any())).thenReturn(response("Antwort."));
        runAnalysis();

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade).answer(captor.capture());
        String prompt = captor.getValue().question();
        assertTrue(prompt.contains("FRÜHERE KOMMUNIKATION"), "frühere Kommunikation als Kontext");
        assertTrue(prompt.contains("Frage zum Wohngeld"), "Betreff der früheren Nachricht");
        assertTrue(prompt.contains("Welche Unterlagen muss ich für meinen Wohngeldantrag"),
                "Auszug der früheren Nachricht");
        assertTrue(prompt.contains("ANHÄNGE / DOKUMENTE"), "Anhänge werden als Dokumente gelistet");
    }

    @Test
    void analysisIsCaseScoped_workspaceRetrieval() {
        when(aiFacade.answer(any())).thenReturn(response("Antwort."));
        runAnalysis();

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade).answer(captor.capture());
        AiRequest request = captor.getValue();
        assertEquals(RetrievalScope.CURRENT_WORKSPACE, request.retrievalScope(),
                "fallbezogene Suche — kein globaler Suchraum");
        assertEquals(workspaceId, request.workspaceId());
        assertNotNull(request.retrievalQuery(), "Thema als Suchanker (nicht die Prompt-Hülle)");
    }

    @Test
    void structuredLlmClassificationReplacesDeterministicOne() {
        when(aiFacade.answer(any())).thenReturn(response("Die Unterlagen können persönlich eingereicht werden.\n\n"
                + "{\"communicationType\":\"FOLLOW_UP\",\"requiresResponse\":true,"
                + "\"requiresAdministrativeWork\":true,"
                + "\"suggestedAction\":\"Die Frage zur Einreichung beantworten und den Versandweg bestätigen.\","
                + "\"responseMode\":\"EMPLOYEE_RESPONSE\"}"));
        runAnalysis();

        ArgumentCaptor<EmailAnalysisEntity> captor = ArgumentCaptor.forClass(EmailAnalysisEntity.class);
        verify(analysisRepository).save(captor.capture());
        String json = captor.getValue().getResultJson();
        assertTrue(json.contains("\"communicationType\":\"FOLLOW_UP\""), "LLM-Klassifikation übernommen");
        assertTrue(json.contains("\"responseMode\":\"EMPLOYEE_RESPONSE\""), "LLM-Antwortmodus übernommen");
        assertTrue(json.contains("\"suggestedAction\":\"Die Frage zur Einreichung"), "LLM-Aktion");
        assertTrue(json.contains("\"aiAnswer\":\"Die Unterlagen können persönlich"), "KI-Antwort im Ergebnis");
        assertTrue(json.contains("Wohngeldgesetz (WoGG)"), "Belege aus der bestehenden Pipeline");
        assertTrue(json.contains("\"aiGrounded\":true") || json.contains("\"aiGrounded\": true"), "Grounding");
    }

    @Test
    void unparseableLlmAnswer_fallsBackToDeterministicClassification() {
        when(aiFacade.answer(any())).thenReturn(response("Deterministische E2E-Antwort ohne JSON-Block."));
        runAnalysis();

        ArgumentCaptor<EmailAnalysisEntity> captor = ArgumentCaptor.forClass(EmailAnalysisEntity.class);
        verify(analysisRepository).save(captor.capture());
        String json = captor.getValue().getResultJson();
        // Deterministischer Fallback der 2C.3b-Klassifikation bleibt erhalten.
        assertTrue(json.contains("\"communicationType\":\"FOLLOW_UP\""), "Fallback-Klassifikation");
        assertTrue(json.contains("\"requiresResponse\":true"));
        assertTrue(json.contains("\"aiAnswer\":\"Deterministische E2E-Antwort"), "KI-Antwort trotzdem übernommen");
    }

    @Test
    void caseIdentityAndOwner_areNeverOverriddenByLlm() {
        when(aiFacade.answer(any())).thenReturn(response("{\"communicationType\":\"COMPLAINT\","
                + "\"requiresResponse\":false,\"requiresAdministrativeWork\":false,"
                + "\"suggestedAction\":\"X\",\"responseMode\":\"NONE\"}"));
        runAnalysis();

        ArgumentCaptor<EmailAnalysisEntity> captor = ArgumentCaptor.forClass(EmailAnalysisEntity.class);
        verify(analysisRepository).save(captor.capture());
        String json = captor.getValue().getResultJson();
        assertTrue(json.contains("\"mode\":\"EXISTING_CASE\""), "Fall-Identität bleibt deterministisch");
        assertTrue(json.contains("\"caseCode\":\"WS-1A2B3C4D\""), "Vorgangsnummer bleibt");
        assertTrue(json.contains("\"assignedTo\":null"), "Zuständigkeit bleibt");
        assertTrue(json.contains("\"invalidCaseId\":null"));
    }

    @Test
    void llmFailure_keepsTriageAndDoesNotPropagate() {
        when(aiFacade.answer(any())).thenThrow(new RuntimeException("Ollama nicht erreichbar"));
        String before = analysis.getResultJson();

        runAnalysis(); // darf nicht werfen

        assertEquals(before, analysis.getResultJson(),
                "Analyse-Fehler lässt die Vor-Analyse unverändert (E-Mail/Vorgang bleiben erhalten)");
        org.mockito.Mockito.verify(progressService, org.mockito.Mockito.atLeastOnce())
                .recordStage(anyString(), anyString());
    }

    // ── Phase 2D.11: Job-Lebenszyklus (Terminal-Zustand + Unregister) ──

    /**
     * Der asynchrone Mailbox-Analyse-Job muss einen Terminal-Zustand erreichen
     * und seinen aktiven Schlüssel freigeben — sonst bliebe die E-Mail-Ansicht
     * dauerhaft gesperrt und ein Abwarten (Overnight-Prozessor) wäre unmöglich.
     * ECHTES JobProgressService: create/registerActive/complete/unregister sind
     * die zu prüfende Semantik.
     */
    @Test
    void analysisJob_reachesTerminalState_andActiveJobBecomesNull() throws Exception {
        JobProgressService realProgress = new JobProgressService();
        IncomingCommunicationAnalysisService liveService = new IncomingCommunicationAnalysisService(
                aiFacade, workspaceService, documentFacade, emailRepository,
                analysisRepository, realProgress);
        when(aiFacade.answer(any())).thenReturn(response(
                "Die Unterlagen können Sie persönlich im Bürgeramt einreichen.\n\n"
                        + "{\"communicationType\":\"FOLLOW_UP\",\"requiresResponse\":true,"
                        + "\"requiresAdministrativeWork\":false,"
                        + "\"suggestedAction\":\"Frage zur Einreichung beantworten.\","
                        + "\"responseMode\":\"EMPLOYEE_RESPONSE\"}"));

        liveService.submitAnalysis(email, workspaceId.toString());

        String key = "email:" + emailId;
        JobProgressService.Job job = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            job = realProgress.activeJob(key);
            if (job != null && !"RUNNING".equals(job.state)) {
                break;
            }
            Thread.sleep(25);
        }
        if (job == null) {
            // Der Worker kann schneller sein als der erste Poll — dann ist der
            // Schlüssel bereits freigegeben; der Erfolg zeigt sich am Ergebnis.
            ArgumentCaptor<EmailAnalysisEntity> fast = ArgumentCaptor.forClass(EmailAnalysisEntity.class);
            verify(analysisRepository).save(fast.capture());
            assertTrue(fast.getValue().getResultJson().contains("im Bürgeramt einreichen"),
                    "Worker war schneller als der Poll — Ergebnis wurde persistiert");
            return;
        }
        assertEquals("DONE", job.state,
                "Erfolgspfad erreicht den Terminal-Zustand (complete) — kein dauerhaft RUNNING");
        assertTrue(realProgress.activeJob(key) == null,
                "Nach dem Terminal-Zustand liefert activeJob() null (Unregister)");

        ArgumentCaptor<EmailAnalysisEntity> captor = ArgumentCaptor.forClass(EmailAnalysisEntity.class);
        verify(analysisRepository).save(captor.capture());
        assertTrue(captor.getValue().getResultJson().contains("im Bürgeramt einreichen"),
                "KI-Antwort wird über den bestehenden Pfad persistiert");
    }

    /** Fehlerpfad: Job endet im ERROR-Zustand und gibt den Schlüssel ebenfalls frei. */
    @Test
    void analysisJob_failure_reachesErrorState_andActiveJobBecomesNull() throws Exception {
        JobProgressService realProgress = new JobProgressService();
        IncomingCommunicationAnalysisService liveService = new IncomingCommunicationAnalysisService(
                aiFacade, workspaceService, documentFacade, emailRepository,
                analysisRepository, realProgress);
        when(aiFacade.answer(any())).thenThrow(new RuntimeException("Ollama nicht erreichbar"));
        String before = analysis.getResultJson();

        liveService.submitAnalysis(email, workspaceId.toString());

        String key = "email:" + emailId;
        JobProgressService.Job job = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            job = realProgress.activeJob(key);
            if (job != null && !"RUNNING".equals(job.state)) {
                break;
            }
            Thread.sleep(25);
        }
        if (job != null) {
            assertEquals("ERROR", job.state, "Fehlerpfad endet im ERROR-Zustand (fail)");
            assertTrue(realProgress.activeJob(key) == null,
                    "auch der Fehlerpfad gibt den aktiven Schlüssel frei");
        }
        // Toleranz: war der Worker schneller als der Poll, ist der Schlüssel
        // bereits frei — entscheidend ist: kein Erfolgs-Ergebnis, kein Absturz.
        assertEquals(before, analysis.getResultJson(),
                "Analyse-Fehler lässt die Vor-Analyse unverändert");
        assertTrue(realProgress.activeJob(key) == null,
                "der aktive Schlüssel ist nach dem Fehlerpfad frei");
    }
}
