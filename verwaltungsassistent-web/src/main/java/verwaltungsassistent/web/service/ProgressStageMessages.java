package verwaltungsassistent.web.service;

/**
 * Translates semantic pipeline stage keys into German user-facing progress
 * messages per operation kind. Deliberately no technical vocabulary: no model
 * names, scores, strategies or internal identifiers are exposed to the user.
 */
public final class ProgressStageMessages {

    private ProgressStageMessages() {
    }

    /** Returns the user-facing message for a stage and operation kind, or null if the stage is not shown. */
    public static String forKind(JobProgressService.Kind kind, String stage) {
        return switch (kind) {
            case ASSISTANT -> switch (stage) {
                case "intent" -> "Ihre Anfrage wird analysiert …";
                case "routing" -> "Die Anfrage wurde verstanden und wird eingeordnet …";
                case "retrieval-started" -> "Relevante Informationen und Vorschriften werden gesucht …";
                case "retrieval-done" -> "Passende Informationen wurden gefunden.";
                case "evidence" -> "Die gefundenen Informationen werden auf ihre Eignung geprüft …";
                case "answer-generation" -> "Die Antwort wird vorbereitet …";
                case "coverage" -> "Die Belege werden mit der Antwort abgeglichen …";
                case "ground" -> "Die Angaben werden unabhängig geprüft …";
                default -> null;
            };
            case ANALYSIS -> switch (stage) {
                case "intent" -> "Die Analyse wird vorbereitet …";
                case "routing" -> "Der Fall wird eingeordnet …";
                case "retrieval-started" -> "Relevante Dokumente werden gesucht …";
                case "retrieval-done" -> "Passende Dokumente wurden gefunden.";
                case "evidence" -> "Die gefundenen Informationen werden geprüft …";
                case "answer-generation" -> "Die Empfehlung wird erstellt …";
                case "coverage" -> "Die Belege werden mit der Empfehlung abgeglichen …";
                case "ground" -> "Die Analyse wird unabhängig geprüft …";
                default -> null;
            };
            case EVALUATION -> switch (stage) {
                case "intent" -> "Der Fall wird für die Auswertung vorbereitet …";
                case "routing" -> "Die Frage wird eingeordnet …";
                case "retrieval-started" -> "Relevante Informationen werden gesucht …";
                case "retrieval-done" -> "Passende Informationen wurden gefunden.";
                case "evidence" -> "Die gefundenen Informationen werden geprüft …";
                case "answer-generation" -> "Die Antwort wird erstellt …";
                case "coverage" -> "Die Belege werden mit der Antwort abgeglichen …";
                case "ground" -> "Die Auswertung wird unabhängig geprüft …";
                default -> null;
            };
            case DATA_RESTORE, DATA_DELETE, DATASET -> null;
        };
    }
}
