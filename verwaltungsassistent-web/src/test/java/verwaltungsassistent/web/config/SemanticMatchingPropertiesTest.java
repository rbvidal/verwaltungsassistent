package verwaltungsassistent.web.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 2C.12a — Konfigurations-Objekt der kalibrierten Schwellen
 * (email.semantic-matching): Defaults 0.75/0.82, Override-Möglichkeit und
 * Validierung der Ordnung 0.0 <= suggestionFloor < highFloor <= 1.0.
 */
class SemanticMatchingPropertiesTest {

    @Test
    void defaults_areTheCalibratedValues() {
        SemanticMatchingProperties props = new SemanticMatchingProperties();

        assertEquals(0.75, props.getSuggestionFloor());
        assertEquals(0.82, props.getHighFloor());
    }

    @Test
    void operatorCanOverrideBothThresholds() {
        SemanticMatchingProperties props = new SemanticMatchingProperties();
        props.setSuggestionFloor(0.77);
        props.setHighFloor(0.85);

        assertEquals(0.77, props.getSuggestionFloor());
        assertEquals(0.85, props.getHighFloor());
    }

    @Test
    void unorderedThresholds_areRejected() {
        SemanticMatchingProperties props = new SemanticMatchingProperties();
        assertThrows(IllegalArgumentException.class, () -> props.setSuggestionFloor(0.83),
                "suggestionFloor muss unter highFloor bleiben");
        assertThrows(IllegalArgumentException.class, () -> props.setHighFloor(0.74),
                "highFloor muss über suggestionFloor liegen");
    }

    @Test
    void outOfRangeThresholds_areRejected() {
        SemanticMatchingProperties props = new SemanticMatchingProperties();
        assertThrows(IllegalArgumentException.class, () -> props.setSuggestionFloor(-0.1));
        assertThrows(IllegalArgumentException.class, () -> props.setHighFloor(1.5));
    }
}
