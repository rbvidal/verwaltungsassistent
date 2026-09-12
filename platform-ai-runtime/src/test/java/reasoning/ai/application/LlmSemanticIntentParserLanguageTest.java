package reasoning.ai.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused tests for the LLM language-field normalization: tolerant of common
 * model output variants, but only the supported answer-language set
 * (de/en/pt/fr) may pass — anything unrecognized must yield null.
 */
class LlmSemanticIntentParserLanguageTest {

    // ── English ──

    @Test
    void englishBareCode() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("en"));
    }

    @Test
    void englishUppercase() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("EN"));
    }

    @Test
    void englishWhitespacePadded() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage(" en "));
    }

    @Test
    void englishRegionCode() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("en-US"));
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("en-GB"));
    }

    @Test
    void englishDisplayName() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("English"));
    }

    // ── Portuguese ──

    @Test
    void portugueseBareCode() {
        assertEquals("pt", LlmSemanticIntentParser.normalizeLanguage("pt"));
    }

    @Test
    void portugueseRegionCode() {
        assertEquals("pt", LlmSemanticIntentParser.normalizeLanguage("pt-PT"));
    }

    @Test
    void portugueseDisplayName() {
        assertEquals("pt", LlmSemanticIntentParser.normalizeLanguage("Portuguese"));
    }

    // ── French ──

    @Test
    void frenchBareCode() {
        assertEquals("fr", LlmSemanticIntentParser.normalizeLanguage("fr"));
    }

    @Test
    void frenchRegionCode() {
        assertEquals("fr", LlmSemanticIntentParser.normalizeLanguage("fr-FR"));
    }

    @Test
    void frenchDisplayName() {
        assertEquals("fr", LlmSemanticIntentParser.normalizeLanguage("French"));
    }

    // ── German ──

    @Test
    void germanBareCode() {
        assertEquals("de", LlmSemanticIntentParser.normalizeLanguage("de"));
    }

    @Test
    void germanRegionCode() {
        assertEquals("de", LlmSemanticIntentParser.normalizeLanguage("de-DE"));
    }

    @Test
    void germanDisplayName() {
        assertEquals("de", LlmSemanticIntentParser.normalizeLanguage("German"));
    }

    // ── Quoted variants ──

    @Test
    void doubleQuotedCode() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("\"en\""));
    }

    @Test
    void singleQuotedCode() {
        assertEquals("fr", LlmSemanticIntentParser.normalizeLanguage("'fr'"));
    }

    @Test
    void nativeDisplayNames() {
        assertEquals("de", LlmSemanticIntentParser.normalizeLanguage("deutsch"));
        assertEquals("fr", LlmSemanticIntentParser.normalizeLanguage("français"));
        assertEquals("pt", LlmSemanticIntentParser.normalizeLanguage("português"));
    }

    @Test
    void displayNameWithRegion() {
        assertEquals("en", LlmSemanticIntentParser.normalizeLanguage("English-US"));
    }

    // ── Unrecognized values must yield null ──

    @Test
    void nullInput() {
        assertNull(LlmSemanticIntentParser.normalizeLanguage(null));
    }

    @Test
    void emptyInput() {
        assertNull(LlmSemanticIntentParser.normalizeLanguage(""));
    }

    @Test
    void whitespaceOnlyInput() {
        assertNull(LlmSemanticIntentParser.normalizeLanguage("   "));
    }

    @Test
    void unknownCode() {
        assertNull(LlmSemanticIntentParser.normalizeLanguage("xyz"));
    }

    @Test
    void unsupportedLanguageName() {
        assertNull(LlmSemanticIntentParser.normalizeLanguage("Esperanto"));
    }

    @Test
    void twoLetterCodeOutsideSupportedSet() {
        assertNull(LlmSemanticIntentParser.normalizeLanguage("it"));
        assertNull(LlmSemanticIntentParser.normalizeLanguage("es"));
    }
}
