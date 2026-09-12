package verwaltungsassistent.web.service;

import verwaltungsassistent.web.controller.EmailController.EmailOutcome;
import verwaltungsassistent.web.mailbox.CommunicationClassifier.CommunicationClassification;
import verwaltungsassistent.web.mailbox.CommunicationClassifier.CommunicationType;
import verwaltungsassistent.web.mailbox.MailboxIntakeService.IntakeInfo;
import verwaltungsassistent.web.mailbox.MailboxIntakeService.IntakeMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2C.8 — Antwortentwurf für eingehende E-Mails: responseMode-Gating,
 * deterministische Zusammenstellung aus dem (bereits belegten) Analyse-Ergebnis
 * und ehrliche Klarstellungstexte statt erfundener Auskünfte. Rein deterministisch
 * — kein LLM, kein Qdrant, kein Versand.
 */
class EmailResponseDraftServiceTest {

    private static CommunicationClassification classification(String mode) {
        return new CommunicationClassification(
                CommunicationType.QUESTION, true, false, "Antwort vorbereiten.", mode);
    }

    private static EmailOutcome outcomeWith(IntakeInfo intake, boolean grounded, String answer) {
        return new EmailOutcome("Frage zum Wohngeld", "Wohngeld", "GENERAL", null,
                List.of(), List.of(), List.of(), List.of(),
                answer, grounded, grounded ? 80 : null, List.of(), intake);
    }

    private static IntakeInfo intake(String mode) {
        return new IntakeInfo(IntakeMode.NEW_CASE, "WS-ABCD1234", "Wohngeld – Familie Lang",
                false, null, null, classification(mode));
    }

    @Test
    void none_mode_neverProducesADraft() {
        EmailOutcome outcome = outcomeWith(intake("NONE"), true, "Alles gut.");

        assertFalse(EmailResponseDraftService.responseRelevant(outcome));
        assertNull(EmailResponseDraftService.composeBody(outcome));
        assertNull(EmailResponseDraftService.composeDraft(outcome, "01.09.2026", "Signatur"));
    }

    @Test
    void acknowledgement_producesReceiptOnly_noProcessingPromises() {
        EmailOutcome outcome = outcomeWith(intake("ACKNOWLEDGEMENT"), false, null);

        String body = EmailResponseDraftService.composeBody(outcome);
        assertNotNull(body);
        assertTrue(body.contains("Vielen Dank für Ihre Nachricht"));
        assertTrue(body.contains("erhalten"));
        assertFalse(body.contains("verbindliche Auskunft"), "Eingangsbestätigung bleibt Bestätigung");
    }

    @Test
    void employeeResponse_withGroundedAnswer_usesTheEvidenceBasedAnswer() {
        String groundedAnswer = "Für Ihren Antrag auf Wohngeld fehlen noch: "
                + "Mietvertrag und Einkommensnachweise der letzten drei Monate.";
        EmailOutcome outcome = outcomeWith(intake("EMPLOYEE_RESPONSE"), true, groundedAnswer);

        String body = EmailResponseDraftService.composeBody(outcome);

        assertTrue(body.contains("Mietvertrag und Einkommensnachweise"));
        assertFalse(body.contains("noch keine verbindliche Auskunft"),
                "belegte Antwort ersetzt den Klarstellungstext");
    }

    @Test
    void employeeResponse_withoutGrounding_asksForEmployeeReview_notInventsDetails() {
        EmailOutcome outcome = outcomeWith(intake("EMPLOYEE_RESPONSE"), false, null);

        String body = EmailResponseDraftService.composeBody(outcome);

        assertTrue(body.contains("noch keine verbindliche Auskunft"));
        assertTrue(body.contains("zuständigen Sachbearbeitung geprüft"));
        assertFalse(body.toLowerCase().contains("fristen"), "keine erfundenen Fristen");
        assertFalse(body.toLowerCase().contains("gebühr"), "keine erfundenen Gebühren");
    }

    @Test
    void manualEmail_withoutIntake_mayStillGetADraft() {
        EmailOutcome outcome = outcomeWith(null, true, "Belegte Antwort für eine manuelle Analyse.");

        assertTrue(EmailResponseDraftService.responseRelevant(outcome));
        assertTrue(EmailResponseDraftService.composeBody(outcome).contains("Belegte Antwort"));
    }

    @Test
    void composeDraft_wrapsBodyAsMunicipalEmail_withSignature() {
        EmailOutcome outcome = outcomeWith(intake("EMPLOYEE_RESPONSE"), false, null);

        String draft = EmailResponseDraftService.composeDraft(
                outcome, "02.09.2026", "Anna Bergmann\nSachbearbeitung");

        assertNotNull(draft);
        assertTrue(draft.startsWith("Betreff: Ihre Anfrage vom 02.09.2026 – Frage zum Wohngeld"));
        assertTrue(draft.contains("Sehr geehrte Bürgerin, sehr geehrter Bürger,"));
        assertTrue(draft.contains("Mit freundlichen Grüßen\nAnna Bergmann\nSachbearbeitung"));
    }

    @Test
    void composeDraft_usesSignaturePlaceholderWhenMissing() {
        EmailOutcome outcome = outcomeWith(intake("ACKNOWLEDGEMENT"), false, null);

        String draft = EmailResponseDraftService.composeDraft(outcome, "02.09.2026", null);

        assertTrue(draft.contains("[Signatur der zuständigen Sachbearbeitung einfügen]"));
    }
}
