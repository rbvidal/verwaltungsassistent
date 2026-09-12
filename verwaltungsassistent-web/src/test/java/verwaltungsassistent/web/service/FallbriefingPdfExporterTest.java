package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2D.16 — Fallbriefing-Synopse: KURZANTWORT / ENTSCHEIDUNG / VERFAHREN /
 * NÄCHSTER SCHRITT werden aus der fortlaufenden Kurzfassung als EIGENE
 * Blöcke/Zeilen zerlegt (kein <br>-Trick im Fließtext). Freitext-Kurz-
 * fassungen bleiben unverändert ein Block.
 */
class FallbriefingPdfExporterTest {

    private static final String SECTIONED =
            "KURZANTWORT\nDie Laterne fällt aus.\n\nENTSCHEIDUNG\nEine Reparatur wird veranlasst.\n\n"
                    + "VERFAHREN\nPrüfung und Wartung.\n\nNÄCHSTER SCHRITT\nDie Abteilung repariert die Laterne.";

    @Test
    void parseSynopse_splitsSectionedKurzfassungIntoSeparateBlocks() {
        List<Map<String, String>> synopse = FallbriefingPdfExporter.parseSynopse(SECTIONED);

        assertEquals(4, synopse.size(), "four named sections become four blocks");
        assertEquals("KURZANTWORT", synopse.get(0).get("label"));
        assertEquals("Die Laterne fällt aus.", synopse.get(0).get("text"));
        assertEquals("ENTSCHEIDUNG", synopse.get(1).get("label"));
        assertEquals("VERFAHREN", synopse.get(2).get("label"));
        assertEquals("NÄCHSTER SCHRITT", synopse.get(3).get("label"));
        assertEquals("Die Abteilung repariert die Laterne.", synopse.get(3).get("text"));
    }

    @Test
    void parseSynopse_multilineTextIsPreservedWithinItsBlock() {
        List<Map<String, String>> synopse = FallbriefingPdfExporter.parseSynopse(
                "KURZANTWORT\nErste Zeile\nzweite Zeile.\n\nENTSCHEIDUNG\nEntscheidungstext.");

        assertEquals(2, synopse.size());
        assertTrue(synopse.get(0).get("text").contains("zweite Zeile."),
                "all lines of a section belong to that section's block");
    }

    @Test
    void parseSynopse_freeTextWithoutSections_staysOneBlock() {
        List<Map<String, String>> synopse = FallbriefingPdfExporter.parseSynopse(
                "Der Bürger meldete eine defekte Laterne; die Reparatur wurde veranlasst.");

        assertTrue(synopse.isEmpty(), "unstructured kurzfassung keeps the single-block rendering");
    }

    @Test
    void parseSynopse_lessThanTwoSections_fallsBackToSingleBlock() {
        List<Map<String, String>> synopse = FallbriefingPdfExporter.parseSynopse(
                "KURZANTWORT\nNur eine Kurzantwort ohne weitere Abschnitte.");

        assertTrue(synopse.isEmpty(), "a single stray heading must not produce a broken layout");
    }

    @Test
    void parseSynopse_headingTextOnSameLine_isNotMisparsedAsSection() {
        List<Map<String, String>> synopse = FallbriefingPdfExporter.parseSynopse(
                "Die Prüfung ergab: ENTSCHEIDUNG fällt hier nicht als Überschrift aus.");

        assertTrue(synopse.isEmpty(), "headings inside prose are not section markers");
    }
}
