package verwaltungsassistent.web.controller;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The analyze endpoint's asOf parameter stays a machine-readable ISO date
 * (yyyy-MM-dd); German UI formatting happens only at presentation time.
 */
class DecisionWorkspaceControllerAsOfTest {

    @Test
    void acceptsIsoDate() {
        assertEquals(LocalDate.of(2026, 8, 20),
                DecisionWorkspaceController.parseAsOf("2026-08-20"));
    }

    @Test
    void rejectsGermanFormattedDate() {
        assertNull(DecisionWorkspaceController.parseAsOf("20.08.2026"));
    }

    @Test
    void rejectsBlankOrMalformed() {
        assertNull(DecisionWorkspaceController.parseAsOf(null));
        assertNull(DecisionWorkspaceController.parseAsOf(""));
        assertNull(DecisionWorkspaceController.parseAsOf("2026-13-45"));
    }
}
