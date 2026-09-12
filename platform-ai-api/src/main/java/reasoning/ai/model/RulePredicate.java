package reasoning.ai.model;

/**
 * One generic condition predicate of an executable RULE.
 *
 * <p>Domain-agnostic: {@code fact} names a generic query fact (e.g. "amount",
 * "hours", "distanceKm", "salaryGrade"), {@code op} is a comparison
 * (EQ|NE|LT|LE|GT|GE), {@code value} is the compared literal
 * (java.math.BigDecimal for numeric comparisons, String for equality), and
 * {@code currency} is optional semantic currency data (e.g. "EUR") — never a
 * display format. The meaning of a fact is supplied by the query-fact layer —
 * never by the evaluator.
 */
public record RulePredicate(String fact, String op, Object value, String currency) {

    public static boolean isKnownOp(String op) {
        return op != null && switch (op) {
            case "EQ", "NE", "LT", "LE", "GT", "GE" -> true;
            default -> false;
        };
    }
}
