package reasoning.ai.unit.application;

import reasoning.ai.application.GenericRuleEvaluator;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit numeric and currency semantics of the generic RULE evaluator:
 * BigDecimal comparisons, inclusive vs exclusive upper bounds, currency as
 * semantic data, deterministic results, and no German locale assumptions.
 */
class RuleNumericSemanticsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private final GenericRuleEvaluator evaluator = new GenericRuleEvaluator(new ObjectMapper());

    private StructuredKnowledgeItem rule(String predicates, String consequence) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT", "AV §55 LHO",
                "{\"condition\":\"Bedingung\",\"predicates\":" + predicates + ",\"consequence\":\"" + consequence + "\"}",
                StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 8, 8, "Quelle.", 0.95, "test", null, null, null);
    }

    private GenericRuleEvaluator.RuleEvaluation eval(StructuredKnowledgeItem rule, Map<String, Object> facts) {
        return evaluator.evaluate(List.of(rule), facts, TODAY);
    }

    // ── Inclusive upper bound: "bis zu 100.000 Euro" → LE ──

    @Test
    void inclusiveUpperBoundMatchesAtAndBelowBoundary() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000}]", "Beschränkte Ausschreibung");
        assertTrue(eval(r, Map.of("amount", 99_999.99)).isMatch());
        assertTrue(eval(r, Map.of("amount", 100_000.00)).isMatch());
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH, eval(r, Map.of("amount", 100_000.01)).outcome());
    }

    @Test
    void exclusiveUpperBoundExcludesExactBoundary() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LT\",\"value\":100000}]", "Unter 100.000");
        assertTrue(eval(r, Map.of("amount", 99_999.99)).isMatch());
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH, eval(r, Map.of("amount", 100_000.00)).outcome());
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH, eval(r, Map.of("amount", 100_000.01)).outcome());
    }

    @Test
    void bigDecimalPrecisionDistinguishesBoundaryValues() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000}]", "X");
        // 100000.01 must never compare equal to 100000 (no float tolerance).
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH, eval(r, Map.of("amount", 100_000.01)).outcome());
        assertTrue(eval(r, Map.of("amount", 100_000.00)).isMatch());
    }

    // ── Currency is semantic data ──

    @Test
    void matchingCurrencyMatches() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000,\"currency\":\"EUR\"}]", "X");
        assertTrue(eval(r, Map.of("amount", 50_000.0, "currency", "EUR")).isMatch());
    }

    @Test
    void incompatibleCurrencyMustNotSilentlyMatch() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000,\"currency\":\"EUR\"}]", "X");
        assertEquals(GenericRuleEvaluator.Outcome.NO_MATCH,
                eval(r, Map.of("amount", 50_000.0, "currency", "USD")).outcome());
    }

    @Test
    void currencyAgnosticPredicateMatchesRegardless() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000}]", "X");
        assertTrue(eval(r, Map.of("amount", 50_000.0, "currency", "USD")).isMatch());
    }

    // ── Determinism ──

    @Test
    void sameInputYieldsIdenticalResult() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000}]", "Beschränkte Ausschreibung");
        var first = eval(r, Map.of("amount", 18_000.0));
        var second = eval(r, Map.of("amount", 18_000.0));
        assertEquals(first.outcome(), second.outcome());
        assertEquals(first.consequence(), second.consequence());
        assertEquals(first.item(), second.item());
        assertEquals(first, second);
    }

    // ── Locale independence ──

    @Test
    void evaluationIsIndependentOfDefaultLocale() {
        var r = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000}]", "X");
        GenericRuleEvaluator.RuleEvaluation reference = null;
        for (Locale locale : List.of(Locale.GERMANY, Locale.US, new Locale("pt", "BR"),
                new Locale("en", "IL"))) {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(locale);
                var result = eval(r, Map.of("amount", 100_000.01));
                assertNotEquals(GenericRuleEvaluator.Outcome.MATCH, result.outcome(),
                        "default locale must not change numeric semantics");
                if (reference == null) {
                    reference = eval(r, Map.of("amount", 18_000.0));
                } else {
                    assertEquals(reference, eval(r, Map.of("amount", 18_000.0)),
                            "locale must not affect the result");
                }
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    // ── Exception precedence (EXCEPTION > RULE) ──

    @Test
    void structuredExceptionOverridesMatchingRule() {
        var rule = rule("[{\"fact\":\"amount\",\"op\":\"LE\",\"value\":100000}]", "Beschränkte Ausschreibung");
        var exception = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.EXCEPTION, "PROCUREMENT", "AV §55 LHO",
                "{\"condition\":\"Sonderfall\",\"predicates\":[{\"fact\":\"amount\",\"op\":\"GT\",\"value\":90000}],\"consequence\":\"Verhandlungsvergabe als Ausnahme\"}",
                StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 10, 10, "Ausnahme.", 0.95, "test", null, null, null);

        GenericRuleEvaluator.RuleEvaluation result =
                evaluator.evaluate(List.of(rule, exception), Map.of("amount", 95_000.0), TODAY);

        assertTrue(result.isMatch());
        assertEquals(StructuredKnowledgeKind.EXCEPTION, result.item().kind(),
                "a matching structured exception must override the general rule");
        assertTrue(result.consequence().contains("Ausnahme"));
    }
}
