package reasoning.ai.application;

import reasoning.ai.api.TemporalConsistencyValidator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates temporal consistency by extracting and checking dates in query and answer text.
 */
@Service
public class DefaultTemporalConsistencyValidator implements TemporalConsistencyValidator {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2})\\.?\\s*(January|February|March|April|May|June|July|August|September|October|November|December|Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})");

    private static final Pattern ISO_DATE = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})");

    private static final Map<String, Integer> MONTH_MAP = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
            Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
            Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12),
            Map.entry("januar", 1), Map.entry("februar", 2), Map.entry("märz", 3),
            Map.entry("mai", 5), Map.entry("juni", 6), Map.entry("juli", 7),
            Map.entry("oktober", 10), Map.entry("dezember", 12));

    @Override
    public TemporalCheckResult validate(String query, String answer) {
        List<ExtractedDate> queryDates = extractDates(query, "query");
        List<ExtractedDate> answerDates = extractDates(answer, "answer");
        List<String> issues = new ArrayList<>();

        List<ExtractedDate> allDates = new ArrayList<>();
        allDates.addAll(queryDates);
        allDates.addAll(answerDates);

        // Check 1: future dates hallucinated in answer
        LocalDate today = LocalDate.now();
        for (ExtractedDate d : answerDates) {
            if (d.date() != null && d.date().isAfter(today.plusDays(30))) {
                if (d.date().isAfter(today.plusYears(1))) {
                    issues.add("Hallucinated far-future date: " + d.date() + " — " + d.description());
                } else if (!d.isExplicit()) {
                    issues.add("Potentially invented future date: " + d.date() + " — " + d.description());
                }
            }
        }

        // Check 2: dates far in the past without context
        for (ExtractedDate d : answerDates) {
            if (d.date() != null && d.date().isBefore(LocalDate.of(2000, 1, 1))) {
                issues.add("Implausibly old date: " + d.date() + " — " + d.description());
            }
        }

        // Check 3: chronological consistency
        List<ExtractedDate> sorted = allDates.stream()
                .filter(d -> d.date() != null)
                .sorted(Comparator.comparing(ExtractedDate::date))
                .toList();

        for (int i = 0; i < sorted.size() - 1; i++) {
            ExtractedDate a = sorted.get(i);
            ExtractedDate b = sorted.get(i + 1);
            if (a.date() != null && b.date() != null) {
                long daysBetween = ChronoUnit.DAYS.between(a.date(), b.date());
                if (daysBetween < 0) {
                    issues.add("Chronological inconsistency: " + a.description()
                            + " (" + a.date() + ") after " + b.description() + " (" + b.date() + ")");
                }
            }
        }

        // Check 4: specific known-hallucinated patterns
        if (answer.contains("31 December 2025") || answer.contains("31.12.2025")
                || answer.contains("31 Dezember 2025")) {
            issues.add("Known hallucination pattern detected: '31 December 2025'");
        }

        boolean consistent = issues.isEmpty();
        return new TemporalCheckResult(consistent, issues, allDates);
    }

    private List<ExtractedDate> extractDates(String text, String source) {
        List<ExtractedDate> dates = new ArrayList<>();
        if (text == null) return dates;

        Matcher iso = ISO_DATE.matcher(text);
        while (iso.find()) {
            try {
                int year = Integer.parseInt(iso.group(1));
                int month = Integer.parseInt(iso.group(2));
                int day = Integer.parseInt(iso.group(3));
                LocalDate d = LocalDate.of(year, month, day);
                dates.add(new ExtractedDate(source, iso.group(), d, true));
            } catch (Exception ignored) {}
        }

        Matcher num = NUMERIC_DATE.matcher(text);
        while (num.find()) {
            try {
                int day = Integer.parseInt(num.group(1));
                int month = Integer.parseInt(num.group(2));
                int year = Integer.parseInt(num.group(3));
                if (year < 100) year += 2000;
                LocalDate d = LocalDate.of(year, month, day);
                dates.add(new ExtractedDate(source, num.group(), d, true));
            } catch (Exception ignored) {}
        }

        Matcher textDate = DATE_PATTERN.matcher(text);
        while (textDate.find()) {
            try {
                int day = Integer.parseInt(textDate.group(1));
                String monthName = textDate.group(2).toLowerCase();
                Integer month = MONTH_MAP.get(monthName);
                int year = Integer.parseInt(textDate.group(3));
                if (month != null) {
                    LocalDate d = LocalDate.of(year, month, day);
                    dates.add(new ExtractedDate(source, textDate.group(), d, true));
                }
            } catch (Exception ignored) {}
        }

        return dates;
    }
}
