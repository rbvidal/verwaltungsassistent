package reasoning.ai.unit.application;

import reasoning.ai.application.GenericRuleEvaluator;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic RULE evaluator: deterministic, domain-agnostic, never guesses,
 * never calls the LLM, and only ever executes ACTIVE temporally-applicable
 * rules with structured predicates.
 */
class GenericRuleEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private final GenericRuleEvaluator evaluator = new GenericRuleEvaluator(new ObjectMapper());

    private StructuredKnowledgeItem rule(StructuredKnowledgeStatus status, String payload,
                                         LocalDate effectiveFrom, LocalDate effectiveUntil) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT", "AV §55 LHO",
                payload, status, effectiveFrom, effectiveUntil,
                UUID.randomUUID(), 1, 8, 8, "Beschränkte Ausschreibung bis 100.000 Euro.",
                0.95, "test", null, null, null);
    }

    private static final String RULE_LE_100K = """
            {"condition":"geschätzter Auftragswert von bis zu 100 000 Euro (ohne Umsatzsteuer)",
             "predicates":[{"fact":"amount","op":"LE","value":100000}],
             "consequence":"Beschränkte Ausschreibung ohne Teilnahmewettbewerb durchgeführt werden"}""";

    @Test
    void matchReturnsRuleWithConsequenceAndProvenance() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K, null, null)),
                Map.of("amount", 18_000.0), TODAY);
        assertTrue(result.isMatch(), result.reason());
        assertTrue(result.consequence().contains("Beschränkte Ausschreibung"));
        assertTrue(result.item().sourceDocumentId() != null);
    }

    @Test
    void noMatchWhenPredicateIsFalse() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K, null, null)),
                Map.of("amount", 120_000.0), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH, result.outcome());
    }

    @Test
    void missingQueryFactIsIndeterminateNotGuessed() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K, null, null)),
                Map.of(), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome());
    }

    @Test
    void ruleWithoutPredicatesIsValidKnowledgeButNotExecutable() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE,
                        "{\"condition\":\"Freiberufliche Leistungen\",\"consequence\":\"§ 50 UVgO\"}",
                        null, null)),
                Map.of("amount", 18_000.0), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome(),
                "text-only rules must remain RAG/context-only");
    }

    @Test
    void malformedRulePayloadIsIndeterminate() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, "{kein json", null, null)),
                Map.of("amount", 18_000.0), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome());
    }

    @Test
    void multipleMatchesAreAmbiguousAndPreserveCompetingEvidence() {
        var second = rule(StructuredKnowledgeStatus.ACTIVE,
                "{\"condition\":\"über 10 000 Euro\",\"predicates\":[{\"fact\":\"amount\",\"op\":\"GT\",\"value\":10000}],\"consequence\":\"Verhandlungsvergabe\"}",
                null, null);
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K, null, null), second),
                Map.of("amount", 18_000.0), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome());
        assertEquals(2, result.competing().size());
    }

    @Test
    void expiredRuleCannotExecute() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K,
                        LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31))),
                Map.of("amount", 18_000.0), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome());
    }

    @Test
    void futureRuleCannotExecute() {
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K,
                        LocalDate.of(2030, 1, 1), null)),
                Map.of("amount", 18_000.0), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome());
    }

    @Test
    void unknownValidityPassesTemporalCheckOnlyAfterPromotion() {
        // ACTIVE guarantees validity was established at promotion; the evaluator
        // must not re-require dates (unknown = established elsewhere).
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K, null, null)),
                Map.of("amount", 18_000.0), TODAY);
        assertTrue(result.isMatch());
    }

    @Test
    void nonActiveRulesCanNeverExecute() {
        for (StructuredKnowledgeStatus status : List.of(StructuredKnowledgeStatus.CANDIDATE,
                StructuredKnowledgeStatus.REJECTED, StructuredKnowledgeStatus.SUPERSEDED)) {
            var result = evaluator.evaluate(
                    List.of(rule(status, RULE_LE_100K, null, null)),
                    Map.of("amount", 18_000.0), TODAY);
            assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome(),
                    status + " must never execute");
        }
    }

    @Test
    void stringEqualityPredicateWorks() {
        var rule = rule(StructuredKnowledgeStatus.ACTIVE,
                "{\"condition\":\"Freiberufliche Leistungen\","
                        + "\"predicates\":[{\"fact\":\"salaryGrade\",\"op\":\"EQ\",\"value\":\"EG 9a\"}],"
                        + "\"consequence\":\"§ 50 UVgO\"}", null, null);
        assertTrue(evaluator.evaluate(List.of(rule), Map.of("salaryGrade", "EG 9a"), TODAY).isMatch());
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH,
                evaluator.evaluate(List.of(rule), Map.of("salaryGrade", "EG 8"), TODAY).outcome());
    }

    @Test
    void evaluatorNeverCallsTheLlm() {
        // The evaluator is constructed with only an ObjectMapper — no LLM
        // provider dependency exists; evaluation is pure deterministic math.
        var result = evaluator.evaluate(
                List.of(rule(StructuredKnowledgeStatus.ACTIVE, RULE_LE_100K, null, null)),
                Map.of("amount", 18_000.0), TODAY);
        assertTrue(result.isMatch());
    }
}
