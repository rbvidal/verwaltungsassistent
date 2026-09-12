package verwaltungsassistent.web.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extraction prompt must teach the exact RULE payload shape with a concrete
 * German regulatory example and forbid {"text": ...} for RULE — the validator
 * stays strict and must never be weakened to accept narrative RULE payloads.
 */
class KnowledgeExtractionPromptTest {

    private String prompt() {
        return KnowledgeExtractionPrompt.build(
                "AV zu Paragraph 55 LHO Berlin", "PDF", "Direktauftrag bis 10.000 Euro.");
    }

    @Test
    void promptTeachesExactRuleShape() {
        String p = prompt();
        assertTrue(p.contains("\"condition\""), "RULE condition field must be in the prompt");
        assertTrue(p.contains("\"consequence\""), "RULE consequence field must be in the prompt");
        assertTrue(p.contains("GENAU zwei Felder"), "prompt must require exactly two RULE fields");
    }

    @Test
    void promptContainsConcreteGermanRegulatoryExample() {
        String p = prompt();
        assertTrue(p.contains("AV §55 LHO"), "example key must be a real regulation name");
        assertTrue(p.contains("Auftragswert unter 10.000 Euro"), "condition example missing");
        assertTrue(p.contains("Direktauftrag mit Vergabevermerk"), "consequence example missing");
    }

    @Test
    void promptExplicitlyForbidsRuleTextPayload() {
        String p = prompt();
        assertTrue(p.contains("NIE"), "explicit prohibition marker missing");
        assertTrue(p.contains("\"text\""), "the forbidden RULE payload shape must be named");
        assertTrue(p.contains("niemals RULE mit"), "fallback instruction missing");
    }

    @Test
    void promptEnforcesCanonicalFactVocabulary() {
        String p = prompt();
        assertTrue(p.contains("KANONISCHEN Liste"), "canonical vocabulary must be explicit");
        assertTrue(p.contains("\"amount\""), "amount must be listed as the monetary fact");
        assertTrue(p.contains("Auftragswert"), "German wording must be mapped to amount");
        assertTrue(p.contains("UNZULAESSIG"), "arbitrary fact names must be forbidden");
        assertFalse(p.contains("\"cost\""), "non-canonical fact names must not appear as allowed");
    }

    @Test
    void promptDefinesSemanticRoleOfNumbers() {
        String p = prompt();
        assertTrue(p.contains("SEMANTISCHE ROLLE"), "semantic-role rules must be explicit");
        assertTrue(p.contains("GELDGRENZE"), "monetary caps must be distinguished from boundaries");
        assertTrue(p.contains("hoechstens X Euro"), "cap phrasing must be named");
    }

    @Test
    void promptTreatsRateAsRateNotBoundary() {
        String p = prompt();
        assertTrue(p.contains("20 Cent je Kilometer"), "the rate example must be present");
        assertTrue(p.contains("KEINE Kilometer-Grenze"), "a rate must never become a km boundary");
        assertTrue(p.contains("Euro/km"), "unit-conversion prohibition must be explicit");
    }

    @Test
    void promptForbidsUnitRelationshipInference() {
        String p = prompt();
        assertTrue(p.contains("EUR <-> km"), "EUR↔km inference must be forbidden");
        assertTrue(p.contains("DERSELBEN semantischen Groesse"),
                "min/max must bound the same semantic quantity");
        assertTrue(p.contains("NIE ein falsches THRESHOLD erzwingen"),
                "omit-over-force rule must be present");
    }

    @Test
    void promptShowsCorrectBrkgPattern() {
        String p = prompt();
        assertTrue(p.contains("130 Euro"), "the BRKG cap example must be present");
        assertTrue(p.contains("hoechstens 130 Euro"), "the consequence must keep the cap semantics");
        assertFalse(p.contains("\"max\":130"), "no THRESHOLD with the cap as a distance bound may be shown");
    }

    @Test
    void promptDirectsNarrativeTextAwayFromRule() {
        String p = prompt();
        assertTrue(p.contains("DEFINITION oder EXCEPTION"),
                "narrative text must be directed to DEFINITION/EXCEPTION");
        assertFalse(p.contains("{\"text\":\"...\"},"),
                "no RULE-with-text example may be shown as a template");
    }
}
