package reasoning.ai.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Structured semantic representation extracted from a natural-language query.
 *
 * <p>This is the language-independent bridge between natural language
 * understanding and the deterministic Verwaltungsassistent core. The core consumes
 * {@code parameters} and {@code intentType} — never raw text.
 *
 * <p>{@code language} is the ISO 639-1 code of the question's language as
 * inferred by the parser (informational/diagnostic — parameters are already
 * language-neutral). {@code null} when the parser cannot determine it.
 * Locale is deliberately not represented: no deterministic consumer needs
 * sub-language granularity, and locale guesses are unreliable.
 */
public record StructuredIntent(
        String originalQuestion,
        Domain domain,
        String intentType,
        Map<String, Object> parameters,
        String language
) {
    public static final String INTENT_TRAVEL_ALLOWANCE = "TRAVEL_ALLOWANCE";
    public static final String INTENT_SALARY_LOOKUP = "SALARY_LOOKUP";
    public static final String INTENT_PROCUREMENT_THRESHOLD = "PROCUREMENT_THRESHOLD";
    public static final String INTENT_GENERAL = "GENERAL";

    public StructuredIntent {
        parameters = parameters != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(parameters))
                : Map.of();
        language = language != null ? language.toLowerCase() : null;
    }

    /** Convenience constructor for parsers without language inference. */
    public StructuredIntent(String originalQuestion, Domain domain, String intentType,
                            Map<String, Object> parameters) {
        this(originalQuestion, domain, intentType, parameters, null);
    }

    public Optional<Double> hours() {
        return numberParam("hours");
    }

    public Optional<Double> distanceKm() {
        return numberParam("distanceKm");
    }

    public Optional<Double> amountEur() {
        return numberParam("amountEur");
    }

    public Optional<String> salaryGrade() {
        Object v = parameters.get("salaryGrade");
        return v instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    public Optional<Integer> salaryStep() {
        Object v = parameters.get("salaryStep");
        if (v instanceof Number n) return Optional.of(n.intValue());
        if (v instanceof String s) {
            try { return Optional.of(Integer.parseInt(s)); }
            catch (NumberFormatException ignored) {}
        }
        return Optional.empty();
    }

    public boolean isIncreaseQuery() {
        return "INCREASE".equalsIgnoreCase((String) parameters.getOrDefault("queryType", ""));
    }

    public boolean isOvernight() {
        return Boolean.TRUE.equals(parameters.get("overnight"));
    }

    private Optional<Double> numberParam(String key) {
        Object v = parameters.get(key);
        if (v instanceof Number n) return Optional.of(n.doubleValue());
        if (v instanceof String s) {
            try { return Optional.of(Double.parseDouble(s)); }
            catch (NumberFormatException ignored) {}
        }
        return Optional.empty();
    }

    /** Returns an empty intent (no parameters extracted). */
    public static StructuredIntent empty(String question) {
        return new StructuredIntent(question, null, INTENT_GENERAL, Map.of());
    }
}
