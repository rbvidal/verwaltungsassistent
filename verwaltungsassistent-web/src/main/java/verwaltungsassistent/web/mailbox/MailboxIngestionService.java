package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.EmailCaseMatchingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Verwaltungsassistent-Eingangs-Schicht für die Mailbox (Phase 2C.2): holt normalisierte
 * Nachrichten über den {@link MailboxConnector} und führt sie in die
 * BESTEHENDE E-Mail-Pipeline ein — ein {@link IncomingEmailEntity} mit
 * {@code SourceType.MAILBOX} plus SOFORTIGER automatischer Verarbeitung
 * (Klassifikation + Fall-/Thread-Matching + Vor-Analyse), wie bei einem
 * echten Mailbox-Eingang. Die Mitarbeiterin muss NICHT zusätzlich
 * "Analysieren" klicken.
 *
 * <p>Idempotenz ist Pflicht: Wiederholte Abrufe dürfen keine Duplikate
 * erzeugen (Message-ID als Primärschlüssel, deterministischer
 * Inhaltsschlüssel als Fallback). Schlägt die Verarbeitung einer Nachricht
 * fehl, wird die E-Mail trotzdem erhalten (Wiederholung über die bestehende
 * Analyse-Aktion) und der Fehler sichtbar protokolliert.</p>
 *
 * <p>Laufzeit-Grenze (Phase 2C.15): Der Dienst haengt ausschliesslich am
 * {@link MailboxConnector}-Interface; die konkrete Implementierung liefert
 * die Spring-Konfiguration des aktiven Profils (Demo: GreenMailMailboxConnector
 * in demo/playwright). Der Dienst kennt weder GreenMail noch den Anbieter —
 * eine andere Connector-Bean kann denselben API-Typ ersetzen.</p>
 */
