package verwaltungsassistent.web.service;

import verwaltungsassistent.web.controller.EmailController.EmailOutcome;

/**
 * Antwortentwurf für eingehende E-Mails (Phase 2C.8): deterministische
 * Aufbereitung des BEREITS VORLIEGENDEN Analyse-Ergebnisses zu einem
 * bearbeitbaren Entwurf einer Mitarbeiter-Antwort. Es wird KEINE zweite
 * KI-Pipeline gestartet und nichts versendet: Der Entwurf entsteht aus dem
 * bestehenden {@code EmailOutcome} (dessen KI-Beantwortung {@code aiAnswer}
 * ist bereits über die bestehende AiFacade-Pipeline belegt/gegroundet) bzw.
 * aus ehrlichen Klarstellungs-Texten, wenn keine belastbare Antwort vorliegt.
 *
 * <p>Gating über {@code responseMode} (2C.3b/2C.3c, unverändert):
 * EMPLOYEE_RESPONSE → substanzieller Entwurf; ACKNOWLEDGEMENT → Eingangs-
 * bestätigung; NONE → kein Entwurf. Es werden keine Fristen, Beträge,
 * Bescheide oder Verfahrensschritte erfunden.</p>
 */
public final class EmailResponseDraftService {

    private EmailResponseDraftService() {
    }

    /** true wenn für diese Analyse ein Antwortentwurf vorgesehen ist. */
    public static boolean responseRelevant(EmailOutcome outcome) {
        if (outcome == null || outcome.intake() == null) {
            // Manuell analysierte E-Mails (ohne Intake-Klassifikation): Entwurf
            // ist möglich — der Text entscheidet über die Substanz.
            return true;
        }
        if (outcome.intake().classification() == null) {
            return false;
        }
        String mode = outcome.intake().classification().responseMode();
        if (mode == null) {
            return true;
        }
        return switch (mode) {
            case "NONE" -> false;
            default -> true; // EMPLOYEE_RESPONSE, ACKNOWLEDGEMENT
        };
    }

    /** Entwurfs-Textbaustein (Körper ohne Betreff/Anrede/Signatur). */
    public static String composeBody(EmailOutcome outcome) {
        if (!responseRelevant(outcome)) {
            return null;
        }
        if (isAcknowledgement(outcome)) {
            return "Vielen Dank für Ihre Nachricht.\n\n"
                    + "Wir haben Ihre Nachricht erhalten und werden sie bei der weiteren Bearbeitung berücksichtigen.";
        }
        boolean grounded = Boolean.TRUE.equals(outcome.aiGrounded())
                && outcome.aiAnswer() != null && !outcome.aiAnswer().isBlank();
        if (grounded) {
            return outcome.aiAnswer().trim()
                    + "\n\nFür Rückfragen steht Ihnen die zuständige Sachbearbeitung gern zur Verfügung.";
        }
        // Keine belastbare Quellenlage: ehrlicher Klarstellungstext statt
        // erfundener Auskünfte (keine Zusagen zu Fristen oder Ergebnissen).
        return "zu Ihrer Anfrage können wir Ihnen derzeit noch keine verbindliche Auskunft erteilen. "
                + "Der Vorgang wird von der zuständigen Sachbearbeitung geprüft; "
                + "Sie erhalten eine Rückmeldung, sobald das Ergebnis vorliegt.";
    }

    private static boolean isAcknowledgement(EmailOutcome outcome) {
        if (outcome.intake() == null || outcome.intake().classification() == null) {
            return false;
        }
        return "ACKNOWLEDGEMENT".equals(outcome.intake().classification().responseMode());
    }

    /** Vollständiger Entwurf inkl. Betreff, Anrede und Signatur-Block. */
    public static String composeDraft(EmailOutcome outcome, String dateLabel,
                                      String signatureText) {
        String body = composeBody(outcome);
        if (body == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String subject = outcome.subject() != null ? outcome.subject().trim() : "";
        sb.append("Betreff: Ihre Anfrage vom ").append(dateLabel)
                .append(subject.isEmpty() ? "" : " – " + subject)
                .append("\n\nSehr geehrte Bürgerin, sehr geehrter Bürger,\n\n")
                .append(body)
                .append("\n\nMit freundlichen Grüßen\n")
                .append(signatureText != null && !signatureText.isBlank()
                        ? signatureText
                        : "[Signatur der zuständigen Sachbearbeitung einfügen]");
        return sb.toString();
    }
}
