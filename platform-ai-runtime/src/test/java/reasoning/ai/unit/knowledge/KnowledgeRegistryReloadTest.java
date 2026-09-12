package reasoning.ai.unit.knowledge;

import reasoning.ai.knowledge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KnowledgeRegistry snapshot, clear, restore, and reload lifecycle.
 */
class KnowledgeRegistryReloadTest {

    private KnowledgeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new KnowledgeRegistry();
    }

    // ── Snapshot / restore ──

    @Test
    void shouldCreateSnapshotOfCurrentState() {
        registry.register(createSampleThresholdTable());

        KnowledgeRegistry.Snapshot snap = registry.snapshot();
        assertEquals(1, snap.thresholdTables().size());
        assertEquals(0, snap.salaryTables().size());
        assertEquals(0, snap.travelTables().size());
    }

    @Test
    void shouldRestoreFromSnapshotAfterClear() {
        registry.register(createSampleThresholdTable());
        KnowledgeRegistry.Snapshot snap = registry.snapshot();

        registry.clear();
        assertEquals(0, registry.totalTables());

        registry.restore(snap);
        assertEquals(1, registry.totalTables());
        assertEquals(1, registry.thresholdTables().size());
    }

    @Test
    void shouldRestoreFullStateFromSnapshot() {
        registry.register(createSampleThresholdTable());
        registry.register(createSampleSalaryTable());
        registry.register(createSampleTravelTable());
        registry.putMetadata("key", "value");

        assertEquals(3, registry.totalTables());
        KnowledgeRegistry.Snapshot snap = registry.snapshot();

        registry.clear();
        assertEquals(0, registry.totalTables());

        registry.restore(snap);
        assertEquals(3, registry.totalTables());
        assertEquals(1, registry.thresholdTables().size());
        assertEquals(1, registry.salaryTables().size());
        assertEquals(1, registry.travelTables().size());
        assertEquals("value", registry.metadata().get("key"));
    }

    // ── Clear ──

    @Test
    void shouldClearAllTables() {
        registry.register(createSampleThresholdTable());
        registry.register(createSampleSalaryTable());

        assertEquals(2, registry.totalTables());
        registry.clear();
        assertEquals(0, registry.totalTables());
        assertEquals(0, registry.totalSalaryEntries());
        assertEquals(0, registry.totalTravelEntries());
        assertEquals(0, registry.totalThresholdEntries());
    }

    @Test
    void shouldSucceedClearWhenAlreadyEmpty() {
        assertEquals(0, registry.totalTables());
        registry.clear();
        assertEquals(0, registry.totalTables());
    }

    // ── Snapshot isolation ──

    @Test
    void snapshotShouldBeImmutable() {
        registry.register(createSampleThresholdTable());
        KnowledgeRegistry.Snapshot snap = registry.snapshot();

        // Modify registry — snapshot should not change
        registry.clear();
        assertEquals(1, snap.thresholdTables().size());
        assertEquals(0, registry.totalTables());
    }

    @Test
    void shouldHandleMultipleSnapshotRestoreCycles() {
        registry.register(createSampleThresholdTable());
        KnowledgeRegistry.Snapshot snap1 = registry.snapshot();

        registry.clear();
        registry.register(createSampleSalaryTable());
        KnowledgeRegistry.Snapshot snap2 = registry.snapshot();

        // Restore snap1
        registry.restore(snap1);
        assertEquals(1, registry.totalTables());
        assertFalse(registry.findThresholdTable("AV §55 LHO").isEmpty());

        // Restore snap2
        registry.restore(snap2);
        assertEquals(1, registry.totalTables());
        assertFalse(registry.findSalaryTable("TV-L").isEmpty());
    }

    // ── Lookups after restore ──

    @Test
    void shouldSupportLookupsAfterRestore() {
        registry.register(createSampleThresholdTable());
        KnowledgeRegistry.Snapshot snap = registry.snapshot();
        registry.clear();
        registry.restore(snap);

        var found = registry.findThresholdTable("AV §55 LHO");
        assertTrue(found.isPresent());
        assertEquals(2, found.get().size());
    }

    @Test
    void shouldSupportLookupsAfterClear() {
        registry.register(createSampleThresholdTable());
        registry.clear();

        var found = registry.findThresholdTable("AV §55 LHO");
        assertTrue(found.isEmpty());
    }

    // ── Helpers ──

    private ThresholdTable createSampleThresholdTable() {
        ThresholdTable table = new ThresholdTable(
                "AV zu Paragraph 55 LHO Berlin", "AV §55 LHO",
                LocalDate.of(2024, 1, 1));
        table.addEntry(0, 500.0, "Kein formelles Verfahren",
                "Lieferung/Dienstleistung",
                List.of(), "Unter 500 €");
        table.addEntry(500.0, 1000.0, "Direktauftrag mit Genehmigung",
                "Lieferung/Dienstleistung",
                List.of(), "500-1000 €");
        return table;
    }

    private SalaryTable createSampleSalaryTable() {
        SalaryTable table = new SalaryTable("TV-L 2025", "TV-L",
                LocalDate.of(2025, 2, 1), null);
        table.addEntry("EG 9a", 3, 4117.53, 0, "");
        return table;
    }

    private TravelAllowanceTable createSampleTravelTable() {
        TravelAllowanceTable table = new TravelAllowanceTable("BRKG", "BRKG",
                LocalDate.of(2024, 1, 1));
        table.addEntry(8, 24.0, 6.0, "domestic", false, "Über 8 Stunden");
        return table;
    }
}
