package reasoning.ai.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Validates temporal consistency between query dates and answer dates.
 */
public interface TemporalConsistencyValidator {

    /**
     * The result of a temporal consistency check, listing any issues found.
     */
    record TemporalCheckResult(boolean consistent, List<String> issues, List<ExtractedDate> dates) {}

    /**
     * A date extracted from text, with its source, description, and whether it was explicitly stated.
     */
    record ExtractedDate(String source, String description, LocalDate date, boolean isExplicit) {}

    /**
     * Validates the temporal consistency of the answer against the query.
     */
    TemporalCheckResult validate(String query, String answer);
}
