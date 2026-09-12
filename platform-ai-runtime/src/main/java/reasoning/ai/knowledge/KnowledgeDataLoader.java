package reasoning.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Generic structured-knowledge loading utility.
 *
 * <p>The core provides the registry and model classes (SalaryTable,
 * TravelAllowanceTable, ThresholdTable). Domain-specific data is
 * supplied by the application via {@link #loadFrom(KnowledgeRegistry,
 * java.util.List, java.util.List, java.util.List)} or by calling the
 * individual register methods.
 *
 * <p>Municipal data (BRKG, TV-L, AV §55 LHO) has been moved to the
 * municipal module's {@code MunicipalKnowledgeInitializer}.
 */
public class KnowledgeDataLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDataLoader.class);

    private KnowledgeDataLoader() {} // utility class

    /**
     * Loads all supplied tables into the registry atomically.
     * Callers supply the domain-specific data.
     */
    public static void loadFrom(KnowledgeRegistry registry,
                                 java.util.List<SalaryTable> salaryTables,
                                 java.util.List<TravelAllowanceTable> travelTables,
                                 java.util.List<ThresholdTable> thresholdTables) {
        log.info("Loading structured knowledge tables...");
        salaryTables.forEach(registry::register);
        travelTables.forEach(registry::register);
        thresholdTables.forEach(registry::register);
        log.info("Knowledge base loaded: {} salary entries, {} travel entries, {} thresholds",
                registry.totalSalaryEntries(), registry.totalTravelEntries(),
                registry.totalThresholdEntries());
    }

    /** Reloads knowledge atomically — snapshots, clears, reloads, or restores on failure. */
    public static void reload(KnowledgeRegistry registry,
                               java.util.List<SalaryTable> salaryTables,
                               java.util.List<TravelAllowanceTable> travelTables,
                               java.util.List<ThresholdTable> thresholdTables) {
        KnowledgeRegistry.Snapshot snapshot = registry.snapshot();
        log.info("Reloading knowledge tables...");
        try {
            registry.clear();
            loadFrom(registry, salaryTables, travelTables, thresholdTables);
        } catch (Exception e) {
            log.error("Knowledge reload failed — rolling back", e);
            registry.restore(snapshot);
            throw new IllegalStateException("Knowledge reload failed; restored. Cause: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> summary(KnowledgeRegistry registry) {
        return Map.of(
                "salaryTables", registry.totalTables() > 0 ? 1 : 0,
                "salaryEntries", registry.totalSalaryEntries(),
                "travelTables", 1,
                "travelEntries", registry.totalTravelEntries(),
                "thresholdTables", 1,
                "thresholdEntries", registry.totalThresholdEntries(),
                "totalTables", registry.totalTables());
    }
}
