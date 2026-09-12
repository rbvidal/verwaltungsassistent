package reasoning.ai.unit.application;

import reasoning.ai.application.TableEvaluator;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic TABLE evaluation: one mechanism, no domain knowledge.
 * Salary (exact equality), travel (half-open hour bands), constant tables,
 * conflicts, temporal validity, ACTIVE-only, provenance.
 */
class TableEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private final TableEvaluator evaluator = new TableEvaluator(new ObjectMapper());

    private StructuredKnowledgeItem table(StructuredKnowledgeStatus status, String payload,
                                          LocalDate from, LocalDate until) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.TABLE, "TRAVEL", "Reisetabelle",
                payload, status, from, until,
                UUID.randomUUID(), 1, 3, 7, "Quelle.", 0.95, "test", null, null, null);
    }

    private static final String SALARY = """
            {"columns":["salaryGrade","salaryStep","monthlyAmount"],
             "rows":[["EG 9a",3,4117.53],["EG 13",1,5100.00]],
             "lookup":{"salaryGrade":"salaryGrade","salaryStep":"salaryStep"},
             "result":["monthlyAmount"]}""";

    private static final String TRAVEL_BANDS = """
            {"columns":["hoursMin","hoursMax","allowanceEur","description"],
             "rows":[[8,11,6,"Abwesenheit über 8 Stunden"],[11,24,12,"Abwesenheit über 11 Stunden"],[24,null,24,"24 Stunden"]],
             "lookup":{"hoursMin":"hours","hoursMax":"hours"},
             "result":["allowanceEur","description"]}""";

    @Test
    void exactEqualityLookupMatchesOneRow() {
        var result = evaluator.evaluate(List.of(table(StructuredKnowledgeStatus.ACTIVE, SALARY, null, null)),
                Map.of("salaryGrade", "EG 9a", "salaryStep", 3), TODAY);
        assertTrue(result.isMatch());
        assertEquals(4117.53, ((Number) result.row().get("monthlyAmount")).doubleValue(), 0.001);
        assertNotNull(result.item().sourceDocumentId(), "provenance must be retained");
    }

    @Test
    void bandLookupIsHalfOpenAndMatchesLegacyBoundaries() {
        var item = table(StructuredKnowledgeStatus.ACTIVE, TRAVEL_BANDS, null, null);
        assertEquals(6.0, value(evaluator, item, 8.0));
        assertEquals(6.0, value(evaluator, item, 10.99));
        assertEquals(12.0, value(evaluator, item, 11.0), "11 belongs to [11,24) like legacy");
        assertEquals(12.0, value(evaluator, item, 23.99));
        assertEquals(24.0, value(evaluator, item, 24.0));
        assertEquals(24.0, value(evaluator, item, 24.01));
    }

    private double value(TableEvaluator evaluator, StructuredKnowledgeItem item, double hours) {
        var result = evaluator.evaluate(List.of(item), Map.of("hours", hours), TODAY);
        assertTrue(result.isMatch(), "hours=" + hours);
        return ((Number) result.row().get("allowanceEur")).doubleValue();
    }

    @Test
    void constantTableWithoutLookupMatchesItsSingleRow() {
        var item = table(StructuredKnowledgeStatus.ACTIVE,
                "{\"columns\":[\"mode\",\"rateEur\"],\"rows\":[[\"PKW\",0.35]],\"result\":[\"rateEur\"]}",
                null, null);
        var result = evaluator.evaluate(List.of(item), Map.of("distanceKm", 50.0), TODAY);
        assertTrue(result.isMatch());
        assertEquals(0.35, ((Number) result.row().get("rateEur")).doubleValue(), 0.001);
    }

    @Test
    void missingFactIsIndeterminateNotGuessed() {
        var result = evaluator.evaluate(List.of(table(StructuredKnowledgeStatus.ACTIVE, SALARY, null, null)),
                Map.of("salaryGrade", "EG 9a"), TODAY);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome());
    }

    @Test
    void noMatchingRowFallsThrough() {
        var result = evaluator.evaluate(List.of(table(StructuredKnowledgeStatus.ACTIVE, SALARY, null, null)),
                Map.of("salaryGrade", "EG 99", "salaryStep", 1), TODAY);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome(),
                "no match must not fabricate a row");
    }

    @Test
    void overlappingBandsProduceConflictInsteadOfFirstMatch() {
        var item = table(StructuredKnowledgeStatus.ACTIVE,
                "{\"columns\":[\"hoursMin\",\"hoursMax\",\"allowanceEur\"],"
                        + "\"rows\":[[0,12,10],[8,24,20]],"
                        + "\"lookup\":{\"hoursMin\":\"hours\",\"hoursMax\":\"hours\"},"
                        + "\"result\":[\"allowanceEur\"]}", null, null);
        var result = evaluator.evaluate(List.of(item), Map.of("hours", 10.0), TODAY);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome());
        assertEquals(1, result.competing().size(),
                "two rows of one table matching → conflict, never first-match");
    }

    @Test
    void expiredAndFutureTablesCannotExecute() {
        var expired = table(StructuredKnowledgeStatus.ACTIVE, SALARY,
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31));
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, evaluator.evaluate(
                List.of(expired), Map.of("salaryGrade", "EG 9a", "salaryStep", 3), TODAY).outcome());
        var future = table(StructuredKnowledgeStatus.ACTIVE, SALARY,
                LocalDate.of(2030, 1, 1), null);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, evaluator.evaluate(
                List.of(future), Map.of("salaryGrade", "EG 9a", "salaryStep", 3), TODAY).outcome());
    }

    @Test
    void nonActiveTablesCanNeverExecute() {
        for (StructuredKnowledgeStatus status : List.of(StructuredKnowledgeStatus.CANDIDATE,
                StructuredKnowledgeStatus.REJECTED, StructuredKnowledgeStatus.SUPERSEDED)) {
            var result = evaluator.evaluate(List.of(table(status, SALARY, null, null)),
                    Map.of("salaryGrade", "EG 9a", "salaryStep", 3), TODAY);
            assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome(), status + " must not execute");
        }
    }

    @Test
    void malformedTableIsIndeterminate() {
        var result = evaluator.evaluate(List.of(table(StructuredKnowledgeStatus.ACTIVE, "{kein json", null, null)),
                Map.of("salaryGrade", "EG 9a", "salaryStep", 3), TODAY);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome());
        // Unsupported lookup fact → malformed → indeterminate.
        var badFact = table(StructuredKnowledgeStatus.ACTIVE,
                "{\"columns\":[\"x\",\"y\"],\"rows\":[[1,2]],\"lookup\":{\"x\":\"cost\"},\"result\":[\"y\"]}",
                null, null);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE,
                evaluator.evaluate(List.of(badFact), Map.of("amount", 1.0), TODAY).outcome());
    }

    @Test
    void resultIsOrderIndependent() {
        var a = table(StructuredKnowledgeStatus.ACTIVE, TRAVEL_BANDS, null, null);
        var b = table(StructuredKnowledgeStatus.ACTIVE, SALARY, null, null);
        var forward = evaluator.evaluate(List.of(a, b), Map.of("hours", 12.0), TODAY);
        var backward = evaluator.evaluate(List.of(b, a), Map.of("hours", 12.0), TODAY);
        assertEquals(forward.outcome(), backward.outcome());
        assertEquals(forward.row(), backward.row());
    }
}
