package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.model.TimelineEventType;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.controller.EmailController;
import verwaltungsassistent.web.planning.CaseAssignmentService;
import verwaltungsassistent.web.service.CaseIdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2C.3b — automatischer Vorgangs-Intake: Ein eingehende Bürger-Kommunikation
 * wird deterministisch einem Vorgang zugeordnet oder legt einen NEUEN Vorgang an.
 *
 * <p>Grundregel der Konversation: Ohne gültige Vorgangsnummer → neuer Vorgang
 * (auch einfache Fragen — Verwaltungsarbeit kann Recherche, Rücksprache oder
 * Auskunftsvorbereitung bedeuten und muss messbar sein). Mit gültiger Nummer →
 * bestehender Vorgang, KEIN zweiter Vorgang. Syntaktisch gültige, aber nicht
 * existierende Nummer → {@code reviewRequired} (keine stille Neuanlage, kein
 * Fallthrough auf semantische Ähnlichkeit).</p>
 *
 * <p>Ziel-Ermittlung (Priorität): (1) explizite Vorgangsnummer im Betreff,
 * (2) bestätigte Absender-Beziehung (EMAIL_IDENTITY) / Thread-Header
 * (THREAD_IDENTITY) über den gemeinsamen Matching-Service. Namens-Heuristiken
 * und semantische Ähnlichkeit bleiben VORSCHLÄGE und werden nie automatisch
 * zugeordnet (keine versehentlichen Kreuz-Zuordnungen).</p>
 *
 * <p>Routing ist getrennt von der Fall-Identität: Direkter Mitarbeiter-
 * Empfänger → deterministische Zuordnung (kein Next-Best-Work-Override);
 * allgemeine Mailbox → Vorgang bleibt bewusst NICHT zugewiesen (owner
 * {@code null} — kein Fake-Mitarbeiter) und steht im allgemeinen Arbeitspool.</p>
 */
@Service
@Profile({"demo", "playwright"})
public class MailboxIntakeService {

    private static final Logger log = LoggerFactory.getLogger(MailboxIntakeService.class);

    public static final String INTAKE_MARKER = "intakeSource";
    public static final String INTAKE_SOURCE_MAILBOX = "MAILBOX";

    /** Modi des Intake-Ergebnisses (Anzeige + Verhalten). */
    public enum IntakeMode {
        /** Neuer Vorgang angelegt (neue Konversation ohne Vorgangsnummer). */
        NEW_CASE,
        /** Bestehender Vorgang (Vorgangsnummer / Thread / bestätigte Adresse). */
        EXISTING_CASE,
        /** Vorgangsnummer syntaktisch gültig, aber nicht vorhanden → Prüfung. */
        REVIEW_REQUIRED
    }

    /**
     * Ergebnis des Intake für die Vor-Analyse: Modus, Vorgang, Klassifikation,
     * Routing und Prüf-Flag. Wird im Analyse-Ergebnis der E-Mail gespeichert
     * (Anzeige in der E-Mail-Ansicht).
     */
    public record IntakeInfo(IntakeMode mode, String caseCode, String caseName,
                             boolean reviewRequired, String invalidCaseId,
                             String assignedTo,
                             CommunicationClassifier.CommunicationClassification classification) {}

    private final WorkspaceService workspaceService;
    private final CaseAssignmentService caseAssignmentService;
    private final UserAccountRepository userAccountRepository;

