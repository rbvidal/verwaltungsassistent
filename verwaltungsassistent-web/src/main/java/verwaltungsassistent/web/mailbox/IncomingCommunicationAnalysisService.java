package verwaltungsassistent.web.mailbox;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiConversationContext;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.ai.model.RetrievalScope;
import reasoning.ai.model.SourceCitation;
import reasoning.document.api.DocumentFacade;
import reasoning.document.model.Document;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.controller.EmailController;
import verwaltungsassistent.web.service.JobProgressService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2C.3c — vollständige Analyse der eingehenden Kommunikation: Der
 * Vorgangs-Intake (2C.3b) hat E-Mail + Anhänge + Kontext zusammengeführt; diese
 * Analyse orchestriert die BESTEHENDE KI-/Retrieval-/Beleg-Infrastruktur über
 * das Kommunikationspaket — KEINE zweite RAG-Pipeline.
 *
 * <p>Eingabe des LLM: Betreff, Absender, Empfänger, der VOLLSTÄNDIGE E-Mail-
 * Body, die Anhänge (als normale Vorgangs-Dokumente), der Vorgangskontext
 * (Name/Kategorie/Beschreibung) und — bei Folgenachrichten — kompakte frühere
 * Kommunikationen. Der Retrieval-Aufruf läuft {@link RetrievalScope#CURRENT_WORKSPACE}-basiert:
 * die Anhänge/Dokumente des Vorgangs sind der Suchraum, die bestehenden
 * Grounding-/Beleg-/Verifikations-Mechanismen bleiben unverändert.</p>
 *
 * <p>Autoritätsgrenzen: Das LLM interpretiert die Kommunikation, aber NIE die
 * Fall-Identität, Vorgangsnummer oder Zuständigkeit — die bleiben
 * deterministisch (2C.3b). Übernommen werden NUR die strukturierten
 * Klassifikationsfelder; scheitert der LLM-Pfad (kein JSON, Fehler, kein
 * Dienst), bleibt die deterministische Klassifikation als ehrlicher Fallback
 * erhalten. Der Intake (E-Mail, Vorgang, Anhänge, Timeline, Routing) wird
 * durch eine fehlgeschlagene Analyse NIE zurückgerollt.</p>
 */
@Service
@Profile({"demo", "playwright"})
public class IncomingCommunicationAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(IncomingCommunicationAnalysisService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{(?:[^{}]|\\{[^{}]*\\})*\\}",
            Pattern.DOTALL);

    private final AiFacade aiFacade;
    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;
    private final JpaIncomingEmailRepository emailRepository;
    private final JpaEmailAnalysisRepository analysisRepository;
    private final JobProgressService progressService;
    private final ExecutorService executor;

    public IncomingCommunicationAnalysisService(AiFacade aiFacade,
                                                WorkspaceService workspaceService,
                                                DocumentFacade documentFacade,
                                                JpaIncomingEmailRepository emailRepository,
                                                JpaEmailAnalysisRepository analysisRepository,
                                                JobProgressService progressService) {
        this.aiFacade = aiFacade;
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.emailRepository = emailRepository;
        this.analysisRepository = analysisRepository;
        this.progressService = progressService;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mailbox-analysis-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Startet die vollständige Kommunikations-Analyse asynchron über den
     * BESTEHENDEN Job-Mechanismus (Key {@code email:<id>} → die E-Mail-Ansicht
     * zeigt „Analyse läuft"). Nur für Nachrichten mit zugeordnetem Vorgang —
     * ohne Vorgang (Prüfung erforderlich) gibt es keine Fall-Kontext-Analyse.
     * Idempotent: solange ein aktiver Job läuft, wird kein zweiter gestartet.
     */
    public void submitAnalysis(IncomingEmailEntity email, String workspaceId) {
        if (email == null || workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        try {
            String jobKey = "email:" + email.getId();
            JobProgressService.Job running = progressService.activeJob(jobKey);
            if (running != null) {
                return;
            }
            JobProgressService.Job job = progressService.create(
                    JobProgressService.Kind.ASSISTANT, email.getSubject());
            progressService.registerActive(jobKey, job.jobId);
            executor.submit(() -> analyze(job.jobId, email.getId().toString(), workspaceId));
        } catch (Exception e) {
            // Fehler beim Job-Start dürfen den (bereits abgeschlossenen)
            // Intake nie gefährden — die Analyse kann später erneut laufen.
            log.warn("Analyse-Start für E-Mail '{}' fehlgeschlagen: {}", email.getSubject(), e.getMessage());
        }
    }

    /** Vollständige Analyse des Kommunikationspakets (Worker-Thread, eigene Transaktion). */
    void analyze(String jobId, String emailId, String workspaceId) {
        try {
            IncomingEmailEntity email = findEmailCommitted(emailId);
            if (email == null) {
                log.warn("Kommunikations-Analyse: E-Mail {} nicht (mehr) vorhanden", emailId);
                return;
            }
            WorkspaceEntity ws = workspaceService.findById(workspaceId).orElse(null);
            String subject = email.getSubject() != null ? email.getSubject() : "";
            String body = email.getText() != null ? email.getText() : "";
            String prompt = buildPrompt(email, ws, workspaceId, subject, body);
            String topic = EmailController.detectTopic(subject + "\n" + body);
            String retrievalQuery = topic + " " + firstWords(body, 60);

            recordStage(jobId, "Das Kommunikationspaket wird analysiert …");
            AiRequest request = new AiRequest(
                    prompt, null, null,
                    new AiConversationContext(List.of(), "mailbox", null, null, jobId), 8,
                    RetrievalScope.CURRENT_WORKSPACE, UUID.fromString(workspaceId), null, retrievalQuery);
            AiResponse response = aiFacade.answer(request);
            ReasonedAnswer answer = response.answer();

            CommunicationClassifier.CommunicationClassification llmClassification =
                    parseClassification(answer.answer(), subject, body);
            List<EmailController.AiEvidence> evidence = evidenceOf(answer);
            int confidence = answer.confidence() != null
                    ? (int) Math.round(answer.confidence().overallConfidence() * 100) : null;

            updateOutcome(email, llmClassification, answer.answer(), answer.grounded(), confidence, evidence);
            recordStage(jobId, "Kommunikation vollständig analysiert (Belege: " + evidence.size() + ")");
            log.info("Kommunikations-Analyse für E-Mail {} abgeschlossen ({} Belege)", emailId, evidence.size());
            // Terminal-Zustand des Jobs (Phase 2D.11): Ohne complete()/fail()
            // bliebe der Job dauerhaft RUNNING — die E-Mail-Ansicht würde die
            // Analyse-Aktion dauerhaft als "läuft" sperren und ein Aufrufer
            // (z. B. der Overnight-Prozessor) könnte die Fertigstellung nicht
            // erkennen. Gleiches Muster wie EmailController.process.
            progressService.complete(jobId, null, "Die Kommunikations-Analyse wurde abgeschlossen.");
        } catch (Exception e) {
            log.error("Kommunikations-Analyse für E-Mail {} fehlgeschlagen (E-Mail/Vorgang bleiben erhalten): {}",
                    emailId, e.getMessage(), e);
            try {
                recordStage(jobId, "Analyse fehlgeschlagen — die Vor-Analyse bleibt erhalten.");
            } catch (Exception ignored) {
            }
            progressService.fail(jobId);
        } finally {
            progressService.unregisterActive("email:" + emailId, jobId);
        }
    }

    /**
     * Baut den Analyse-Prompt: die E-Mail ist ERSTKLASSIGER Eingabe-Bestandteil
     * (Betreff, Absender, Empfänger, vollständiger Text), ergänzt um die
     * Anhänge (normale Vorgangs-Dokumente), den Vorgangskontext und — bei
     * bestehenden Vorgängen — kompakte frühere Kommunikationen. Kein
     * Historie-Dump: höchstens drei vorherige Nachrichten, jeweils gekürzt.
     */
    private String buildPrompt(IncomingEmailEntity email, WorkspaceEntity ws, String workspaceId,
                               String subject, String body) {
        StringBuilder p = new StringBuilder();
        p.append("Du analysierst eine eingehende Bürger-Nachricht im kommunalen Verwaltungsassistenten ")
                .append("als Teil eines konkreten Vorgangs.\n\n");
        p.append("WICHTIGE GRENZE: Die Vorgangsnummer und die Zuständigkeit sind bereits ")
                .append("deterministisch bestimmt und werden von dir NICHT verändert. Du interpretierst ")
                .append("nur die Kommunikation selbst.\n\n");
        p.append(reasoning.ai.model.PromptBoundary.UNTRUSTED_CONTENT_MARKER).append("\n\n");
        p.append("EINGEHENDE E-MAIL\n");
        p.append("Betreff: ").append(subject).append("\n");
        if (email.getSenderName() != null && !email.getSenderName().isBlank()) {
            p.append("Absender: ").append(email.getSenderName());
        }
        if (email.getSenderEmail() != null && !email.getSenderEmail().isBlank()) {
            p.append(email.getSenderName() != null && !email.getSenderName().isBlank() ? " <" : "Absender: <")
                    .append(email.getSenderEmail()).append(">");
        }
        p.append("\n");
        p.append("Empfänger: ").append(email.getAddressedToEmail() != null && !email.getAddressedToEmail().isBlank()
                ? email.getAddressedToEmail() : "Allgemeine Mailbox (Bürgeramt)").append("\n");
        p.append("Text der Nachricht (VOLLSTÄNDIG):\n").append(body.isBlank() ? "(kein Text)" : body).append("\n\n");

        if (ws != null) {
            p.append("VORGANG\n");
            p.append("Name: ").append(ws.getName() != null ? ws.getName() : "—").append("\n");
            p.append("Vorgangsnummer: ").append(ws.getWorkspaceCode() != null ? ws.getWorkspaceCode() : "—").append("\n");
            p.append("Kategorie: ").append(caseCategory(ws)).append("\n");
            if (ws.getDescription() != null && !ws.getDescription().isBlank()) {
                p.append("Beschreibung: ").append(ws.getDescription()).append("\n");
            }
            p.append("\n");
        }

        List<String> attachments = attachedDocumentTitles(workspaceId);
        if (!attachments.isEmpty()) {
            p.append("ANHÄNGE / DOKUMENTE DES VORGANGS (bereits als Dokumente übernommen)\n");
            for (String title : attachments) {
                p.append("- ").append(title).append("\n");
            }
            p.append("\n");
        }

        List<String> previous = previousCommunications(email, workspaceId);
        if (!previous.isEmpty()) {
            p.append("FRÜHERE KOMMUNIKATION ZU DIESEM VORGANG (Kontext)\n");
            for (String line : previous) {
                p.append("- ").append(line).append("\n");
            }
            p.append("\n");
        }

        p.append("AUFGABE\n");
        p.append("Analysiere die Kommunikation im Kontext des Vorgangs und der für die Suche ")
                .append("geladenen autoritativen Quellen. Beantworte die Frage der Bürgerin bzw. des ")
                .append("Bürgers belegt; nenne ehrlich, was nicht belegbar ist.\n");
        p.append("Ergänze deine Antwort am Ende um einen JSON-Block mit GENAU diesen Feldern:\n");
        p.append("{\n");
        p.append("  \"communicationType\": \"APPLICATION|QUESTION|INFORMATION_REQUEST|FOLLOW_UP|CLARIFICATION|COMPLAINT|OTHER\",\n");
        p.append("  \"requiresResponse\": true|false,\n");
        p.append("  \"requiresAdministrativeWork\": true|false,\n");
        p.append("  \"suggestedAction\": \"konkrete nächste Aktion\",\n");
        p.append("  \"responseMode\": \"ACKNOWLEDGEMENT|EMPLOYEE_RESPONSE|NONE\"\n");
        p.append("}\n");
        p.append("Beispiele: „Danke für die Information, das war schon alles.").append("\" → requiresResponse=false, ")
                .append("responseMode=NONE. „Eine Frage habe ich noch: …").append("\" → FOLLOW_UP, requiresResponse=true, ")
                .append("responseMode=EMPLOYEE_RESPONSE.\n");
        return p.toString();
    }

    /** Strukturierte Felder aus dem LLM-Ergebnis; Fallback: deterministische Klassifikation. */
    private CommunicationClassifier.CommunicationClassification parseClassification(
            String answerText, String subject, String body) {
        CommunicationClassifier.CommunicationClassification fallback =
                CommunicationClassifier.classify(subject, body);
        if (answerText == null || answerText.isBlank()) {
            return fallback;
        }
        try {
            Matcher matcher = JSON_BLOCK.matcher(answerText);
            if (!matcher.find()) {
                return fallback;
            }
            JsonNode node = MAPPER.readTree(matcher.group());
            CommunicationClassifier.CommunicationType type = CommunicationClassifier.CommunicationType
                    .valueOf(node.path("communicationType").asText("OTHER").trim().toUpperCase(java.util.Locale.ROOT));
            boolean requiresResponse = node.path("requiresResponse").asBoolean(fallback.requiresResponse());
            boolean requiresWork = node.path("requiresAdministrativeWork")
                    .asBoolean(fallback.requiresAdministrativeWork());
            String action = node.path("suggestedAction").asText(fallback.suggestedAction());
            String responseMode = node.path("responseMode").asText(fallback.responseMode());
            if (!"NONE".equals(responseMode) && !"ACKNOWLEDGEMENT".equals(responseMode)
                    && !"EMPLOYEE_RESPONSE".equals(responseMode)) {
                responseMode = fallback.responseMode();
            }
            return new CommunicationClassifier.CommunicationClassification(
                    type, requiresResponse, requiresWork, action, responseMode);
        } catch (Exception e) {
            log.debug("Strukturierte LLM-Klassifikation nicht lesbar — deterministischer Fallback: {}",
                    e.getMessage());
            return fallback;
        }
    }

    /** Belege aus den echten Quell-Zitaten der bestehenden Pipeline (Validierung unverändert). */
    private List<EmailController.AiEvidence> evidenceOf(ReasonedAnswer answer) {
        List<EmailController.AiEvidence> evidence = new ArrayList<>();
        if (answer.sourceCitations() != null) {
            for (SourceCitation sc : answer.sourceCitations()) {
                evidence.add(new EmailController.AiEvidence(
                        sc.title() != null ? sc.title() : "",
                        sc.excerpt() != null ? sc.excerpt() : "",
                        sc.pageNumber(),
                        sc.confidenceScore(),
                        sc.tier() != null ? sc.tier().name() : null));
            }
        }
        return evidence;
    }

    /**
     * Schreibt das Analyse-Ergebnis in das BESTEHENDE E-Mail-Analyse-Ergebnis:
     * KI-Antwort, Belege, Grounding, Konfidenz und — falls das LLM strukturierte
     * Felder geliefert hat — die Kommunikations-Klassifikation. Fall-Identität,
     * Vorgangsnummer und Zuständigkeit des Intake bleiben UNVERÄNDERT.
     */
    private void updateOutcome(IncomingEmailEntity email,
                               CommunicationClassifier.CommunicationClassification llmClassification,
                               String aiAnswer, boolean grounded, Integer confidence,
                               List<EmailController.AiEvidence> evidence) {
        if (email.getAnalysisId() == null) {
            return;
        }
        EmailAnalysisEntity analysis = analysisRepository.findById(email.getAnalysisId()).orElse(null);
        if (analysis == null || analysis.getResultJson() == null) {
            return;
        }
        try {
            EmailController.EmailOutcome outcome = MAPPER.readValue(analysis.getResultJson(),
                    EmailController.EmailOutcome.class);
            MailboxIntakeService.IntakeInfo intake = outcome.intake();
            if (intake != null) {
                intake = new MailboxIntakeService.IntakeInfo(intake.mode(), intake.caseCode(), intake.caseName(),
                        intake.reviewRequired(), intake.invalidCaseId(), intake.assignedTo(), llmClassification);
            }
            EmailController.EmailOutcome updated = new EmailController.EmailOutcome(
                    outcome.subject(), outcome.topicLabel(), outcome.domainLabel(), outcome.intentType(),
                    outcome.matchedCases(), outcome.relevantDocuments(), outcome.missingDocuments(),
                    outcome.steps(), aiAnswer != null ? aiAnswer : outcome.aiAnswer(),
                    aiAnswer != null ? grounded : outcome.aiGrounded(),
                    confidence != null ? confidence : outcome.aiConfidence(),
                    evidence.isEmpty() ? outcome.aiEvidence() : evidence,
                    intake);
            analysis.setResultJson(MAPPER.writeValueAsString(updated));
            analysisRepository.save(analysis);
        } catch (Exception e) {
            log.warn("Analyse-Ergebnis für E-Mail {} nicht aktualisierbar: {}", email.getId(), e.getMessage());
        }
    }

    private List<String> attachedDocumentTitles(String workspaceId) {
        List<String> titles = new ArrayList<>();
        try {
            for (WorkspaceDocumentLinkEntity link : workspaceService.getWorkspaceDocuments(workspaceId)) {
                try {
                    Document doc = documentFacade.getDocument(link.getDocumentUuid(), "system");
                    if (doc.metadata() != null && doc.metadata().title() != null) {
                        titles.add(doc.metadata().title());
                    }
                } catch (Exception e) {
                    log.debug("Dokument {} nicht lesbar: {}", link.getDocumentId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Anhangs-Titel für Vorgang {} nicht lesbar: {}", workspaceId, e.getMessage());
        }
        return titles;
    }

    /** Kompakte frühere Kommunikationen (max. 3, gekürzt) — kein Historie-Dump. */
    private List<String> previousCommunications(IncomingEmailEntity current, String workspaceId) {
        List<String> lines = new ArrayList<>();
        try {
            List<IncomingEmailEntity> emails = emailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(workspaceId));
            int count = 0;
            for (IncomingEmailEntity e : emails) {
                if (e.getId().equals(current.getId())) {
                    continue;
                }
                if (count++ >= 3) {
                    break;
                }
                String subject = e.getSubject() != null ? e.getSubject() : "";
                String text = e.getText() != null ? firstWords(e.getText(), 40) : "";
                lines.add(subject + " — " + (text.isBlank() ? "(kein Text)" : text));
            }
        } catch (Exception e) {
            log.debug("Frühere Kommunikation für Vorgang {} nicht lesbar: {}", workspaceId, e.getMessage());
        }
        return lines;
    }

    private static String caseCategory(WorkspaceEntity ws) {
        try {
            Object category = ws.getPhaseDataMap().get("caseCategory");
            return category != null && !String.valueOf(category).isBlank()
                    ? String.valueOf(category) : "Allgemein";
        } catch (Exception e) {
            return "Allgemein";
        }
    }

    private static String firstWords(String text, int maxWords) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= 400) {
            return flat;
        }
        return flat.substring(0, 400) + "…";
    }

    /**
     * Die Analyse startet unmittelbar nach dem Abruf — die E-Mail ist in dessen
     * Transaktion gespeichert, aber noch nicht sichtbar. Kurze Warte-Schleife,
     * bis der Commit sichtbar ist (kein eigener Job-Mechanismus nötig).
     */
    private IncomingEmailEntity findEmailCommitted(String emailId) {
        IncomingEmailEntity email = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            email = emailRepository.findById(UUID.fromString(emailId)).orElse(null);
            if (email != null) {
                return email;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void recordStage(String jobId, String message) {
        try {
            progressService.recordStage(jobId, message);
        } catch (Exception ignored) {
        }
    }
}
