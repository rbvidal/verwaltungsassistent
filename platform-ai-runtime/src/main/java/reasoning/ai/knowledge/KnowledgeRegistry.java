package reasoning.ai.knowledge;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Central registry of structured knowledge.
 *
 * <p>Legacy typed tables (SalaryTable/TravelAllowanceTable/ThresholdTable)
 * are registered at startup by KnowledgeDataLoader. Additionally, when the
 * generic persistence-backed store is present and
 * {@code knowledge.extraction.enabled=true}, ACTIVE generic structured
 * knowledge items can be looked up by kind/domain/key/as-of — the domain-
 * agnostic path the deterministic engine will eventually consume.
 */
@Component
public class KnowledgeRegistry {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRegistry.class);

    private final List<SalaryTable> salaryTables = new ArrayList<>();
    private final List<TravelAllowanceTable> travelTables = new ArrayList<>();
    private final List<ThresholdTable> thresholdTables = new ArrayList<>();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private StructuredKnowledgeStore knowledgeStore;
    private boolean extractionEnabled;

    // ── Registration ──

    public void register(SalaryTable table) {
        salaryTables.add(table);
        log.info("Registered SalaryTable: {} ({} grades, valid from {})",
                table.sourceDocument(), table.size(), table.effectiveFrom());
    }

    public void register(TravelAllowanceTable table) {
        travelTables.add(table);
        log.info("Registered TravelAllowanceTable: {} ({} entries, valid from {})",
                table.sourceDocument(), table.size(), table.effectiveFrom());
    }

    public void register(ThresholdTable table) {
        thresholdTables.add(table);
        log.info("Registered ThresholdTable: {} ({} entries, valid from {})",
                table.sourceDocument(), table.size(), table.effectiveFrom());
    }

    public void putMetadata(String key, String value) {
        metadata.put(key, value);
    }

    // ── Generic persisted knowledge (opt-in, domain-agnostic) ──

    @Autowired(required = false)
    public void setStructuredKnowledgeStore(StructuredKnowledgeStore store) {
        this.knowledgeStore = store;
    }

    @Autowired(required = false)
    public void setExtractionEnabled(@Value("${knowledge.extraction.enabled:false}") boolean enabled) {
        this.extractionEnabled = enabled;
    }

    public boolean isExtractionEnabled() {
        return extractionEnabled && knowledgeStore != null;
    }

    /** All ACTIVE generic items of a kind within a domain (empty when extraction is disabled). */
    public List<StructuredKnowledgeItem> findActive(String kind, String domain) {
        if (!isExtractionEnabled()) return List.of();
        // Safety boundary: the deterministic engine may only ever see ACTIVE
        // items. The store filters by status; this defense-in-depth re-checks
        // so CANDIDATE/REJECTED/SUPERSEDED can never influence a decision.
        return knowledgeStore.findActive(kind, domain).stream()
                .filter(i -> i.status() == StructuredKnowledgeStatus.ACTIVE)
                .toList();
    }

    /**
     * Domain-agnostic temporal lookup: the ACTIVE item of the given kind/domain
     * matching {@code key}, applicable at {@code asOf} (default today), preferring
     * the highest effectiveFrom. Never contains municipal knowledge — key and
     * domain are data supplied by the extracted source document.
     */
    public Optional<StructuredKnowledgeItem> lookupActive(
            String kind, String domain, String key, LocalDate asOf) {
        if (!isExtractionEnabled()) return Optional.empty();
        LocalDate effective = asOf != null ? asOf : LocalDate.now();
        return knowledgeStore.findActive(kind, domain).stream()
                .filter(i -> i.status() == StructuredKnowledgeStatus.ACTIVE)
                .filter(i -> key == null || key.equalsIgnoreCase(i.key()))
                .filter(i -> i.effectiveFrom() == null || !i.effectiveFrom().isAfter(effective))
                .filter(i -> i.effectiveUntil() == null || !i.effectiveUntil().isBefore(effective))
                .max(Comparator.comparing(StructuredKnowledgeItem::effectiveFrom,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    // ── Lookups ──

    /** Finds the most recent salary table for a given pay scale. */
    public Optional<SalaryTable> findSalaryTable(String payScale) {
        return salaryTables.stream()
                .filter(t -> t.payScale().equalsIgnoreCase(payScale))
                .max(Comparator.comparing(SalaryTable::effectiveFrom));
    }

    /** Finds the most recent travel allowance table for a regulation. */
    public Optional<TravelAllowanceTable> findTravelTable(String regulation) {
        return travelTables.stream()
                .filter(t -> t.regulation().equalsIgnoreCase(regulation))
                .max(Comparator.comparing(TravelAllowanceTable::effectiveFrom));
    }

    /** Finds the most recent threshold table for a regulation. */
    public Optional<ThresholdTable> findThresholdTable(String regulation) {
        return thresholdTables.stream()
                .filter(t -> t.regulation().equalsIgnoreCase(regulation))
                .max(Comparator.comparing(ThresholdTable::effectiveFrom));
    }

    // ── Stats ──

    public int totalSalaryEntries() {
        return salaryTables.stream().mapToInt(SalaryTable::size).sum();
    }

    public int totalTravelEntries() {
        return travelTables.stream().mapToInt(TravelAllowanceTable::size).sum();
    }

    public int totalThresholdEntries() {
        return thresholdTables.stream().mapToInt(ThresholdTable::size).sum();
    }

    public int totalTables() {
        return salaryTables.size() + travelTables.size() + thresholdTables.size();
    }

    public List<SalaryTable> salaryTables() { return List.copyOf(salaryTables); }
    public List<TravelAllowanceTable> travelTables() { return List.copyOf(travelTables); }
    public List<ThresholdTable> thresholdTables() { return List.copyOf(thresholdTables); }
    public Map<String, String> metadata() { return Map.copyOf(metadata); }

    // ── Runtime reload ──

    /**
     * Clears all registered tables. Used before an atomic reload.
     * Caller must ensure reload completes successfully; on failure,
     * a previously-saved snapshot should be restored.
     */
    public synchronized void clear() {
        salaryTables.clear();
        travelTables.clear();
        thresholdTables.clear();
        metadata.clear();
        log.info("KnowledgeRegistry cleared — {} salary, {} travel, {} threshold tables removed",
                0, 0, 0);
    }

    /**
     * Creates a snapshot of the current registry state for rollback.
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                List.copyOf(salaryTables),
                List.copyOf(travelTables),
                List.copyOf(thresholdTables),
                Map.copyOf(metadata));
    }

    /**
     * Restores the registry from a previously-taken snapshot (rollback).
     */
    public synchronized void restore(Snapshot snapshot) {
        salaryTables.clear();
        travelTables.clear();
        thresholdTables.clear();
        metadata.clear();
        salaryTables.addAll(snapshot.salaryTables);
        travelTables.addAll(snapshot.travelTables);
        thresholdTables.addAll(snapshot.thresholdTables);
        metadata.putAll(snapshot.metadata);
        log.info("KnowledgeRegistry restored from snapshot: {} tables",
                totalTables());
    }

    /** An immutable snapshot of the registry state. */
    public record Snapshot(
            List<SalaryTable> salaryTables,
            List<TravelAllowanceTable> travelTables,
            List<ThresholdTable> thresholdTables,
            Map<String, String> metadata) {}
}
