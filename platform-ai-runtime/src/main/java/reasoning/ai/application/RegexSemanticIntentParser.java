package reasoning.ai.application;

import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.model.StructuredIntent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regex-based semantic intent parser. This is the current production behavior
 * — extracts structured parameters from natural language using deterministic
 * patterns.
 *
 * <p>This parser understands German and English numeric expressions but
 * is fundamentally language-dependent. It serves as the fallback when the
 * experimental LLM parser is disabled.
 */
@Component
public class RegexSemanticIntentParser implements SemanticIntentParser {

    private static final Pattern HOURS_PATTERN = Pattern.compile(
            "(\\d+)[\\s-]*(stündig|stündigen|stunden|stündige|stündiger"
                    + "|stuendig|stuendigen|stuendige|stuendiger|h|hour|hours)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DISTANCE_PATTERN = Pattern.compile(
            "(\\d+)[\\s-]*(km|kilometer|mile|meile)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{2})?|\\d{4,})\\s*(€|euro|eur|euros)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GRADE_PATTERN = Pattern.compile(
            "EG\\s*(\\d+[a-z]?)\\s*(?:Stufe\\s*(\\d+))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INCREASE_PATTERN = Pattern.compile(
            ".*(erhöhung|steigerung|mehr|differenz|änderung).*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public StructuredIntent parse(String question) {
        Map<String, Object> params = new LinkedHashMap<>();
        String intent = StructuredIntent.INTENT_GENERAL;

        // Hours / duration
        var h = extractHours(question);
        if (h.isPresent()) {
            params.put("hours", h.get());
            intent = StructuredIntent.INTENT_TRAVEL_ALLOWANCE;
        }

        // Distance
        var km = extractDistance(question);
        km.ifPresent(d -> params.put("distanceKm", d));

        // Amount
        var amounts = extractAmounts(question);
        if (!amounts.isEmpty()) {
            params.put("amountEur", amounts.get(0));
            intent = StructuredIntent.INTENT_PROCUREMENT_THRESHOLD;
        }

        // Salary grade
        var grades = extractGrades(question);
        if (!grades.isEmpty()) {
            params.put("salaryGrade", grades.get(0)[0]);
            if (grades.get(0).length > 1) {
                try { params.put("salaryStep", Integer.parseInt(grades.get(0)[1])); }
                catch (NumberFormatException ignored) {}
            }
            intent = StructuredIntent.INTENT_SALARY_LOOKUP;
        }

        // Increase query
        if (INCREASE_PATTERN.matcher(question.toLowerCase()).matches()) {
            params.put("queryType", "INCREASE");
        }

        // Overnight
        String lower = question.toLowerCase();
        if (lower.contains("übernachtung") || lower.contains("übernacht")
                || lower.contains("overnight")) {
            params.put("overnight", true);
        }

        return new StructuredIntent(question, null, intent, params);
    }

    // ── Pattern extractors (mirroring DecisionRouter logic) ──

    static Optional<Double> extractHours(String text) {
        var m = HOURS_PATTERN.matcher(text);
        return m.find() ? Optional.of(Double.parseDouble(m.group(1))) : Optional.empty();
    }

    static Optional<Double> extractDistance(String text) {
        var m = DISTANCE_PATTERN.matcher(text);
        return m.find() ? Optional.of(Double.parseDouble(m.group(1))) : Optional.empty();
    }

    static java.util.List<Double> extractAmounts(String text) {
        java.util.List<Double> a = new java.util.ArrayList<>();
        var m = AMOUNT_PATTERN.matcher(text);
        while (m.find()) {
            try { a.add(Double.parseDouble(m.group(1).replace(".", "").replace(",", "."))); }
            catch (NumberFormatException ignored) {}
        }
        return a;
    }

    static java.util.List<String[]> extractGrades(String text) {
        java.util.List<String[]> r = new java.util.ArrayList<>();
        var m = GRADE_PATTERN.matcher(text);
        while (m.find()) {
            // No silent step default: a grade without an explicit "Stufe N"
            // yields a null step — the router refuses the deterministic lookup.
            r.add(new String[]{"EG " + m.group(1), m.group(2)});
        }
        return r;
    }
}
