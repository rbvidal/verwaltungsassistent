package verwaltungsassistent.web.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 2A: YAML-Referenzkonfiguration (config/processing-times.yml) —
 * bekannte Kategorien liefern die Baseline, unbekannte fallen auf "allgemein"
 * zurück, das Lern-Intervall wird gelesen und die Datei ist reine
 * Lese-Konfiguration (kein Schreibpfad).
 */
class ProcessingTimeConfigTest {

    private final ProcessingTimeConfig config = new ProcessingTimeConfig();

    @Test
    void knownCategories_returnConfiguredBaselines() {
        assertEquals(45, config.baselineMinutes("wohngeld"));
        assertEquals(30, config.baselineMinutes("gewerbeanmeldung"));
        assertEquals(15, config.baselineMinutes("ummeldung"));
        assertEquals(20, config.baselineMinutes("reisepass"));
        assertEquals(60, config.baselineMinutes("baugenehmigung"));
        assertEquals(30, config.baselineMinutes("geovorgang"));
        assertEquals(30, config.baselineMinutes("allgemein"));
    }

    @Test
    void caseInsensitiveAndNormalizedLookup() {
        assertEquals(45, config.baselineMinutes("Wohngeld"));
        assertEquals(45, config.baselineMinutes("  wohngeld  "));
    }

    @Test
    void unknownCategory_fallsBackToAllgemein() {
        assertEquals(30, config.baselineMinutes("völlig unbekannt"));
        assertEquals(30, config.baselineMinutes(null));
    }

    @Test
    void learningInterval_isReadFromYaml() {
        assertEquals(5, config.learningInterval());
    }

    @Test
    void baselines_areReadOnlySnapshot() {
        assertFalse(config.baselines().isEmpty());
        // Modifikation der Kopie darf die Konfiguration nicht ändern.
        try {
            config.baselines().clear();
        } catch (UnsupportedOperationException expected) {
            // unveränderliche Kopie
        }
        assertEquals(45, config.baselineMinutes("wohngeld"));
    }
}