    public MailboxIntakeService(WorkspaceService workspaceService,
                                CaseAssignmentService caseAssignmentService,
                                UserAccountRepository userAccountRepository) {
        this.workspaceService = workspaceService;
        this.caseAssignmentService = caseAssignmentService;
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * Führt den Intake einer Nachricht durch (nachdem die E-Mail persistiert
     * und voranalysiert wurde — die Zuordnung wird auf der E-Mail gespeichert).
     *
     * @param analysisId  ID der (bereits gespeicherten) Vor-Analyse — wird als
     *                    {@code sourceEmailId} im Vorgang abgelegt
     * @param matches     Treffer des gemeinsamen Matching-Service (gleicher
     *                    Aufruf wie für die Vor-Analyse — keine Doppel-Logik)
     */
    public IntakeInfo intake(IncomingMessage message, IncomingEmailEntity email,
                             UUID analysisId,
                             List<EmailController.CaseRef> matches) {
        CommunicationClassifier.CommunicationClassification classification =
                CommunicationClassifier.classify(message.subject(), message.body());
        Optional<String> caseId = CaseIdService.parseCaseId(message.subject());
        if (caseId.isPresent()) {
            WorkspaceEntity existing = findWorkspaceByCode(caseId.get());
            if (existing != null) {
                return associateWithCase(message, email, existing, analysisId, classification);
            }
            log.warn("E-Mail '{}' referenziert unbekannte Vorgangsnummer {} — Prüfung erforderlich",
                    message.subject(), caseId.get());
            email.setReviewRequired(true);
            return new IntakeInfo(IntakeMode.REVIEW_REQUIRED, null, null, true,
                    caseId.get(), null, classification);
        }
        // Bestätigte Beziehung: EMAIL_IDENTITY / THREAD_IDENTITY → bestehender
        // Vorgang (Namens-Heuristiken und Ähnlichkeit bleiben Vorschläge).
        for (EmailController.CaseRef match : matches) {
            if ("EMAIL_IDENTITY".equals(match.matchType()) || "THREAD_IDENTITY".equals(match.matchType())) {
                WorkspaceEntity existing = workspaceService.findById(match.id()).orElse(null);
                if (existing != null) {
                    return associateWithCase(message, email, existing, analysisId, classification);
                }
            }
        }
        return createNewCase(message, email, analysisId, classification);
    }

    /** Neue Konversation → neuer Vorgang (owner null bzw. direkter Empfänger). */
    private IntakeInfo createNewCase(IncomingMessage message, IncomingEmailEntity email,
                                     UUID analysisId,
                                     CommunicationClassifier.CommunicationClassification classification) {
        String subject = subjectOf(message);
        // Deterministisch aus dem Betreff (transport-stabil, über Resets
        // gleich, im Demo-Seeder für Folge-E-Mails vorausberechenbar).
        String code = CaseIdService.deterministicCaseCode(subject);
        String description = textOf(message);
        WorkspaceEntity ws = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(subject, description, "CASE", null), code);
        Map<String, Object> data = new LinkedHashMap<>();
        String receivedAt = email.getReceivedAt() != null ? email.getReceivedAt().toString() : null;
        data.put("source", "E-Mail vom " + (email.getReceivedAt() != null
                ? DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(email.getReceivedAt().atZone(ZoneId.systemDefault())) : "—"));
        data.put("sourceEmailId", analysisId.toString());
        data.put("sourceEmailSubject", email.getSubject());
        data.put("sourceEmailAt", receivedAt);
        data.put("caseCategory", topicOf(message));
        // Ein Vorgang aus Bürger-Kommunikation ist unmittelbar bearbeitbar —
        // die Ingestion-Blockade ("Unterlagen fehlen") gilt nicht für
        // Auskunfts-/Frage-Vorgänge (Phase 2C.3b).
        data.put("ingestionResolved", true);
        data.put(INTAKE_MARKER, INTAKE_SOURCE_MAILBOX);
        try {
            ws.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        } catch (Exception e) {
            ws.setPhaseData("{}");
        }
        workspaceService.save(ws);
        recordIncomingEmail(email, ws);
        email.setWorkspaceId(UUID.fromString(ws.getId()));
        workspaceService.addTimelineEvent(ws.getId(), eventDate(email),
                "E-Mail eingegangen", "Ausgangsanfrage: \"" + subject + "\"",
                TimelineEventType.COMMUNICATION, null, 1.0, false);
        String assignedTo = routeRecipient(message, ws, email);
        log.info("Mailbox-Intake: neuer Vorgang {} ('{}', {}) aus E-Mail '{}'",
                ws.getWorkspaceCode(), ws.getName(), assignedTo != null ? "zugewiesen " + assignedTo : "nicht zugewiesen",
                subject);
        return new IntakeInfo(IntakeMode.NEW_CASE, ws.getWorkspaceCode(), ws.getName(),
                false, null, assignedTo, classification);
    }

    /**
     * Bestehende Konversation → E-Mail dem vorhandenen Vorgang zuordnen.
     * Anhänge hängt die Ingestion danach über die bestehende Pipeline an
     * (2C.3a, mit Vorgangs-ID — idempotent über die Provenienz-Tags).
     */
    private IntakeInfo associateWithCase(IncomingMessage message, IncomingEmailEntity email,
                                         WorkspaceEntity ws, UUID analysisId,
                                         CommunicationClassifier.CommunicationClassification classification) {
        recordIncomingEmail(email, ws);
        workspaceService.addTimelineEvent(ws.getId(), eventDate(email),
                "E-Mail eingegangen", "Folge-Nachricht: \"" + email.getSubject() + "\"",
                TimelineEventType.COMMUNICATION, null, 1.0, false);
        log.info("Mailbox-Intake: E-Mail '{}' dem bestehenden Vorgang {} zugeordnet",
                email.getSubject(), ws.getWorkspaceCode());
        return new IntakeInfo(IntakeMode.EXISTING_CASE, ws.getWorkspaceCode(),
                ws.getName() != null ? ws.getName() : ws.getWorkspaceCode(),
                false, null, ws.getOwnerId(), classification);
    }

