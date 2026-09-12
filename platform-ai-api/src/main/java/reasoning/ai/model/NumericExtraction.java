package reasoning.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured numeric data extracted from document text.
 * Captures money amounts, percentages, dates, thresholds, salary grades,
 * and other numeric values that the LLM should reason over directly.
 */
public record NumericExtraction(
        List<MoneyValue> moneyValues,
        List<PercentageValue> percentages,
        List<DateValue> dates,
        List<ThresholdValue> thresholds,
        List<SalaryGrade> salaryGrades
) {
    public NumericExtraction {
        moneyValues = moneyValues == null ? List.of() : List.copyOf(moneyValues);
        percentages = percentages == null ? List.of() : List.copyOf(percentages);
        dates = dates == null ? List.of() : List.copyOf(dates);
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        salaryGrades = salaryGrades == null ? List.of() : List.copyOf(salaryGrades);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return moneyValues.isEmpty() && percentages.isEmpty()
                && dates.isEmpty() && thresholds.isEmpty() && salaryGrades.isEmpty();
    }

    public static NumericExtraction empty() {
        return new NumericExtraction(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Builder for incremental construction. */
    public static class Builder {
        private final List<MoneyValue> money = new ArrayList<>();
        private final List<PercentageValue> pcts = new ArrayList<>();
        private final List<DateValue> dates = new ArrayList<>();
        private final List<ThresholdValue> thresholds = new ArrayList<>();
        private final List<SalaryGrade> grades = new ArrayList<>();

        public Builder addMoney(MoneyValue m) { money.add(m); return this; }
        public Builder addPercentage(PercentageValue p) { pcts.add(p); return this; }
        public Builder addDate(DateValue d) { dates.add(d); return this; }
        public Builder addThreshold(ThresholdValue t) { thresholds.add(t); return this; }
        public Builder addSalaryGrade(SalaryGrade g) { grades.add(g); return this; }

        public NumericExtraction build() {
            return new NumericExtraction(
                    List.copyOf(money), List.copyOf(pcts), List.copyOf(dates),
                    List.copyOf(thresholds), List.copyOf(grades));
        }
    }

    // ── Value types ──

    /** A monetary amount with optional currency and context. */
    public record MoneyValue(double amount, String currency, String label, String context) {
        public MoneyValue {
            if (currency == null) currency = "EUR";
        }
    }

    /** A percentage value. */
    public record PercentageValue(double value, String label, String context) {}

    /** A date value extracted from text. */
    public record DateValue(String isoDate, String displayDate, String label, String context) {}

    /** A threshold or limit value (e.g. procurement limits). */
    public record ThresholdValue(double amount, String currency, String label,
                                  String condition, String context) {
        public ThresholdValue {
            if (currency == null) currency = "EUR";
        }
    }

    /** A salary grade with pay scale info. */
    public record SalaryGrade(String grade, int step, double amount, String currency,
                               String effectiveDate, String payScale, String context) {
        public SalaryGrade {
            if (currency == null) currency = "EUR";
        }
    }
}
