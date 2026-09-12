package reasoning.ai.knowledge;

import java.time.LocalDate;
import java.util.*;

/**
 * Structured TV-L salary table for deterministic lookup.
 * Loaded from the corpus, not hardcoded.
 */
public class SalaryTable {

    private final String sourceDocument;
    private final String payScale; // "TV-L", "TVöD", etc.
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveUntil;
    private final List<SalaryEntry> entries = new ArrayList<>();

    public SalaryTable(String sourceDocument, String payScale,
                       LocalDate effectiveFrom, LocalDate effectiveUntil) {
        this.sourceDocument = sourceDocument;
        this.payScale = payScale;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
    }

    public void addEntry(String grade, int step, double monthlyAmount,
                          double annualSpecialPayment, String notes) {
        entries.add(new SalaryEntry(grade, step, monthlyAmount, annualSpecialPayment, notes));
    }

    /** Looks up a specific salary grade and step. */
    public Optional<SalaryEntry> lookup(String grade, int step) {
        return entries.stream()
                .filter(e -> e.grade().equalsIgnoreCase(grade) && e.step() == step)
                .findFirst();
    }

    /** Returns all entries for a grade. */
    public List<SalaryEntry> findByGrade(String grade) {
        return entries.stream()
                .filter(e -> e.grade().equalsIgnoreCase(grade))
                .sorted(Comparator.comparingInt(SalaryEntry::step))
                .toList();
    }

    /** Computes the increase between two dates for a given grade/step. */
    public record SalaryIncrease(double oldAmount, double newAmount, double increase,
                                  double increasePercent) {}

    public Optional<SalaryIncrease> computeIncrease(String grade, int step,
                                                      SalaryTable previousTable) {
        var current = lookup(grade, step);
        var previous = previousTable.lookup(grade, step);
        if (current.isEmpty() || previous.isEmpty()) return Optional.empty();
        double diff = current.get().monthlyAmount() - previous.get().monthlyAmount();
        double pct = previous.get().monthlyAmount() > 0
                ? (diff / previous.get().monthlyAmount()) * 100 : 0;
        return Optional.of(new SalaryIncrease(
                previous.get().monthlyAmount(), current.get().monthlyAmount(), diff, pct));
    }

    public record SalaryEntry(String grade, int step, double monthlyAmount,
                               double annualSpecialPayment, String notes) {}

    // Getters
    public String sourceDocument() { return sourceDocument; }
    public String payScale() { return payScale; }
    public LocalDate effectiveFrom() { return effectiveFrom; }
    public LocalDate effectiveUntil() { return effectiveUntil; }
    public List<SalaryEntry> entries() { return List.copyOf(entries); }
    public int size() { return entries.size(); }
}
