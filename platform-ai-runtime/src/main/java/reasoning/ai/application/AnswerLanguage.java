package reasoning.ai.application;

import java.util.List;

/**
 * Presentation-language helper for answer generation.
 *
 * <p>Maps the StructuredIntent ISO 639-1 code to the display name and the
 * output-format scaffolding used in generator instructions. {@code null} or
 * unknown codes fall back to German — the application's existing default
 * language. Language controls presentation only; authoritative knowledge,
 * legal references and terminology are never translated here.
 */
final class AnswerLanguage {

    private static final List<String> SUPPORTED = List.of("en", "pt", "fr");

    private AnswerLanguage() {}

    static String displayName(String iso) {
        if (iso == null) return "Deutsch";
        return switch (iso.toLowerCase()) {
            case "en" -> "English";
            case "pt" -> "Português";
            case "fr" -> "Français";
            default -> "Deutsch";
        };
    }

    /** True when the answer should use the German default instruction set. */
    static boolean germanDefault(String iso) {
        return iso == null || !SUPPORTED.contains(iso.toLowerCase());
    }

    /** Output-format line for the rule-engine explanation prompt. */
    static String ruleFormatInstruction(String iso) {
        if (iso == null) return "Format: KURZANTWORT (1 Satz), ENTSCHEIDUNG (2-3 Sätze), RECHTSGRUNDLAGEN.\n";
        return switch (iso.toLowerCase()) {
            case "en" -> "Format: SHORT ANSWER (1 sentence), DECISION (2-3 sentences), LEGAL BASIS.\n";
            case "pt" -> "Formato: RESPOSTA CURTA (1 frase), DECISÃO (2-3 frases), FUNDAMENTO LEGAL.\n";
            case "fr" -> "Format : RÉPONSE COURTE (1 phrase), DÉCISION (2-3 phrases), BASE LÉGALE.\n";
            default -> "Format: KURZANTWORT (1 Satz), ENTSCHEIDUNG (2-3 Sätze), RECHTSGRUNDLAGEN.\n";
        };
    }

    /** Output-format block for the retrieval/generation prompt. */
    static String retrievalFormatBlock(String iso) {
        if (iso == null) return """
            ANTWORTFORMAT:
            KURZANTWORT
            (Ein Satz)

            ENTSCHEIDUNG
            (Empfehlung, 1-2 Sätze)

            RECHTSGRUNDLAGE
            - [Dokument], [Abschnitt]

            VERFAHREN
            (Konkretes Verfahren oder: Kein Verfahren erforderlich)

            NÄCHSTER SCHRITT
            (Eine konkrete Handlung)

            KURZANTWORT und ENTSCHEIDUNG zuerst. Dies ist keine Rechtsberatung.
            """;
        return switch (iso.toLowerCase()) {
            case "en" -> """
                ANSWER FORMAT:
                SHORT ANSWER
                (One sentence)

                DECISION
                (Recommendation, 1-2 sentences)

                LEGAL BASIS
                - [Document], [Section]

                PROCEDURE
                (Specific procedure or: No procedure required)

                NEXT STEP
                (One concrete action)

                SHORT ANSWER and DECISION first. This is not legal advice.
                """;
            case "pt" -> """
                FORMATO DA RESPOSTA:
                RESPOSTA CURTA
                (Uma frase)

                DECISÃO
                (Recomendação, 1-2 frases)

                FUNDAMENTO LEGAL
                - [Documento], [Seção]

                PROCEDIMENTO
                (Procedimento concreto ou: Nenhum procedimento necessário)

                PRÓXIMO PASSO
                (Uma ação concreta)

                RESPOSTA CURTA e DECISÃO primeiro. Isto não é aconselhamento jurídico.
                """;
            case "fr" -> """
                FORMAT DE LA RÉPONSE :
                RÉPONSE COURTE
                (Une phrase)

                DÉCISION
                (Recommandation, 1-2 phrases)

                BASE LÉGALE
                - [Document], [Section]

                PROCÉDURE
                (Procédure concrète ou : aucune procédure nécessaire)

                PROCHAINE ÉTAPE
                (Une action concrète)

                RÉPONSE COURTE et DÉCISION d'abord. Ceci n'est pas un conseil juridique.
                """;
            default -> """
                ANTWORTFORMAT:
                KURZANTWORT
                (Ein Satz)

                ENTSCHEIDUNG
                (Empfehlung, 1-2 Sätze)

                RECHTSGRUNDLAGE
                - [Dokument], [Abschnitt]

                VERFAHREN
                (Konkretes Verfahren oder: Kein Verfahren erforderlich)

                NÄCHSTER SCHRITT
                (Eine konkrete Handlung)

                KURZANTWORT und ENTSCHEIDUNG zuerst. Dies ist keine Rechtsberatung.
                """;
        };
    }

    /**
     * The explain-only task instruction for the rule-engine prompt.
     * German mode uses the German block; non-German mode uses one neutral
     * English meta-instruction (the answer language is stated separately).
     */
    static String taskInstruction(String iso) {
        if (iso == null) return """
            IHRE AUFGABE

            Die Entscheidung wurde bereits deterministisch
            vom Regelsystem getroffen.
            Alle nachfolgenden Angaben sind verbindlich.
            Ihre Aufgabe besteht ausschließlich darin,
            die Entscheidung verständlich zu erklären.

            Sie dürfen

            - kein anderes Verfahren auswählen
            - keine andere Schwelle anwenden
            - keine eigenen Berechnungen durchführen
            - keine eigene juristische Bewertung vornehmen
            - keine zusätzlichen Vorschriften erfinden

            Erklären Sie, warum das Regelsystem
            genau diese Entscheidung getroffen hat.

            """;
        return """
            YOUR TASK

            The decision was already made deterministically
            by the rule system.
            All of the following details are binding.
            Your only task is to explain the decision understandably.

            You must not

            - choose a different procedure
            - apply a different threshold
            - perform your own calculations
            - make your own legal assessment
            - invent additional regulations

            Explain why the rule system made exactly this decision.

            """;
    }

    /** "No documents found" block (header + instruction) for the insufficient-evidence branch. */
    static String noDocumentsBlock(String iso) {
        if (iso == null) {
            return "KEINE DOKUMENTE GEFUNDEN.\n"
                + "Antwort: Die Wissensbasis enthält keine ausreichenden Informationen. Bitte folgende Dokumente ergänzen: [konkret benennen].\n";
        }
        return switch (iso.toLowerCase()) {
            case "en" -> "NO DOCUMENTS FOUND.\n"
                + "Answer: The knowledge base contains no sufficient information. Please add the following documents: [name them concretely].\n";
            case "pt" -> "NENHUM DOCUMENTO ENCONTRADO.\n"
                + "Resposta: A base de conhecimento não contém informações suficientes. Acrescente os seguintes documentos: [nomeie-os concretamente].\n";
            case "fr" -> "AUCUN DOCUMENT TROUVÉ.\n"
                + "Réponse : La base de connaissances ne contient pas d'informations suffisantes. Veuillez ajouter les documents suivants : [nommez-les concrètement].\n";
            default -> "KEINE DOKUMENTE GEFUNDEN.\n"
                + "Antwort: Die Wissensbasis enthält keine ausreichenden Informationen. Bitte folgende Dokumente ergänzen: [konkret benennen].\n";
        };
    }
}