@Service
@Profile({"demo", "playwright"})
public class MailboxIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MailboxIngestionService.class);

    private final MailboxConnector connector;
    private final JpaIncomingEmailRepository emailRepository;
    private final JpaEmailAnalysisRepository analysisRepository;
    private final EmailCaseMatchingService caseMatchingService;
    private final MailboxAttachmentDocumentIngestionService attachmentIngestionService;
    private final MailboxIntakeService intakeService;
    private final IncomingCommunicationAnalysisService communicationAnalysisService;
    private final ObjectMapper mapper = new ObjectMapper();

    public MailboxIngestionService(MailboxConnector connector,
                                   JpaIncomingEmailRepository emailRepository,
                                   JpaEmailAnalysisRepository analysisRepository,
                                   EmailCaseMatchingService caseMatchingService,
                                   MailboxAttachmentDocumentIngestionService attachmentIngestionService,
                                   MailboxIntakeService intakeService,
                                   IncomingCommunicationAnalysisService communicationAnalysisService) {
        this.connector = connector;
        this.emailRepository = emailRepository;
        this.analysisRepository = analysisRepository;
        this.caseMatchingService = caseMatchingService;
        this.attachmentIngestionService = attachmentIngestionService;
        this.intakeService = intakeService;
        this.communicationAnalysisService = communicationAnalysisService;
    }

    /**
     * Ergebnis eines Abrufs: importierte/voranalysierte/fehlgeschlagene
     * E-Mails, übernommene/fehlgeschlagene Anhänge (2C.3a) und automatisch
     * ANGELEGTE Vorgänge (2C.3b — neue Konversationen ohne Vorgangsnummer).
     */
    public record MailboxFetchResult(int imported, int analysed, int failed,
                                     int attachmentsImported, int attachmentsFailed,
                                     int newCases) {
        public boolean hasNewMessages() {
            return imported > 0;
        }
    }

    /**
     * Holt alle Nachrichten und verarbeitet die noch unbekannten (Message-ID-
     * basierte Deduplizierung): persistieren → automatisch klassifizieren /
     * Fall-/Thread-Matching → deterministische Vor-Analyse → sichtbares
     * Ergebnis. Synchron, kein Scheduler.
     */
    @Transactional
    public MailboxFetchResult fetchAndIngest() {
        int imported = 0;
        int analysed = 0;
        int failed = 0;
        int attachmentsImported = 0;
        int attachmentsFailed = 0;
        int newCases = 0;
        for (IncomingMessage message : connector.fetchNewMessages()) {
            MessageProcessResult r = processOne(message);
            imported += r.imported();
            analysed += r.analysed();
            failed += r.failed();
            attachmentsImported += r.attachmentsImported();
            attachmentsFailed += r.attachmentsFailed();
            newCases += r.newCase() ? 1 : 0;
        }
        if (imported > 0) {
            log.info("Postfach abrufen: {} neue E-Mail(s), {} automatisch analysiert, {} fehlgeschlagen, "
                            + "{} Anhänge übernommen, {} Anhänge fehlgeschlagen, {} neue Vorgänge",
                    imported, analysed, failed, attachmentsImported, attachmentsFailed, newCases);
        }
        return new MailboxFetchResult(imported, analysed, failed,
                attachmentsImported, attachmentsFailed, newCases);
    }

    /**
     * Verarbeitet EINE Mailbox-Nachricht durch denselben bestehenden Pfad wie
     * der Postfach-Abruf (Phase 2D.11 — Wiederverwendung statt Duplikat):
     * deduplizieren (Message-ID) → persistieren → klassifizieren/Matching →
     * deterministische Vor-Analyse → Vorgangs-Intake → Anhang-Übernahme →
     * Start der asynchronen Kommunikations-Analyse (sofern Vorgang). Der
     * Aufrufer kann die E-Mail-Id des Ergebnisses nutzen, um die Fertigstellung
     * der Kommunikations-Analyse über den Job-Schlüssel {@code email:<id>}
     * abzuwarten.
     */
    @Transactional
    public MessageProcessResult processOne(IncomingMessage message) {
        String dedupKey = dedupKeyOf(message);
        if (dedupKey == null || emailRepository.findByMessageId(dedupKey).isPresent()) {
            return new MessageProcessResult(0, 0, 0, 0, 0, false, null, null);
        }
        IncomingEmailEntity entity = createEntity(message, dedupKey);
        int analysed = 0;
        int failed = 0;
        int attachmentsImported = 0;
        int attachmentsFailed = 0;
        boolean newCase = false;
        try {
            String text = (message.subject() == null ? "" : message.subject() + "\n")
                    + (message.body() == null ? "" : message.body());
            // Gemeinsames Matching (dieselbe Semantik wie die manuelle
            // Analyse): der Intake nutzt bestätigte Beziehungen daraus.
            List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches =
                    caseMatchingService.matchCases(
                            text, message.senderEmail(), null, true, Set.copyOf(message.threadReferences()));
            UUID analysisId = preAnalysisFor(entity, message, matches);
            if (analysisId == null) {
                failed++;
            } else {
                entity.setAnalysisId(analysisId);
                analysed++;
            }
            // Phase 2C.3b: Vorgangs-Intake (neu/bestehend/Prüfung erforderlich).
            try {
                MailboxIntakeService.IntakeInfo intake =
                        intakeService.intake(message, entity, analysisId, matches);
                if (intake.mode() == MailboxIntakeService.IntakeMode.NEW_CASE) {
                    newCase = true;
                }
                if (intake != null && analysisId != null) {
                    updateAnalysisWithIntake(analysisId, intake);
                }
            } catch (Exception e) {
                failed++;
                log.error("Vorgangs-Intake der Mailbox-Nachricht '{}' fehlgeschlagen: {}",
                        message.subject(), e.getMessage(), e);
            }
        } catch (Exception e) {
            failed++;
            log.error("Mailbox-Nachricht '{}' konnte nicht automatisch verarbeitet werden: {}",
                    message.subject(), e.getMessage(), e);
        }
        // Die E-Mail bleibt in jedem Fall erhalten (Status NEW ohne
        // Analyse → Wiederholung über die bestehende Analyse-Aktion).
        emailRepository.save(entity);
        // Phase 2C.3a/2C.3b: Anhänge als Dokumente über die bestehende
        // Pipeline übernehmen — mit der Vorgangs-ID des Intake (neu oder
        // bestehend); bei Prüfung-Erforderlich ohne Vorgang bleiben die
        // Dokumente erhalten und werden nach Zuordnung verknüpft.
        try {
            String workspaceId = entity.getWorkspaceId() != null
                    ? entity.getWorkspaceId().toString() : null;
            var attachmentResult = attachmentIngestionService.ingestAttachments(message, workspaceId);
            attachmentsImported += attachmentResult.imported();
            attachmentsFailed += attachmentResult.failed();
        } catch (Exception e) {
            attachmentsFailed += message.attachments() == null ? 0 : message.attachments().size();
            log.error("Anhang-Verarbeitung der Mailbox-Nachricht '{}' fehlgeschlagen: {}",
                    message.subject(), e.getMessage(), e);
        }
        // Phase 2C.3c: vollständige Kommunikations-Analyse (E-Mail + Anhänge +
        // Vorgangskontext) über die bestehende KI-Pipeline — asynchron über
        // den Job-Mechanismus; ohne Vorgang (Prüfung erforderlich) gibt es
        // keine Fall-Kontext-Analyse. Fehler isoliert: Der Intake bleibt
        // in jedem Fall erhalten.
        if (entity.getWorkspaceId() != null) {
            try {
                communicationAnalysisService.submitAnalysis(entity, entity.getWorkspaceId().toString());
            } catch (Exception e) {
                log.warn("Kommunikations-Analyse für '{}' nicht gestartet: {}", message.subject(), e.getMessage());
            }
        }
        return new MessageProcessResult(1, analysed, failed,
                attachmentsImported, attachmentsFailed, newCase,
                entity.getId(), entity.getWorkspaceId() != null
                        ? entity.getWorkspaceId().toString() : null);
    }

    /** Ergebnis der Einzel-Nachricht-Verarbeitung (Phase 2D.11). */
    public record MessageProcessResult(int imported, int analysed, int failed,
                                       int attachmentsImported, int attachmentsFailed,
                                       boolean newCase, UUID emailId, String workspaceId) {}

    /** Ergänzt das gespeicherte Analyse-Ergebnis um den Intake-Befund (Anzeige). */
    private void updateAnalysisWithIntake(UUID analysisId, MailboxIntakeService.IntakeInfo intake) {
        try {
            EmailAnalysisEntity analysis = analysisRepository.findById(analysisId).orElse(null);
            if (analysis == null || analysis.getResultJson() == null) {
                return;
            }
            verwaltungsassistent.web.controller.EmailController.EmailOutcome outcome =
                    mapper.readValue(analysis.getResultJson(),
                            verwaltungsassistent.web.controller.EmailController.EmailOutcome.class);
            verwaltungsassistent.web.controller.EmailController.EmailOutcome withIntake =
                    new verwaltungsassistent.web.controller.EmailController.EmailOutcome(
                            outcome.subject(), outcome.topicLabel(), outcome.domainLabel(),
                            outcome.intentType(), outcome.matchedCases(), outcome.relevantDocuments(),
                            outcome.missingDocuments(), outcome.steps(), outcome.aiAnswer(),
                            outcome.aiGrounded(), outcome.aiConfidence(), outcome.aiEvidence(), intake);
            analysis.setResultJson(mapper.writeValueAsString(withIntake));
            analysisRepository.save(analysis);
        } catch (Exception e) {
            log.warn("Intake-Befund konnte nicht im Analyse-Ergebnis ergänzt werden: {}", e.getMessage());
        }
    }

    /** Primärschlüssel: Message-ID; Fallback ohne Message-ID = deterministischer Inhaltsschlüssel. */
    private String dedupKeyOf(IncomingMessage message) {
        if (message.messageId() != null && !message.messageId().isBlank()) {
            return message.messageId().trim();
        }
        String basis = (message.subject() == null ? "" : message.subject())
                + "|" + (message.senderEmail() == null ? "" : message.senderEmail())
                + "|" + (message.body() == null ? "" : message.body());
        return "synthetic-" + sha256(basis);
    }

    private IncomingEmailEntity createEntity(IncomingMessage message, String dedupKey) {
        IncomingEmailEntity entity = new IncomingEmailEntity(
                UUID.randomUUID(),
                subjectOf(message),
                message.senderName() != null ? message.senderName() : "—",
                message.senderEmail(),
                message.body() != null ? message.body() : "",
                message.receivedAt() != null ? message.receivedAt() : Instant.now(),
                IncomingEmailEntity.AddressedTo.GENERAL,
                firstRecipientOrNull(message));
        entity.setSourceType(IncomingEmailEntity.SourceType.MAILBOX);
        entity.setStatus(IncomingEmailEntity.Status.NEW);
        entity.setMessageId(dedupKey);
        entity.setInReplyTo(message.inReplyTo());
        entity.setReferences(message.references());
        return entity;
    }

    /**
     * Automatische Verarbeitung (deterministisch, kein LLM): Klassifikation
     * über die bestehende Themen-Erkennung; Fall-/Thread-Matching kommt vom
     * GEMEINSAMEN {@link EmailCaseMatchingService} (derselbe Aufruf, der auch
     * der Intake nutzt — EMAIL_IDENTITY &lt; THREAD_IDENTITY &lt; NAME_IDENTITY
     * &lt; SIMILAR). Der Intake (2C.3b) bestimmt daraus deterministisch den
     * Vorgang; ein bloß thematisch ähnlicher Treffer wird NIE automatisch
     * zugeordnet.
     */
    private UUID preAnalysisFor(IncomingEmailEntity email, IncomingMessage message,
                                List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches)
            throws Exception {
        String topic = verwaltungsassistent.web.controller.EmailController
                .detectTopic((message.subject() == null ? "" : message.subject() + "\n")
                        + (message.body() == null ? "" : message.body()));
        List<verwaltungsassistent.web.controller.EmailController.Step> steps = List.of(
                new verwaltungsassistent.web.controller.EmailController.Step(1,
                        "Fallbezug prüfen",
                        matches.isEmpty()
                                ? "Kein passender bestehender Vorgang erkannt — neuen Vorgang anlegen."
                                : "Automatisch als Folge-/Zugehörigkeits-Kandidat erkannt — Zuordnung ausdrücklich bestätigen.",
                        matches.isEmpty() ? "Neuen Vorgang anlegen" : "Vorgang prüfen",
                        matches.isEmpty() ? "/cases/new" : "/cases/" + matches.get(0).id(),
                        true));
        verwaltungsassistent.web.controller.EmailController.EmailOutcome outcome =
                new verwaltungsassistent.web.controller.EmailController.EmailOutcome(
                        email.getSubject(), topic, null, null,
                        matches, List.of(),
                        verwaltungsassistent.web.controller.EmailController
                                .typicalMissingDocuments(topic),
                        steps, null, null, null, List.of());
        EmailAnalysisEntity analysis = new EmailAnalysisEntity(UUID.randomUUID(),
                DemoMailboxServer.MAILBOX_USER, email.getText(), email.getSubject(),
                topic, null, null, Instant.now());
        analysis.setResultJson(mapper.writeValueAsString(outcome));
        analysisRepository.save(analysis);
        return analysis.getId();
    }

    private static String subjectOf(IncomingMessage message) {
        String subject = message.subject() != null ? message.subject().trim() : "";
        if (subject.isEmpty()) {
            return "E-Mail ohne Betreff";
        }
        return subject.length() > 120 ? subject.substring(0, 120) + "…" : subject;
    }

    private static String firstRecipientOrNull(IncomingMessage message) {
        if (message.recipients() == null || message.recipients().isEmpty()) {
            return null;
        }
        return message.recipients().get(0);
    }

    private static String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
