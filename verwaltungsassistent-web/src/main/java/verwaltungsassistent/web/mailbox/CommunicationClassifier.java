package verwaltungsassistent.web.mailbox;

/**
 * Deterministische Kommunikations-Klassifikation einer Bürger-E-Mail
 * (Phase 2C.3b) — dieselbe Rolle wie die Themen-Erkennung der Vor-Analyse:
 * kein LLM-Aufruf, kein Ollama, sofortige und testbare Antworten. Die
 * Klassifikation ist SEMANTISCHE EINORDNUNG, nie autoritativ für Fall-Identität,
 * Aktenzeichen, Zuständigkeit oder Datenbank-Zustand (die bleiben system-
 * bestimmt).
 *
 * <p>Produktregel: Auch eine scheinbar einfache Frage kann Verwaltungsarbeit
 * bedeuten (Recherche, Auskunftsvorbereitung …). Daher gilt — außer bei
 * ausdrücklicher Erledigung — {@code requiresResponse=true} und
 * {@code requiresAdministrativeWork=true}. „Danke, das war schon alles" wird
 * als abschließende Nachricht erkannt ({@code requiresResponse=false}).</p>
 *
 * <p>{@code responseMode} bereitet die spätere automatische
 * Eingangsbestätigung vor (§9): neue Konversationen, die eine Antwort
 * erfordern, erhalten später automatisch eine Bestätigung (ACKNOWLEDGEMENT);
 * Nachrichten zu bestehenden Vorgängen beantwortet die zuständige
 * Mitarbeiterin (EMPLOYEE_RESPONSE); abschließende Nachrichten keine (NONE).
 * Es wird in dieser Phase keine Mail versendet.</p>
 */
public final class CommunicationClassifier {

    public enum CommunicationType {
        APPLICATION, QUESTION, INFORMATION_REQUEST, FOLLOW_UP,
        CLARIFICATION, COMPLAINT, OTHER
    }

    public record CommunicationClassification(
            CommunicationType communicationType,
            boolean requiresResponse,
            boolean requiresAdministrativeWork,
            String suggestedAction,
            String responseMode) {}

    private CommunicationClassifier() {
    }

    /**
     * Klassifiziert eine Nachricht aus Betreff + Text. Reine
     * Wort-Heuristiken auf normalisiertem Text (lowercase, Umlaut-Faltung) —
     * Reihenfolge bestimmt die Priorität.
     */
    public static CommunicationClassification classify(String subject, String body) {
        String text = normalize(subject == null ? "" : subject + "\n")
                + normalize(body == null ? "" : body);
        if (isClosingGratitude(text)) {
            return new CommunicationClassification(CommunicationType.OTHER, false, false,
                    "Keine Antwort erforderlich — Eingangsbestätigung des Abschlusses im Vorgang dokumentieren.",
                    "NONE");
        }
        CommunicationType type = typeOf(text);
        boolean response = true;
        String action = switch (type) {
            case APPLICATION -> "Antrag prüfen und über die erforderlichen Unterlagen und den weiteren Ablauf informieren.";
            case COMPLAINT -> "Beschwerde sichten, Sachverhalt prüfen und eine Antwort bzw. Rückmeldung vorbereiten.";
            case CLARIFICATION -> "Rückfrage klären und eine präzise Antwort formulieren.";
            case INFORMATION_REQUEST -> "Auskunft zusammenstellen und beantworten.";
            case FOLLOW_UP -> "Auf dem bestehenden Vorgang fortführen, offene Punkte klären und antworten.";
            case QUESTION -> "Frage beantworten; ggf. Sachverhalt kurz prüfen.";
            default -> "Anliegen sichten, ggf. recherchieren und antworten.";
        };
        return new CommunicationClassification(type, response, true, action,
                response ? "ACKNOWLEDGEMENT" : "NONE");
    }

    private static CommunicationType typeOf(String text) {
        if (containsAny(text, "beschwerde", "reklamation", "beanstande", "mängel", "mangelhaft")) {
            return CommunicationType.COMPLAINT;
        }
        // Konversations-Marker (Folge-Nachricht) — NICHT das Themenwort
        // "Nachfrage" (das steht in vielen Betreffen ohne Folge-Charakter).
        if (containsAny(text, "frage habe ich noch", "noch eine frage", "frage hätte ich",
                "frage haette ich", "ergänzend", "ergaenzend", "folgefrage",
                "wie sieht es mit", "ich melde mich nochmal", "nochmal eine frage")) {
            return CommunicationType.FOLLOW_UP;
        }
        if (containsAny(text, "klär", "klaer", "bedeutet das genau", "was bedeutet",
                "wieso", "weshalb", "wie genau", "inwiefern")) {
            return CommunicationType.CLARIFICATION;
        }
        // Auskunftsfrage über Unterlagen/Voraussetzungen vor dem bloßen
        // Antrags-Begriff: "Welche Unterlagen benötige ich für den Antrag?"
        // ist eine Informationsanfrage, kein Antrag.
        if (containsAny(text, "auskunft", "information", "welche unterlagen", "benötige ich",
                "benoetige ich", "was brauche ich", "wie lautet", "status", "sachstand",
                "bearbeitungsstand", "welche nachweise")) {
            return CommunicationType.INFORMATION_REQUEST;
        }
        if (containsAny(text, "antrag", "beantragen", "beantrage", "anmeldung", "anmelden",
                "beantragung", "unterlagen einreichen", "reichte ich ein", "zur bearbeitung")) {
            return CommunicationType.APPLICATION;
        }
        if (containsAny(text, "frage", "?")) {
            return CommunicationType.QUESTION;
        }
        return CommunicationType.OTHER;
    }

    /**
     * Abschließende Dank-/Erledigungs-Nachricht: beantwortet KEINE offene
     * Frage und verlangt keine weitere Reaktion. Nur zusammen mit einem
     * Dank/Erledigungs-Ausdruck gewertet — eine Dankes-Floskel in einer
     * Anfrage („Vielen Dank im Voraus") ist keine Erledigung.
     */
    private static boolean isClosingGratitude(String text) {
        boolean thanks = containsAny(text, "vielen dank für die information",
                "vielen dank fuer die information", "danke für die information",
                "danke fuer die information", "danke für ihre antwort",
                "danke fuer ihre antwort", "danke, das war schon alles",
                "danke das war schon alles", "vielen dank, das war");
        if (!thanks) {
            return false;
        }
        return containsAny(text, "das war schon alles", "keine weiteren fragen",
                "keine weiteren rückfragen", "keine weiteren rueckfragen", "alles erledigt",
                "keine frage mehr", "keine offenen fragen", "passt so", "damit ist alles geklärt",
                "damit ist alles geklaert", "auf wiedersehen", "schönen tag", "schoenen tag",
                "bis bald", "mit freundlichen grüßen");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Umlaut-Faltung + Kleinbuchstaben für robuste Heuristiken. */
    private static String normalize(String input) {
        return input.toLowerCase(java.util.Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
}
