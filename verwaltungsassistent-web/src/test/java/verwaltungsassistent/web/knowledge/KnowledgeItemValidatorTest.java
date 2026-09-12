package verwaltungsassistent.web.knowledge;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.model.ClaimVerification;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Validation gate: only structurally sound, provenance-backed, temporally known, entailed items become ACTIVE. */
class KnowledgeItemValidatorTest {

    private ClaimVerificationService claimVerifier;
    private KnowledgeItemValidator validator;

    @BeforeEach
    void setUp() {
        claimVerifier = mock(ClaimVerificationService.class);
        validator = new KnowledgeItemValidator(new ObjectMapper(), claimVerifier);
    }

    private void entail(ClaimVerification.Verdict verdict) {
        when(claimVerifier.verifyAllClaims(anyList(), anyList()))
                .thenReturn(List.of(List.of(new ClaimVerification("c", "e", verdict, 0.9, "r"))));
    }

    private StructuredKnowledgeItem threshold(UUID doc, String payload, LocalDate effectiveFrom) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "AV §55 LHO",
                payload, StructuredKnowledgeStatus.CANDIDATE, effectiveFrom, null,
                doc, 1, 3, 7, "Direktauftrag bis 10.000 Euro.", 0.95, "test", null, null, null);
    }

    @Test
    void validItemBecomesActive() {
        entail(ClaimVerification.Verdict.ENTAILED);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        LocalDate.of(2024, 1, 1)),
                false);
        assertTrue(o.isActive(), o.reason());
    }

    @Test
    void itemWithoutProvenanceIsRejected() {
        StructuredKnowledgeItem noProvenance = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "AV §55 LHO",
                "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                null, 0, null, null, "Auszug", 0.9, "test", null, null, null);
        KnowledgeItemValidator.Outcome o = validator.validate(noProvenance, false);
        assertEquals(StructuredKnowledgeStatus.REJECTED, o.status());
    }

    @Test
    void invalidNumbersAreRejected() {
        entail(ClaimVerification.Verdict.ENTAILED);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":\"abc\",\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        LocalDate.of(2024, 1, 1)),
                false);
        assertEquals(StructuredKnowledgeStatus.REJECTED, o.status());
    }

    @Test
    void overlappingBoundsAreRejected() {
        entail(ClaimVerification.Verdict.ENTAILED);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"A\"},{\"min\":5000,\"max\":20000,\"outcome\":\"B\"}]}",
                        LocalDate.of(2024, 1, 1)),
                false);
        assertEquals(StructuredKnowledgeStatus.REJECTED, o.status());
    }

    @Test
    void invalidDateRangeIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new StructuredKnowledgeItem(
                        UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "AV §55 LHO",
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        StructuredKnowledgeStatus.CANDIDATE,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2024, 1, 1),
                        UUID.randomUUID(), 1, 3, 7, "Direktauftrag bis 10.000 Euro.", 0.95,
                        "test", null, null, null));
    }

    @Test
    void unknownValidityNeverBecomesActive() {
        entail(ClaimVerification.Verdict.ENTAILED);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        null),
                false);
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, o.status(),
                "unknown effective date must remain RAG-only");
    }

    @Test
    void documentValidityBacksUnknownEffectiveFrom() {
        entail(ClaimVerification.Verdict.ENTAILED);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        null),
                true);
        assertTrue(o.isActive(), "document-level validity establishes temporal applicability");
    }

    @Test
    void failedEntailmentIsNotActive() {
        entail(ClaimVerification.Verdict.CONTRADICTED);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        LocalDate.of(2024, 1, 1)),
                false);
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, o.status());
    }

    @Test
    void unknownEntailmentVerdictIsNotActive() {
        entail(ClaimVerification.Verdict.UNKNOWN);
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(),
                        "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                        LocalDate.of(2024, 1, 1)),
                false);
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, o.status());
    }

    @Test
    void malformedPayloadJsonIsRejected() {
        KnowledgeItemValidator.Outcome o = validator.validate(
                threshold(UUID.randomUUID(), "{kein json", LocalDate.of(2024, 1, 1)), false);
        assertEquals(StructuredKnowledgeStatus.REJECTED, o.status());
    }

    @Test
    void ruleRequiresConditionAndConsequence() {
        entail(ClaimVerification.Verdict.ENTAILED);
        UUID doc = UUID.randomUUID();
        StructuredKnowledgeItem validRule = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "HR", "UrlVO Bln",
                "{\"condition\":\"5-Tage-Woche\",\"consequence\":\"30 Arbeitstage Urlaub\"}",
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                doc, 1, 2, 0, "30 Arbeitstage Urlaub bei 5-Tage-Woche.", 0.9, "test", null, null, null);
        assertTrue(validator.validate(validRule, false).isActive());

        StructuredKnowledgeItem missingConsequence = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "HR", "UrlVO Bln",
                "{\"condition\":\"5-Tage-Woche\"}",
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                doc, 1, 2, 0, "30 Arbeitstage Urlaub bei 5-Tage-Woche.", 0.9, "test", null, null, null);
        assertEquals(StructuredKnowledgeStatus.REJECTED, validator.validate(missingConsequence, false).status());
    }

    @Test
    void definitionStaysRagOnlyWhenNoTemporalBasis() {
        entail(ClaimVerification.Verdict.ENTAILED);
        StructuredKnowledgeItem definition = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.DEFINITION, "HR", "UrlVO Bln",
                "{\"text\":\"Urlaubsjahr ist das Kalenderjahr.\"}",
                StructuredKnowledgeStatus.CANDIDATE, null, null,
                UUID.randomUUID(), 1, 1, 0, "Urlaubsjahr ist das Kalenderjahr.", 0.9,
                "test", null, null, null);
        assertEquals(StructuredKnowledgeStatus.CANDIDATE, validator.validate(definition, false).status(),
                "definitions with unknown validity remain RAG-only");
    }
}
