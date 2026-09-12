package reasoning.ai.unit.knowledge;

import reasoning.ai.knowledge.SalaryTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SalaryTableTest {

    private SalaryTable current;
    private SalaryTable previous;

    @BeforeEach
    void setUp() {
        current = new SalaryTable("TV-L Entgelttabellen 2025", "TV-L",
                LocalDate.of(2025, 2, 1), null);
        current.addEntry("EG 9a", 1, 3500.00, 0, "");
        current.addEntry("EG 9a", 2, 3715.69, 0, "");
        current.addEntry("EG 9a", 3, 4117.53, 0, "");
        current.addEntry("EG 10", 2, 4231.36, 0, "");
        current.addEntry("EG 10", 3, 4400.00, 0, "Höchststufe");

        previous = new SalaryTable("TV-L Entgelttabellen 2024", "TV-L",
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 31));
        previous.addEntry("EG 9a", 3, 3700.00, 0, "");
        previous.addEntry("EG 10", 2, 4000.00, 0, "");
    }

    @Test
    void shouldLookupExactGradeAndStep() {
        var result = current.lookup("EG 9a", 3);
        assertTrue(result.isPresent());
        assertEquals(4117.53, result.get().monthlyAmount(), 0.01);
    }

    @Test
    void shouldReturnEmptyForMissingGrade() {
        var result = current.lookup("EG 15", 1);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingStep() {
        var result = current.lookup("EG 9a", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldFindByGrade() {
        var entries = current.findByGrade("EG 9a");
        assertEquals(3, entries.size());
        assertTrue(entries.get(0).step() < entries.get(1).step());
    }

    @Test
    void shouldReturnEmptyListForUnknownGrade() {
        var entries = current.findByGrade("EG 99");
        assertTrue(entries.isEmpty());
    }

    @Test
    void shouldComputeIncrease() {
        var increase = current.computeIncrease("EG 9a", 3, previous);
        assertTrue(increase.isPresent());
        assertEquals(3700.00, increase.get().oldAmount(), 0.01);
        assertEquals(4117.53, increase.get().newAmount(), 0.01);
        assertEquals(417.53, increase.get().increase(), 0.01);
        assertTrue(increase.get().increasePercent() > 10.0);
    }

    @Test
    void shouldReturnEmptyForMissingPreviousEntry() {
        var increase = current.computeIncrease("EG 9a", 1, previous);
        assertTrue(increase.isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingCurrentEntry() {
        var increase = current.computeIncrease("EG 15", 1, previous);
        assertTrue(increase.isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingBothTables() {
        SalaryTable empty = new SalaryTable("Empty", "TV-L", LocalDate.now(), null);
        var increase = current.computeIncrease("EG 9a", 3, empty);
        assertTrue(increase.isEmpty());
    }

    @Test
    void shouldReportSize() {
        assertEquals(5, current.size());
    }

    @Test
    void shouldReturnMetadata() {
        assertEquals("TV-L Entgelttabellen 2025", current.sourceDocument());
        assertEquals("TV-L", current.payScale());
        assertEquals(LocalDate.of(2025, 2, 1), current.effectiveFrom());
        assertNull(current.effectiveUntil());
    }

    @Test
    void shouldReturnPreviousEffectiveUntil() {
        assertEquals(LocalDate.of(2025, 1, 31), previous.effectiveUntil());
    }

    @Test
    void shouldContainNotesInEntries() {
        var entry = current.lookup("EG 10", 3);
        assertTrue(entry.isPresent());
        assertEquals("Höchststufe", entry.get().notes());
    }

    @Test
    void shouldReturnImmutableCopy() {
        var entries = current.entries();
        assertEquals(5, entries.size());
        assertThrows(UnsupportedOperationException.class, () ->
                entries.add(current.lookup("EG 9a", 1).get()));
    }
}
