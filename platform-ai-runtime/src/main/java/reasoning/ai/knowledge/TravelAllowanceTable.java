package reasoning.ai.knowledge;

import java.time.LocalDate;
import java.util.*;

/**
 * Structured BRKG/LRKG travel allowance table for deterministic lookup.
 *
 * <p>Supports queries like:
 * "What is the meal allowance for a 12-hour business trip?"
 */
public class TravelAllowanceTable {

    private final String sourceDocument;
    private final String regulation; // "BRKG", "LRKG", etc.
    private final LocalDate effectiveFrom;
    private final List<TravelAllowanceEntry> entries = new ArrayList<>();

    public TravelAllowanceTable(String sourceDocument, String regulation,
                                  LocalDate effectiveFrom) {
        this.sourceDocument = sourceDocument;
        this.regulation = regulation;
        this.effectiveFrom = effectiveFrom;
    }

    public void addEntry(double minHours, Double maxHours, double allowanceEur,
                          String category, boolean requiresOvernight, String description) {
        entries.add(new TravelAllowanceEntry(
                minHours, maxHours, allowanceEur, category, requiresOvernight, description));
    }

    /**
     * Looks up the allowance for a given absence duration.
     *
     * @param hours          duration of absence
     * @param overnight      whether an overnight stay occurred
     * @param categoryFilter optional category filter ("domestic", "international", null)
     */
    public Optional<TravelAllowanceEntry> lookup(double hours, boolean overnight,
                                                   String categoryFilter) {
        return entries.stream()
                .filter(e -> hours >= e.minHours())
                .filter(e -> e.maxHours() == null || hours < e.maxHours())
                .filter(e -> !e.requiresOvernight() || overnight)
                .filter(e -> categoryFilter == null
                        || e.category().equalsIgnoreCase(categoryFilter))
                .max(Comparator.comparingDouble(TravelAllowanceEntry::allowanceEur));
    }

    /** All domestic meal allowances. */
    public List<TravelAllowanceEntry> domesticMealAllowances() {
        return entries.stream()
                .filter(e -> "domestic".equalsIgnoreCase(e.category()))
                .sorted(Comparator.comparingDouble(TravelAllowanceEntry::minHours))
                .toList();
    }

    /** Kilometer reimbursement rate. */
    public Optional<Double> mileageRate() {
        return entries.stream()
                .filter(e -> "mileage".equalsIgnoreCase(e.category()))
                .map(TravelAllowanceEntry::allowanceEur)
                .findFirst();
    }

    /** Overnight accommodation allowance. */
    public Optional<Double> accommodationAllowance(boolean withReceipt) {
        return entries.stream()
                .filter(e -> "accommodation".equalsIgnoreCase(e.category()))
                .filter(e -> e.requiresOvernight() == withReceipt)
                .map(TravelAllowanceEntry::allowanceEur)
                .findFirst();
    }

    public record TravelAllowanceEntry(
            double minHours,
            Double maxHours,      // null = no upper bound
            double allowanceEur,
            String category,      // "domestic", "international", "mileage", "accommodation"
            boolean requiresOvernight,
            String description
    ) {}

    public String sourceDocument() { return sourceDocument; }
    public String regulation() { return regulation; }
    public LocalDate effectiveFrom() { return effectiveFrom; }
    public List<TravelAllowanceEntry> entries() { return List.copyOf(entries); }
    public int size() { return entries.size(); }
}
