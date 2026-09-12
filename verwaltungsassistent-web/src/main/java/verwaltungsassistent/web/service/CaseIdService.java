package verwaltungsassistent.web.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kanonischer externer Konversations-Schlüssel (Phase 2C.3b): die
 * Vorgangsnummer im bestehenden {@code WS-XXXXXXXX}-Format (Aktenzeichen der
 * Plattform — kein paralleles VG-…-Format). Bürger-E-Mails tragen sie im
 * Betreff (z. B. {@code AW: [WS-1A2B3C4D] Frage zum Wohngeld}); der Parser ist
 * deterministisch, maschinenlesbar und toleriert Klein-/Großschreibung.
 *
 * <p>Der Parser ist syntaktisch, nicht autoritativ: Ob die Nummer zu einem
 * echten Vorgang gehört, entscheidet die Anwendungs-Schicht (Suche nach dem
 * Code). Eine syntaktisch gültige, aber nicht existierende Nummer wird
 * markiert und NICHT über semantische Ähnlichkeit auf einen anderen Vorgang
 * umgeleitet.</p>
 */
public final class CaseIdService {

    private static final Pattern CASE_ID = Pattern.compile(
            "\\b(WS-[0-9A-Fa-f]{8})\\b", Pattern.CASE_INSENSITIVE);

    private CaseIdService() {
    }

    /** Erste Vorgangsnummer im Betreff (normalisiert: {@code WS-XXXXXXXX}), sofern syntaktisch gültig. */
    public static Optional<String> parseCaseId(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = CASE_ID.matcher(subject);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Deterministisches Aktenzeichen für einen Mailbox-Intake-Vorgang
     * (Phase 2C.3b): aus dem Betreff der Auslöser-E-Mail. Der Betreff überlebt
     * den Transport (GreenMail schreibt Message-IDs neu — der Betreff nicht),
     * ist über Demo-Resets stabil und lässt sich im Demo-Seeder für
     * Folge-E-Mails vorausberechnen ({@code AW: [WS-XXXXXXXX] …}).
     */
    public static String deterministicCaseCode(String subject) {
        String basis = subject != null && !subject.isBlank()
                ? subject.trim().toLowerCase(java.util.Locale.ROOT) : "no-subject";
        String digest = sha256Hex(basis);
        return "WS-" + digest.substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode()) + "00000000";
        }
    }
}
