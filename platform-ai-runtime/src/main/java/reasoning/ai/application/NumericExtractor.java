package reasoning.ai.application;

import reasoning.ai.model.NumericExtraction;
import reasoning.ai.model.NumericExtraction.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured numeric data (money, percentages, dates, thresholds,
 * salary grades) from document text for deterministic reasoning.
 *
 * <p>The LLM should reason over these structured fields rather than parsing
 * numbers from plain text — this prevents calculation errors.
 */
@Component
public class NumericExtractor {

    private static final Logger log = LoggerFactory.getLogger(NumericExtractor.class);

    // ── Patterns ──

    // German-formatted number: "1.234,56" or "1.234" or "1234,56"
    private static final String GERMAN_NUMBER = "(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{2})?)";

    // Money: "4.117,53 €", "10.000 Euro", "500 euros", "EUR 1000"
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            GERMAN_NUMBER + "\\s*(€|Euro|EUR|euros?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Money range: "5.000-10.000 Euro", "zwischen 5.000 und 10.000 Euro"
    private static final Pattern MONEY_RANGE_PATTERN = Pattern.compile(
            GERMAN_NUMBER + "\\s*[-–—]\\s*" + GERMAN_NUMBER + "\\s*(€|Euro|EUR|euros?)" +
            "|(?:zwischen)\\s+" + GERMAN_NUMBER + "\\s+(?:und)\\s+" + GERMAN_NUMBER + "\\s*(€|Euro|EUR|euros?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Approximate money: "rund 10.000 Euro", "ca. 5.000 €", "etwa 3.000 EUR"
    private static final Pattern APPROX_MONEY_PATTERN = Pattern.compile(
            "(?:rund|ca\\.?|circa|etwa|ungefähr|zirka)\\s+" + GERMAN_NUMBER + "\\s*(€|Euro|EUR|euros?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Bare German number without currency (but looks like money): "1.234,56"
    // Must NOT be followed by a currency marker (€, Euro, EUR) to avoid
    // duplicating captures from MONEY_PATTERN. Also skip percentages.
    private static final Pattern BARE_GERMAN_MONEY_PATTERN = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{2}))" +
            "(?!\\s*(?:%|Prozent|€|Euro|EUR|euros?))");

    // Percentages: "5,5 %", "5.5%", "5,5 Prozent"
    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(%|Prozent|percent)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Salary grades: "EG 9a Stufe 3", "EG9a/3", "Entgeltgruppe 9a Stufe 3"
    private static final Pattern SALARY_GRADE_PATTERN = Pattern.compile(
            "E(?:ntgeltgruppe|G)\\s*(\\d+[a-z]?)\\s*(?:Stufe\\s*(\\d+)|/\\s*(\\d+))?",
            Pattern.CASE_INSENSITIVE);

    // Salary amounts near grades: "4.117,53 €" near "EG 9a"
    private static final Pattern SALARY_AMOUNT_PATTERN = Pattern.compile(
            GERMAN_NUMBER + "\\s*(€|Euro|EUR)");

    // Procurement thresholds: "bis 10.000 Euro", "up to 100.000 EUR"
    private static final Pattern THRESHOLD_PATTERN = Pattern.compile(
            "(?:bis|up to|unter|below|ab|above|über|Schwellenwert|Grenze" +
            "|mindestens|höchstens|maximal|minimal)\\s*(?:zu)?\\s*" +
            GERMAN_NUMBER + "\\s*(€|Euro|EUR|euros?)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Threshold range: "5.000 bis 10.000 Euro"
    private static final Pattern THRESHOLD_RANGE_PATTERN = Pattern.compile(
            GERMAN_NUMBER + "\\s*(?:bis|–|—|-)\\s*" + GERMAN_NUMBER + "\\s*(€|Euro|EUR|euros?)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Thousand/Million words: "50 Tausend Euro", "2 Millionen €"
    private static final Pattern WORD_NUMBER_PATTERN = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(Tausend|Millionen?|Mrd\\.?|Milliarden?)\\s*(€|Euro|EUR)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Dates: "01.02.2025", "1. Februar 2025", "2025-02-01"
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?:ab|vom|seit|gültig ab|effective|valid from|effective date)\\s*" +
            "((?:\\d{1,2}[.\\s])?(?:Januar|Februar|März|April|Mai|Juni|Juli|" +
            "August|September|Oktober|November|Dezember|January|February|March|" +
            "April|May|June|July|August|September|October|November|December)\\s*\\d{4}" +
            "|\\d{1,2}\\.\\d{1,2}\\.\\d{4}|\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Extracts all numeric data from the given text.
     */
    public NumericExtraction extract(String text) {
        if (text == null || text.isBlank()) return NumericExtraction.empty();
        Builder builder = new Builder();

        extractMoney(text, builder);
        extractMoneyRanges(text, builder);
        extractApproxMoney(text, builder);
        extractBareGermanMoney(text, builder);
        extractWordNumbers(text, builder);
        extractPercentages(text, builder);
        extractSalaryGrades(text, builder);
        extractThresholds(text, builder);
        extractThresholdRanges(text, builder);
        extractDates(text, builder);

        return builder.build();
    }

    /**
     * Extracts numeric data from a list of texts and returns a merged result.
     */
    public NumericExtraction extractAll(List<String> texts) {
        Builder builder = new Builder();
        for (String text : texts) {
            NumericExtraction single = extract(text);
            single.moneyValues().forEach(builder::addMoney);
            single.percentages().forEach(builder::addPercentage);
            single.salaryGrades().forEach(builder::addSalaryGrade);
            single.thresholds().forEach(builder::addThreshold);
            single.dates().forEach(builder::addDate);
        }
        return builder.build();
    }

    // ── Extractors ──

    private void extractMoney(String text, Builder builder) {
        Matcher m = MONEY_PATTERN.matcher(text);
        while (m.find()) {
            try {
                double amount = parseGermanNumber(m.group(1));
                String context = getContext(text, m.start(), m.end());
                String currency = m.group(2) != null ? "EUR" : "EUR";
                builder.addMoney(new MoneyValue(amount, currency,
                        m.group(1) + " " + currency, context));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void extractPercentages(String text, Builder builder) {
        Matcher m = PERCENTAGE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                double value = Double.parseDouble(m.group(1).replace(",", "."));
                String context = getContext(text, m.start(), m.end());
                builder.addPercentage(new PercentageValue(value,
                        m.group(1) + "%", context));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void extractSalaryGrades(String text, Builder builder) {
        Matcher gradeMatcher = SALARY_GRADE_PATTERN.matcher(text);
        List<String> foundGrades = new ArrayList<>();

        while (gradeMatcher.find()) {
            String grade = gradeMatcher.group(1);
            int step = 0;
            if (gradeMatcher.group(2) != null) step = Integer.parseInt(gradeMatcher.group(2));
            else if (gradeMatcher.group(3) != null) step = Integer.parseInt(gradeMatcher.group(3));

            // Look for the closest salary amount
            Matcher amountMatcher = SALARY_AMOUNT_PATTERN.matcher(text);
            double amount = 0;
            String context = getContext(text, gradeMatcher.start(), gradeMatcher.end());
            while (amountMatcher.find()) {
                String amtStr = amountMatcher.group(1).replace(".", "").replace(",", ".");
                try {
                    amount = Double.parseDouble(amtStr);
                    context = getContext(text,
                            Math.min(gradeMatcher.start(), amountMatcher.start()),
                            Math.max(gradeMatcher.end(), amountMatcher.end()));
                } catch (NumberFormatException ignored) {}
            }
            foundGrades.add("EG " + grade + (step > 0 ? " Stufe " + step : ""));
            builder.addSalaryGrade(new SalaryGrade("EG " + grade, step, amount, "EUR",
                    null, "TV-L", context));
        }
    }

    private void extractThresholds(String text, Builder builder) {
        Matcher m = THRESHOLD_PATTERN.matcher(text);
        while (m.find()) {
            try {
                double amount = parseGermanNumber(m.group(1));
                String currency = m.group(2) != null ? "EUR" : "EUR";
                String context = getContext(text, m.start(), m.end());
                builder.addThreshold(new ThresholdValue(amount, currency,
                        m.group(1) + " " + currency, null, context));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void extractDates(String text, Builder builder) {
        Matcher m = DATE_PATTERN.matcher(text);
        while (m.find()) {
            String dateStr = m.group(1).trim();
            String context = getContext(text, m.start(), m.end());
            builder.addDate(new DateValue(null, dateStr,
                    "gültig ab", context));
        }
    }

    private void extractMoneyRanges(String text, Builder builder) {
        Matcher m = MONEY_RANGE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                String fullMatch = m.group(0);
                String amount1Str, amount2Str, currency;
                if (m.group(1) != null) {
                    // dash range: "5.000-10.000 Euro"
                    amount1Str = m.group(1);
                    amount2Str = m.group(2);
                    currency = m.group(3) != null ? "EUR" : "EUR";
                } else {
                    // "zwischen X und Y" range
                    amount1Str = m.group(4);
                    amount2Str = m.group(5);
                    currency = m.group(6) != null ? "EUR" : "EUR";
                }
                double amount1 = parseGermanNumber(amount1Str);
                double amount2 = parseGermanNumber(amount2Str);
                String context = getContext(text, m.start(), m.end());
                builder.addMoney(new MoneyValue(amount1, currency,
                        "von " + amount1Str + " " + currency, context));
                builder.addMoney(new MoneyValue(amount2, currency,
                        "bis " + amount2Str + " " + currency, context));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void extractApproxMoney(String text, Builder builder) {
        Matcher m = APPROX_MONEY_PATTERN.matcher(text);
        while (m.find()) {
            try {
                String amountStr = m.group(1);
                double amount = parseGermanNumber(amountStr);
                String currency = m.group(2) != null ? "EUR" : "EUR";
                String context = getContext(text, m.start(), m.end());
                builder.addMoney(new MoneyValue(amount, currency,
                        "ca. " + amountStr + " " + currency, context));
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Extracts bare German-formatted numbers like "1.234,56" that look
     * like monetary amounts. These are NOT the same as "1.234" (thousands
     * separator) — we require both dots as thousands separators AND
     * comma decimal to qualify as a money-like number.
     */
    private void extractBareGermanMoney(String text, Builder builder) {
        Matcher m = BARE_GERMAN_MONEY_PATTERN.matcher(text);
        while (m.find()) {
            try {
                String amountStr = m.group(1);
                double amount = parseGermanNumber(amountStr);
                // Skip if this was already captured by the currency-pattern extractor
                String context = getContext(text, m.start(), m.end());
                if (!context.toLowerCase().contains("prozent") && !context.contains("%")) {
                    builder.addMoney(new MoneyValue(amount, "EUR",
                            amountStr + " EUR", context));
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void extractWordNumbers(String text, Builder builder) {
        Matcher m = WORD_NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            try {
                double baseNum = Double.parseDouble(m.group(1).replace(",", "."));
                String word = m.group(2).toLowerCase();
                double multiplier = 1.0;
                if (word.startsWith("tausend")) multiplier = 1_000.0;
                else if (word.startsWith("million")) multiplier = 1_000_000.0;
                else if (word.startsWith("mrd") || word.startsWith("milliard")) multiplier = 1_000_000_000.0;
                double amount = baseNum * multiplier;
                String currency = m.group(3) != null ? "EUR" : "EUR";
                String context = getContext(text, m.start(), m.end());
                builder.addMoney(new MoneyValue(amount, currency,
                        m.group(1) + " " + m.group(2) + " " + currency, context));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void extractThresholdRanges(String text, Builder builder) {
        Matcher m = THRESHOLD_RANGE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                String amount1Str = m.group(1);
                String amount2Str = m.group(2);
                double amount1 = parseGermanNumber(amount1Str);
                double amount2 = parseGermanNumber(amount2Str);
                String currency = m.group(3) != null ? "EUR" : "EUR";
                String context = getContext(text, m.start(), m.end());
                builder.addThreshold(new ThresholdValue(amount1, currency,
                        amount1Str + " - " + amount2Str + " " + currency,
                        amount2Str, context));
                builder.addThreshold(new ThresholdValue(amount2, currency,
                        amount1Str + " - " + amount2Str + " " + currency,
                        amount2Str, context));
            } catch (NumberFormatException ignored) {}
        }
    }

    /** Parses a German-formatted number like "1.234,56" → 1234.56. */
    private static double parseGermanNumber(String s) {
        return Double.parseDouble(s.replace(".", "").replace(",", "."));
    }

    private String getContext(String text, int start, int end) {
        int ctxStart = Math.max(0, start - 40);
        int ctxEnd = Math.min(text.length(), end + 60);
        String ctx = text.substring(ctxStart, ctxEnd).replace('\n', ' ').trim();
        if (ctx.length() > 200) ctx = ctx.substring(0, 200) + "...";
        return ctx;
    }
}