    /**
     * Routing (§10): Direkter bekannter Mitarbeiter-Empfänger → deterministische
     * Zuordnung über den atomaren {@link CaseAssignmentService} (kein
     * Next-Best-Work-Override). Allgemeine Mailbox → Vorgang bleibt
     * unzugewiesen (owner null, allgemeiner Arbeitspool).
     *
     * @return die zugewiesene Mitarbeiter-E-Mail oder {@code null} (unzugewiesen)
     */
    private String routeRecipient(IncomingMessage message, WorkspaceEntity ws, IncomingEmailEntity email) {
        String recipient = directEmployeeRecipient(message);
        if (recipient == null) {
            return null;
        }
        try {
            caseAssignmentService.assign(ws.getId(), recipient, false);
            // E-Mail-Modell konsistent: direkt an eine Mitarbeiterin adressiert.
            email.setAddressedTo(IncomingEmailEntity.AddressedTo.EMPLOYEE);
            email.setAddressedToEmail(recipient);
            return recipient;
        } catch (Exception e) {
            log.warn("Direkte Empfänger-Zuordnung an {} fehlgeschlagen: {}", recipient, e.getMessage());
            return null;
        }
    }

    /** Erster Empfänger, der ein bekannter MITARBEITER-Account ist — sonst null
     *  (allgemeine Mailbox). Das Leitungs-Konto (ADMIN) ist keine operative
     *  Zuständigkeit: eine an die Leitung adressierte Nachricht wird NICHT an
     *  sie geroutet, sondern fällt in den allgemeinen Pool (owner null). */
    private String directEmployeeRecipient(IncomingMessage message) {
        if (message.recipients() == null) {
            return null;
        }
        for (String recipient : message.recipients()) {
            if (recipient == null || recipient.isBlank()) {
                continue;
            }
            try {
                var account = userAccountRepository
                        .findByEmail(recipient.trim().toLowerCase(java.util.Locale.ROOT));
                if (account.isPresent()
                        && account.get().getRoles().stream()
                                .noneMatch(r -> r.name().equals("ADMIN"))) {
                    return recipient.trim();
                }
            } catch (Exception e) {
                log.debug("Empfänger-Prüfung für {} fehlgeschlagen: {}", recipient, e.getMessage());
            }
        }
        return null;
    }

    private void recordIncomingEmail(IncomingEmailEntity email, WorkspaceEntity ws) {
        // Kommunikations-Verlauf: die E-Mail gehört dem Vorgang — auch wenn er
        // abgeschlossen ist; der Status des Vorgangs ändert sich nicht.
        email.setWorkspaceId(UUID.fromString(ws.getId()));
    }

    private WorkspaceEntity findWorkspaceByCode(String code) {
        return workspaceService.findAll().stream()
                .filter(ws -> code.equalsIgnoreCase(ws.getWorkspaceCode()))
                .findFirst().orElse(null);
    }

    private static LocalDate eventDate(IncomingEmailEntity email) {
        return email.getReceivedAt() != null
                ? LocalDate.ofInstant(email.getReceivedAt(), ZoneId.systemDefault())
                : LocalDate.now();
    }

    private static String subjectOf(IncomingMessage message) {
        String subject = message.subject() != null ? message.subject().trim() : "";
        if (subject.isEmpty()) {
            return "E-Mail ohne Betreff";
        }
        return subject.length() > 120 ? subject.substring(0, 120) + "…" : subject;
    }

    private static String textOf(IncomingMessage message) {
        String text = message.body() != null ? message.body().trim() : "";
        if (text.isEmpty()) {
            return "Vorgang aus eingehender E-Mail angelegt.";
        }
        return text.length() > 252 ? text.substring(0, 252) + "…" : text;
    }

    private static String topicOf(IncomingMessage message) {
        return EmailController.detectTopic((message.subject() == null ? "" : message.subject() + "\n")
                + (message.body() == null ? "" : message.body()));
    }
}
