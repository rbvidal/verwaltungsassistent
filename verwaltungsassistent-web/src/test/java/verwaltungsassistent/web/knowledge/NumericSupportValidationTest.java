package verwaltungsassistent.web.knowledge;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Numeric-support gate: structured numeric values (THRESHOLD bounds, RULE
 * predicate values) must be explicitly supported by the source excerpt.
 * LLM-invented numbers keep the item CANDIDATE — never executable.
 */
class NumericSupportValidationTest {

    private KnowledgeItemValidator validator;

    @BeforeEach
    void setUp() {
        ClaimVerificationService claimVerifier = mock(ClaimVerificationService.class);
        when(claimVerifier.verifyAllClaims(anyList(), anyList()))
                .thenReturn(List.of(List.of(new reasoning.ai.model.ClaimVerification(
                        "c", "e", reasoning.ai.model.ClaimVerification.Verdict.ENTAILED,
                        0.9, "r"))));
        validator = new KnowledgeItemValidator(new ObjectMapper(), claimVerifier);
    }

    private StructuredKnowledgeItem threshold(String excerpt, String boundsJson,
                                              LocalDate effectiveFrom) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "TRAVEL", "BRKG § 5",
                "{\"bounds\":" + boundsJson + "}", StructuredKnowledgeStatus.CANDIDATE,
                effectiveFrom, null, UUID.randomUUID(), 1, 11, 11, excerpt,
                0.95, "test", null, null, null);
    }

    @Test
    void explicitlySupportedBoundaryIsAccepted() {
        var item = threshold(
                "Die Wegstreckenentschädigung beträgt 20 Cent pro Kilometer. Bei 130 Kilometern gelten besondere Regelungen.",
                "[{\"min\":0,\"max\":130,\"outcome\":\"20 Cent pro Kilometer\"}]",
                LocalDate.of(2024, 1, 1));
        assertTrue(validator.validate(item, false).isActive(),
                "an explicitly stated 130 boundary is source-supported");
    }

    @Test
    void inventedNumericBoundaryIsPreventedFromBecomingExecutable() {
        // The demonstrated Stage-4 fabrication: no 130/150 in the source text.
        var item = threshold(
                "Die Wegstreckenentschädigung beträgt 20 Cent pro Kilometer.",
                "[{\"min\":0,\"max\":130,\"outcome\":\"20 Cent pro Kilometer\"},"
                        + "{\"min\":130,\"max\":150,\"outcome\":\"130 Euro\"}]",
                LocalDate.of(2024, 1, 1));
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, validator.validate(item, false).status(),
                "an unsupported numeric boundary must never become ACTIVE");
    }

    @Test
    void rateValueNotPresentInTextIsRejectedAsBoundary() {
        // A boundary number that never appears in the text (even as another
        // quantity) is unsupported. Note: containment verifies the NUMBER, not
        // its unit — "20" in "20 Cent" supports max=20; unit confusion is
        // mitigated at the prompt level and by entailment, not by containment.
        var item = threshold(
                "Die Wegstreckenentschädigung beträgt 20 Cent pro Kilometer.",
                "[{\"min\":0,\"max\":15,\"outcome\":\"20 Cent pro Kilometer\"}]",
                LocalDate.of(2024, 1, 1));
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, validator.validate(item, false).status(),
                "a boundary value absent from the text must never become ACTIVE");
    }

    @Test
    void explicitlyStatedKilometreBoundaryIsAccepted() {
        var item = threshold(
                "Für Fahrten bis 130 Kilometer gilt ein besonderer Satz.",
                "[{\"min\":0,\"max\":130,\"outcome\":\"Besonderer Satz\"}]",
                LocalDate.of(2024, 1, 1));
        assertTrue(validator.validate(item, false).isActive());
    }

    @Test
    void openEndedBoundaryStaysOpenAndIsAccepted() {
        var item = threshold(
                "Für Fahrten ab 130 Kilometern wird ein Zuschlag gewährt.",
                "[{\"min\":130,\"max\":null,\"outcome\":\"Zuschlag\"}]",
                LocalDate.of(2024, 1, 1));
        assertTrue(validator.validate(item, false).isActive(),
                "null max must remain open, not invented");
    }

    @Test
    void germanFormattedNumbersNormalizeCorrectly() {
        var item = threshold(
                "Die Obergrenze beträgt 100.000,50 Euro.",
                "[{\"min\":0,\"max\":100000.50,\"outcome\":\"Grenze\"}]",
                LocalDate.of(2024, 1, 1));
        assertTrue(validator.validate(item, false).isActive(),
                "German 100.000,50 must match the semantic value 100000.50");
    }

    @Test
    void englishFormattedNumbersNormalizeCorrectly() {
        var item = threshold(
                "The limit is 100,000.50 Euro.",
                "[{\"min\":0,\"max\":100000.50,\"outcome\":\"Limit\"}]",
                LocalDate.of(2024, 1, 1));
        assertTrue(validator.validate(item, false).isActive(),
                "English 100,000.50 must match 100000.50");
    }

    @Test
    void rulePredicateValueMustBeSupported() {
        var item = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT", "AV §55 LHO",
                "{\"condition\":\"bis 10 000 Euro\","
                        + "\"predicates\":[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":10000}],"
                        + "\"consequence\":\"Direktauftrag\"}",
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 8, 8, "Direktauftrag bis 10 000 Euro.", 0.95,
                "test", null, null, null);
        assertTrue(validator.validate(item, false).isActive(),
                "German 10 000 in the excerpt supports the value 10000");

        var fabricated = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT", "AV §55 LHO",
                "{\"condition\":\"bis 99 999\","
                        + "\"predicates\":[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":99999}],"
                        + "\"consequence\":\"Direktauftrag\"}",
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 8, 8, "Direktauftrag bis 10 000 Euro.", 0.95,
                "test", null, null, null);
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, validator.validate(fabricated, false).status());
    }

    @Test
    void normalizeNumberHandlesGermanAndEnglishGrouping() {
        assertEquals(new BigDecimal("1000"), KnowledgeItemValidator.normalizeNumber("1.000"));
        assertEquals(new BigDecimal("1000"), KnowledgeItemValidator.normalizeNumber("1,000"));
        assertEquals(new BigDecimal("100000.50"), KnowledgeItemValidator.normalizeNumber("100.000,50"));
        assertEquals(new BigDecimal("100000.50"), KnowledgeItemValidator.normalizeNumber("100,000.50"));
        assertEquals(new BigDecimal("0.35"), KnowledgeItemValidator.normalizeNumber("0,35"));
        assertEquals(new BigDecimal("3.5"), KnowledgeItemValidator.normalizeNumber("3.5"));
        assertEquals(new BigDecimal("130"), KnowledgeItemValidator.normalizeNumber("130"));
        assertEquals(new BigDecimal("100000"), KnowledgeItemValidator.normalizeNumber("100 000"));
    }

    @Test
    void nonNumericKindsAreNotAffected() {
        var item = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.DEFINITION, "HR", "UrlVO",
                "{\"text\":\"Urlaubsjahr ist das Kalenderjahr.\"}",
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 1, 0, "Urlaubsjahr ist das Kalenderjahr.", 0.9,
                "test", null, null, null);
        assertTrue(validator.validate(item, false).isActive(),
                "DEFINITION has no numeric fields — the gate must not apply");
    }
}
