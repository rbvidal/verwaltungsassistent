package reasoning.ai.application;

import reasoning.ai.model.DecisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that buildExplanationPrompt produces a structurally sound prompt
 * where the RuleEngine decision is fully exposed as structured data and the
 * LLM is constrained to explain-only.
 */
class AiServiceExplanationPromptTest {

    // ── FRAGE section ──

    @Test
    void shouldIncludeQuestionInPrompt() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        assertTrue(prompt.contains("FRAGE"), "Must contain FRAGE section header");
        assertTrue(prompt.contains("Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?"),
                "Must contain the original question verbatim");
        assertTrue(prompt.contains("18.000"), "18.000 must appear in the question");
    }

    // ── ANTWORT DES REGELSYSTEMS section ──

    @Test
    void shouldHaveDeterministicAnswerSection() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        assertTrue(prompt.contains("ANTWORT DES REGELSYSTEMS"),
                "Must contain ANTWORT DES REGELSYSTEMS section");
        assertTrue(prompt.contains("Beschränkte Ausschreibung"),
                "ANTWORT must state the procedure");

        // Verify ANTWORT appears between FRAGE and DETAILS
        int frageIdx = prompt.indexOf("FRAGE");
        int antwortIdx = prompt.indexOf("ANTWORT DES REGELSYSTEMS");
        int detailsIdx = prompt.indexOf("DETAILS DES REGELSYSTEMS");
        assertTrue(frageIdx < antwortIdx, "FRAGE must come before ANTWORT");
        assertTrue(antwortIdx < detailsIdx, "ANTWORT must come before DETAILS");
    }

    @Test
    void shouldNotUseJavaDoubleFormatting() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        assertFalse(prompt.contains("18000.0"),
                "Java double format 18000.0 must not appear in prompt");
        assertFalse(prompt.contains("18000"),
                "Raw 18000 without formatting must not appear (except inside question)");

        // Betrag should use German formatting: 18.000,00 €
        assertTrue(prompt.contains("Betrag: 18.000,00"),
                "Betrag must use German formatting with thousand separators");
    }

    @Test
    void shouldFormatSalaryAmountInGermanFormat() {
        var decision = new DecisionResult.SalaryDecision(
                "EG 9a Stufe 3 = 3900.00 €",
                "TV-L Entgelttabelle 2025, gültig ab 01.02.2025",
                "TV-L Entgelttabellen 2025",
                0.99, "EG 9a", 3, 3900.00,
                "TV-L",
                "2025-02-01",
                "Tarifgemeinschaft deutscher Länder (TdL)");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Wie hoch ist das Gehalt für EG 9a Stufe 3?",
                decision);

        assertTrue(prompt.contains("3.900,00 €"),
                "Salary amount must use German formatting 3.900,00 €");
        assertFalse(prompt.contains("3900.0"),
                "Java double 3900.0 must not appear in prompt");
    }

    // ── DETAILS DES REGELSYSTEMS section ──

    @Test
    void shouldIncludeAllStructuredFields() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        assertTrue(prompt.contains("DETAILS DES REGELSYSTEMS"),
                "Must contain DETAILS section header");
        assertTrue(prompt.contains("Betrag:"), "Must contain Betrag field");
        assertTrue(prompt.contains("Kategorie: Lieferung/Dienstleistung"), "Must contain Kategorie");
        assertTrue(prompt.contains("Verfahren: Beschränkte Ausschreibung"),
                "Must contain Verfahren as separate label");
        assertTrue(prompt.contains("Zusätzliche Pflichten:"),
                "Must use Zusätzliche Pflichten (not Weitere Anforderungen)");
        assertTrue(prompt.contains("Angewendete Schwelle:"),
                "Must contain Angewendete Schwelle label");
        assertTrue(prompt.contains("Rechtsgrundlage: AV zu Paragraph 55 LHO Berlin"),
                "Must contain Rechtsgrundlage");
        assertTrue(prompt.contains("Behörde: Senatsverwaltung für Finanzen"), "Must contain Behörde");
    }

    @Test
    void shouldUseZusaetzlichePflichtenLabel() {
        var decision = procurementDecision(5_000.0, "Direktauftrag",
                List.of("Vergabevermerk erforderlich", "Drei Vergleichsangebote"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "IT-Dienstleistung über 5.000 € — Direktauftrag möglich?",
                decision);

        assertTrue(prompt.contains("Zusätzliche Pflichten:"),
                "Must use 'Zusätzliche Pflichten' label");
        assertFalse(prompt.contains("Weitere Anforderungen"),
                "Must not contain old 'Weitere Anforderungen' label");
    }

    @Test
    void shouldUseAngewendeteSchwelleLabel() {
        var decision = procurementDecision(50_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich eine Beschaffung über 50.000 € freihändig vergeben?",
                decision);

        assertTrue(prompt.contains("Angewendete Schwelle:"),
                "Must contain 'Angewendete Schwelle' label");
    }

    @Test
    void shouldKeep25kOnlyUnderZusaetzlichePflichten() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        // 25.000 € appears only under Zusätzliche Pflichten
        int zusaetzlicheIdx = prompt.indexOf("Zusätzliche Pflichten:");
        assertTrue(zusaetzlicheIdx > 0, "Zusätzliche Pflichten section must exist");

        String beforeZusaetzliche = prompt.substring(0, zusaetzlicheIdx);
        assertFalse(beforeZusaetzliche.contains("25.000"),
                "25.000 must NOT appear before Zusätzliche Pflichten section");

        assertTrue(prompt.contains("  - Ex-post-Veröffentlichung (ab 25.000 €)"),
                "25.000 € requirement must be listed under Zusätzliche Pflichten as a bullet");
    }

    // ── IHRE AUFGABE section ──

    @Test
    void shouldContainStrengthenedSystemInstruction() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        assertTrue(prompt.contains("IHRE AUFGABE"), "Must contain IHRE AUFGABE section");

        // Strengthened system instruction
        assertTrue(prompt.contains("deterministisch"),
                "Must state the decision was made deterministically");
        assertTrue(prompt.contains("verbindlich"),
                "Must state all details are binding");
        assertTrue(prompt.contains("ausschließlich"),
                "Must state the task is exclusively explanation");

        // Prohibitions
        assertTrue(prompt.contains("kein anderes Verfahren auswählen"),
                "Must forbid selecting a different procedure");
        assertTrue(prompt.contains("keine andere Schwelle anwenden"),
                "Must forbid applying a different threshold");
        assertTrue(prompt.contains("keine eigenen Berechnungen durchführen"),
                "Must forbid own calculations");
        assertTrue(prompt.contains("keine eigene juristische Bewertung vornehmen"),
                "Must forbid own legal assessment");
        assertTrue(prompt.contains("keine zusätzlichen Vorschriften erfinden"),
                "Must forbid inventing regulations");

        // Explanation framing
        assertTrue(prompt.contains("genau diese Entscheidung getroffen hat"),
                "Must frame the task as explaining why this exact decision was made");
    }

    // ── Prompt flow arrows ──

    @Test
    void shouldContainPromptFlowWithArrows() {
        var decision = procurementDecision(18_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich einen IT-Auftrag über 18.000 € freihändig vergeben?",
                decision);

        // Verify the four main sections exist in order
        int frageIdx = prompt.indexOf("FRAGE");
        int antwortIdx = prompt.indexOf("ANTWORT DES REGELSYSTEMS");
        int detailsIdx = prompt.indexOf("DETAILS DES REGELSYSTEMS");
        int aufgabeIdx = prompt.indexOf("IHRE AUFGABE");

        assertTrue(frageIdx >= 0, "FRAGE section must exist");
        assertTrue(antwortIdx > frageIdx, "ANTWORT must come after FRAGE");
        assertTrue(detailsIdx > antwortIdx, "DETAILS must come after ANTWORT");
        assertTrue(aufgabeIdx > detailsIdx, "IHRE AUFGABE must come after DETAILS");

        // Arrow separators
        String betweenFrageAntwort = prompt.substring(frageIdx, antwortIdx);
        assertTrue(betweenFrageAntwort.contains("↓"), "↓ arrow must separate FRAGE from ANTWORT");

        String betweenAntwortDetails = prompt.substring(antwortIdx, detailsIdx);
        assertTrue(betweenAntwortDetails.contains("↓"), "↓ arrow must separate ANTWORT from DETAILS");

        String betweenDetailsAufgabe = prompt.substring(detailsIdx, aufgabeIdx);
        assertTrue(betweenDetailsAufgabe.contains("↓"), "↓ arrow must separate DETAILS from IHRE AUFGABE");
    }

    // ── Non-procurement decision ──

    @Test
    void shouldHandleNonProcurementDecision() {
        var decision = new DecisionResult.SalaryDecision(
                "EG 9a Stufe 3 = 3900.00 €",
                "TV-L Entgelttabelle 2025, gültig ab 01.02.2025",
                "TV-L Entgelttabellen 2025",
                0.99, "EG 9a", 3, 3900.00,
                "TV-L",
                "2025-02-01",
                "Tarifgemeinschaft deutscher Länder (TdL)");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Wie hoch ist das Gehalt für EG 9a Stufe 3?",
                decision);

        assertTrue(prompt.contains("FRAGE"), "Salary prompt must contain FRAGE");
        assertTrue(prompt.contains("ANTWORT DES REGELSYSTEMS"), "Must contain ANTWORT section");
        assertTrue(prompt.contains("EG 9a Stufe 3"), "ANTWORT must contain grade and step");
        assertTrue(prompt.contains("DETAILS DES REGELSYSTEMS"), "Must contain DETAILS section");
        assertTrue(prompt.contains("Entgeltgruppe: EG 9a"), "Must contain grade");
        assertTrue(prompt.contains("Stufe: 3"), "Must contain step");
        assertTrue(prompt.contains("Monatsbetrag: 3.900,00 €"),
                "Monthly amount must use German formatting");
        assertTrue(prompt.contains("Tarifvertrag: TV-L"), "Must contain payScale");
        assertTrue(prompt.contains("Rechtsgrundlage: TV-L Entgelttabellen 2025"), "Must contain source");
        assertTrue(prompt.contains("IHRE AUFGABE"), "Must contain AUFGABE section");
    }

    // ── Anti-concatenation ──

    @Test
    void shouldNotConcatenateProcedureAndRequirements() {
        var decision = procurementDecision(50_000.0, "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)", "EU-Schwellenwerte prüfen"),
                "Lieferung/Dienstleistung");
        var aiService = newAiService();

        String prompt = aiService.buildExplanationPrompt(
                "Kann ich eine Beschaffung über 50.000 € freihändig vergeben?",
                decision);

        // Verfahren must be a separate labeled entry, not concatenated
        assertTrue(prompt.contains("Verfahren: Beschränkte Ausschreibung"),
                "Verfahren must appear as a separate labeled entry");

        // Each requirement must be bulleted under Zusätzliche Pflichten
        assertTrue(prompt.contains("  - Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Each requirement must be a separate bullet");
        assertTrue(prompt.contains("  - EU-Schwellenwerte prüfen"),
                "Multiple requirements must each be bulleted");

        // Old-style concatenation must not appear
        assertFalse(prompt.contains("Beschränkte Ausschreibung. Ex-post-Veröffentlichung"),
                "Procedure and requirements must NOT be concatenated with a period");
    }

    // ── Answer language (StructuredIntent.language propagation) ──

    @Test
    void shouldInstructEnglishAnswerLanguage() {
        var aiService = newAiService();
        String prompt = aiService.buildExplanationPrompt(
                "What is the meal allowance for a 12-hour business trip?",
                procurementDecision(8000.0, "Direktauftrag", List.of(), "Lieferung/Dienstleistung"),
                "en");

        assertTrue(prompt.contains("ANSWER LANGUAGE: English."),
                "EN question must produce an English answer-language instruction");
        assertTrue(prompt.contains("The entire answer must be written in this language."));
    }

    @Test
    void shouldInstructPortugueseAnswerLanguage() {
        var aiService = newAiService();
        String prompt = aiService.buildExplanationPrompt(
                "Qual é o subsídio de alimentação para uma viagem de trabalho de 12 horas?",
                procurementDecision(8000.0, "Direktauftrag", List.of(), "Lieferung/Dienstleistung"),
                "pt");
        assertTrue(prompt.contains("ANSWER LANGUAGE: Português."));
    }

    @Test
    void shouldInstructFrenchAnswerLanguage() {
        var aiService = newAiService();
        String prompt = aiService.buildExplanationPrompt(
                "Quelle est l'indemnité de repas pour un voyage professionnel de 12 heures ?",
                procurementDecision(8000.0, "Direktauftrag", List.of(), "Lieferung/Dienstleistung"),
                "fr");
        assertTrue(prompt.contains("ANSWER LANGUAGE: Français."));
    }

    @Test
    void shouldDefaultToGermanWhenLanguageNull() {
        var aiService = newAiService();
        String prompt = aiService.buildExplanationPrompt(
                "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?",
                procurementDecision(8000.0, "Direktauftrag", List.of(), "Lieferung/Dienstleistung"));
        assertTrue(prompt.contains("Antwortsprache: Deutsch."));
    }

    @Test
    void shouldPreserveAuthoritativeTerminologyInstruction() {
        var aiService = newAiService();
        String prompt = aiService.buildExplanationPrompt(
                "What is the meal allowance for a 12-hour business trip?",
                procurementDecision(8000.0, "Direktauftrag", List.of(), "Lieferung/Dienstleistung"),
                "en");
        assertTrue(prompt.contains("official designations"),
                "Must instruct preserving authoritative terms");
        assertTrue(prompt.contains("BRKG") && prompt.contains("TV-L"),
                "Must name the authoritative abbreviations");
        assertTrue(prompt.contains("Direktauftrag"),
                "The deterministic procedure value must remain in the prompt");
    }

    // ── Helpers ──

    private static AiService newAiService() {
        return new AiService(null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static DecisionResult.ProcurementDecision procurementDecision(
            double amount, String procedure, List<String> requirements, String category) {
        return new DecisionResult.ProcurementDecision(
                procedure + ". " + String.join("; ", requirements),
                "10.000 € bis 100.000 €",
                "AV zu Paragraph 55 LHO Berlin",
                0.98, amount, procedure, requirements,
                category,
                "2024-01-01",
                "Senatsverwaltung für Finanzen");
    }
}
