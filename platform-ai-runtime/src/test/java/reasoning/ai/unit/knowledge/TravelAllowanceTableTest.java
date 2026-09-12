package reasoning.ai.unit.knowledge;

import reasoning.ai.knowledge.TravelAllowanceTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TravelAllowanceTableTest {

    private TravelAllowanceTable brkg;

    @BeforeEach
    void setUp() {
        brkg = new TravelAllowanceTable("Bundesreisekostengesetz (BRKG)", "BRKG",
                LocalDate.of(2024, 1, 1));
        // Meal allowances
        brkg.addEntry(8, 24.0, 6.0, "domestic", false,
                "Abwesenheit über 8 Stunden");
        brkg.addEntry(11, 24.0, 12.0, "domestic", false,
                "Abwesenheit über 11 Stunden");
        brkg.addEntry(24, null, 24.0, "domestic", false,
                "Voller 24-Stunden-Tag");
        brkg.addEntry(0, 24.0, 12.0, "domestic", true,
                "An- und Abreisetag mit Übernachtung");
        // Mileage
        brkg.addEntry(0, null, 0.35, "mileage", false,
                "Kilometerpauschale PKW");
        brkg.addEntry(0, null, 0.20, "mileage", false,
                "Kilometerpauschale sonstiges KFZ");
        // Accommodation
        brkg.addEntry(0, null, 80.0, "accommodation", true,
                "Übernachtung mit Beleg");
        brkg.addEntry(0, null, 20.0, "accommodation", false,
                "Übernachtung pauschal ohne Beleg");
    }

    // ── Meal allowance lookup ──

    @Test
    void shouldLookup8HourMealAllowance() {
        var entry = brkg.lookup(9.0, false, "domestic");
        assertTrue(entry.isPresent());
        assertEquals(6.0, entry.get().allowanceEur(), 0.01);
        assertEquals("Abwesenheit über 8 Stunden", entry.get().description());
    }

    @Test
    void shouldLookup12HourMealAllowance() {
        var entry = brkg.lookup(12.0, false, "domestic");
        assertTrue(entry.isPresent());
        assertEquals(12.0, entry.get().allowanceEur(), 0.01);
    }

    @Test
    void shouldLookupFullDayMealAllowance() {
        var entry = brkg.lookup(24.0, false, "domestic");
        assertTrue(entry.isPresent());
        assertEquals(24.0, entry.get().allowanceEur(), 0.01);
    }

    @Test
    void shouldLookupWithOvernightStay() {
        var entry = brkg.lookup(10.0, true, "domestic");
        assertTrue(entry.isPresent());
        assertEquals(12.0, entry.get().allowanceEur(), 0.01);
    }

    @Test
    void shouldReturnEmptyForBelowThreshold() {
        var entry = brkg.lookup(5.0, false, "domestic");
        assertTrue(entry.isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingCategory() {
        var entry = brkg.lookup(10.0, false, "international");
        assertTrue(entry.isEmpty());
    }

    // ── Domestic meal allowances ──

    @Test
    void shouldReturnOrderedMealAllowances() {
        var allowances = brkg.domesticMealAllowances();
        assertTrue(allowances.size() >= 3);
        // Should be ordered by minHours
        assertTrue(allowances.get(0).minHours() <= allowances.get(1).minHours());
    }

    // ── Mileage ──

    @Test
    void shouldReturnMileageRate() {
        var rate = brkg.mileageRate();
        assertTrue(rate.isPresent());
        assertEquals(0.35, rate.get(), 0.01);
    }

    // ── Accommodation ──

    @Test
    void shouldReturnAccommodationWithReceipt() {
        var allowance = brkg.accommodationAllowance(true);
        assertTrue(allowance.isPresent());
        assertEquals(80.0, allowance.get(), 0.01);
    }

    @Test
    void shouldReturnAccommodationWithoutReceipt() {
        var allowance = brkg.accommodationAllowance(false);
        assertTrue(allowance.isPresent());
        assertEquals(20.0, allowance.get(), 0.01);
    }

    // ── Metadata ──

    @Test
    void shouldReturnMetadata() {
        assertEquals("Bundesreisekostengesetz (BRKG)", brkg.sourceDocument());
        assertEquals("BRKG", brkg.regulation());
        assertEquals(LocalDate.of(2024, 1, 1), brkg.effectiveFrom());
        assertEquals(8, brkg.size());
    }

    @Test
    void shouldReturnImmutableEntries() {
        var entries = brkg.entries();
        assertEquals(8, entries.size());
        assertThrows(UnsupportedOperationException.class, () ->
                entries.add(entries.get(0)));
    }
}
