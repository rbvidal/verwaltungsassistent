package reasoning.ai.model;

import java.util.List;

/**
 * Canonical semantic fact identifiers for deterministic rule evaluation.
 *
 * <p>The deterministic engine operates ONLY on these canonical facts — never
 * on domain-specific wording ("Auftragswert", "Kosten", "Preis", …). The
 * extraction layer is responsible for mapping regulatory language onto these
 * identifiers; the evaluator contains no aliases. Unknown fact names fail
 * safely into INDETERMINATE (never executed).
 */
public final class FactVocabulary {

    /** The monetary amount relevant to the evaluated decision. */
    public static final String AMOUNT = "amount";

    /** Duration in hours. */
    public static final String HOURS = "hours";

    /** Distance in kilometers. */
    public static final String DISTANCE_KM = "distanceKm";

    /** Salary grade, e.g. "EG 9a". */
    public static final String SALARY_GRADE = "salaryGrade";

    /** Salary step (1..6). */
    public static final String SALARY_STEP = "salaryStep";

    /**
     * Categorical mode/qualifier of the evaluated decision (string equality).
     * Values are data supplied by the knowledge and query layers — e.g.
     * "standard"/"overnight" for travel duration bands, "withReceipt"/"flat"
     * for accommodation — never defined by the evaluator.
     */
    public static final String MODE = "mode";

    private FactVocabulary() {
    }

    public static boolean isCanonical(String fact) {
        return AMOUNT.equals(fact) || HOURS.equals(fact) || DISTANCE_KM.equals(fact)
                || SALARY_GRADE.equals(fact) || SALARY_STEP.equals(fact) || MODE.equals(fact);
    }

    public static List<String> supportedFacts() {
        return List.of(AMOUNT, HOURS, DISTANCE_KM, SALARY_GRADE, SALARY_STEP, MODE);
    }
}
