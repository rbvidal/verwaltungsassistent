package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the analysis-result normalisation:
 * pure document references must not appear as Kernfeststellungen or in
 * RECHTSGRUNDLAGE; legitimate substantive findings mentioning a document
 * are kept; the Weitere-Erkenntnisse legal-basis deduplication still works.
 */
class AnalysisResultSanitizerTest {

    private final AnalysisResultSanitizer sanitizer = new AnalysisResultSanitizer();

    private Map<String, Object> finding(String label, String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("description", description);
        return f;
    }

    private Map<String, Object> result(List<Map<String, Object>> primary,
                                       List<Map<String, Object>> secondary,
                                       List<Map<String, Object>> authorities,
                                       String answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("primaryFindings", new ArrayList<>(primary));
        m.put("secondaryFindings", new ArrayList<>(secondary));
        m.put("authorities", new ArrayList<>(authorities));
        m.put("decisionAnswer", answer);
        m.put("evidenceItems", List.of(
                Map.of("title", "info_gewerbe_anmelden.pdf"),
                Map.of("title", "Gewerbeordnung (GewO).pdf")));
        return m;
    }

    private static final String ANSWER = String.join("\n",
            "KURZANTWORT",
            "Die Gewerbeanmeldung ist vor Beginn des Betriebs erforderlich.",
            "",
            "ENTSCHEIDUNG",
            "Die Anmeldung ist vorzunehmen.",
            "",
            "RECHTSGRUNDLAGE",
            "- Gewerbeordnung (GewO), § 2a, § 3",
            "",
            "VERFAHREN",
            "Die Unterlagen sind einzureichen.",
            "",
            "NÄCHSTER SCHRITT",
            "Termin buchen.");

    @Test
    void pureDocumentReference_removedFromPrimaryFindings() {
        Map<String, Object> m = result(
                List.of(finding("- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen",
                                "- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen"),
                        finding("Die Gewerbeanmeldung ist vor Betriebsbeginn erforderlich",
                                "Die Gewerbeanmeldung ist für eine Kapitalgesellschaft erforderlich.")),
                List.of(), List.of(), ANSWER);

        sanitizer.sanitize(m);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary = (List<Map<String, Object>>) m.get("primaryFindings");
        assertEquals(1, primary.size(), "pure document reference must be dropped");
        assertFalse(primary.get(0).get("label").toString().contains("info_gewerbe_anmelden.pdf"));
    }

    @Test
    void substantiveFindingMentioningDocument_retained() {
        Map<String, Object> m = result(
                List.of(finding("Unterlagen sind erforderlich",
                        "Laut info_gewerbe_anmelden.pdf sind Personalausweis und "
                                + "Zustimmungserklärung der Gesellschafter zwingend erforderlich; "
                                + "die Anmeldung muss vor Betriebsbeginn erfolgen.")),
                List.of(), List.of(), ANSWER);

        sanitizer.sanitize(m);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary = (List<Map<String, Object>>) m.get("primaryFindings");
        assertEquals(1, primary.size(), "substantive finding mentioning a document stays");
    }

    @Test
    void docReferenceInRechtsgrundlageSection_removed() {
        String answerWithDocRef = String.join("\n",
                "KURZANTWORT",
                "Die Gewerbeanmeldung ist erforderlich.",
                "",
                "ENTSCHEIDUNG",
                "Die Anmeldung ist vorzunehmen.",
                "",
                "RECHTSGRUNDLAGE",
                "- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen",
                "",
                "VERFAHREN",
                "Unterlagen einreichen.",
                "",
                "NÄCHSTER SCHRITT",
                "Termin buchen.");
        Map<String, Object> m = result(List.of(), List.of(), List.of(), answerWithDocRef);

        sanitizer.sanitize(m);

        String answer = (String) m.get("decisionAnswer");
        assertFalse(answer.contains("RECHTSGRUNDLAGE"),
                "RECHTSGRUNDLAGE consisting only of a document reference is removed");
        assertFalse(answer.contains("info_gewerbe_anmelden.pdf"));
    }

    @Test
    void legalBasisNotDuplicatedInWeitereErkenntnisse() {
        // Rechtsgrundlage, die KEIN Dokumenttitel ist (BMG): die deduplizierende
        // Regel "Weitere Erkenntnisse ≠ Rechtsgrundlage" muss isoliert greifen.
        String answer = String.join("\n",
                "KURZANTWORT",
                "Die Ummeldung ist erfolgt.",
                "",
                "ENTSCHEIDUNG",
                "Die Ummeldung ist rechtzeitig angemeldet.",
                "",
                "RECHTSGRUNDLAGE",
                "- Bundesmeldegesetz (BMG), § 17 Abs. 1, § 19",
                "",
                "VERFAHREN",
                "Meldebestätigung übergeben.",
                "",
                "NÄCHSTER SCHRITT",
                "Vorgang dokumentieren.");
        Map<String, Object> m = result(
                List.of(),
                List.of(finding("Bundesmeldegesetz (BMG), § 17 Abs. 1, § 19",
                                "Bundesmeldegesetz (BMG), § 17 Abs. 1, § 19"),
                        finding("Meldebestätigung übergeben",
                                "Die Meldebestätigung wurde der Bürgerin übergeben.")),
                List.of(Map.of("title", "Bundesmeldegesetz (BMG)", "reference", "§ 17 Abs. 1, § 19")),
                answer);

        sanitizer.sanitize(m);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondary = (List<Map<String, Object>>) m.get("secondaryFindings");
        assertEquals(1, secondary.size(), "legal-basis duplicate is dropped");
        assertTrue(secondary.get(0).get("label").toString().contains("Meldebestätigung übergeben"));
    }

    @Test
    void authorityThatIsOnlyADocumentReference_removed() {
        Map<String, Object> m = result(
                List.of(),
                List.of(),
                List.of(Map.of("title", "Gewerbeordnung (GewO)", "reference", "§ 2a, § 3"),
                        Map.of("title", "info_gewerbe_anmelden.pdf", "reference", "")),
                ANSWER);

        sanitizer.sanitize(m);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> authorities = (List<Map<String, Object>>) m.get("authorities");
        assertEquals(1, authorities.size());
        assertEquals("Gewerbeordnung (GewO)", authorities.get(0).get("title"));
    }

    /**
     * Das AKTUELLE Problem-Muster aus der Praxis — "info_gewerbe_anmelden.pdf,
     * Abschnitt: Erforderliche Unterlagen" — muss auch dann entfernt werden,
     * wenn der Dokumenttitel NICHT in der Belegliste steht (z. B. weil die
     * Evidenz leer ist): reine Dateinamen-Referenzen sind kein Befund.
     */
    @Test
    void pureFilenameReference_removedEvenWithoutKnownTitle() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("primaryFindings", new ArrayList<>(List.of(
                finding("- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen",
                        "- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen"),
                finding("Gewerbeanmeldung ist vor Betriebsbeginn erforderlich",
                        "Die Gewerbeanmeldung ist für eine Kapitalgesellschaft erforderlich."))));
        m.put("secondaryFindings", new ArrayList<>());
        m.put("authorities", new ArrayList<>());
        m.put("decisionAnswer", String.join("\n",
                "KURZANTWORT",
                "Die Gewerbeanmeldung ist erforderlich.",
                "",
                "ENTSCHEIDUNG",
                "Die Anmeldung ist vorzunehmen.",
                "",
                "RECHTSGRUNDLAGE",
                "- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen",
                "",
                "VERFAHREN",
                "Unterlagen einreichen.",
                "",
                "NÄCHSTER SCHRITT",
                "Termin buchen."));
        // Bewusst KEINE evidenceItems/documentNames — die Belegliste ist leer.

        sanitizer.sanitize(m);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary = (List<Map<String, Object>>) m.get("primaryFindings");
        assertEquals(1, primary.size(), "filename-only reference is dropped from Kernfeststellungen");
        assertFalse(primary.get(0).get("label").toString().contains("info_gewerbe_anmelden.pdf"));
        String answer = (String) m.get("decisionAnswer");
        assertFalse(answer.contains("RECHTSGRUNDLAGE"),
                "RECHTSGRUNDLAGE consisting only of a filename reference is removed");
    }

    /** Evidenz-Zustands-Boilerplate ("Keine Dokumente gefunden.") ist kein substantieller Befund. */
    @Test
    void evidenceStateBoilerplate_removedFromFindingsAndRechtsgrundlage() {
        String answer = String.join("\n",
                "KURZANTWORT",
                "Eine Straßenlaterne in der Lehnitzer Straße 12 ist defekt.",
                "",
                "ENTSCHEIDUNG",
                "Die Reparatur ist zu veranlassen.",
                "",
                "RECHTSGRUNDLAGE",
                "- Keine Dokumente gefunden.",
                "",
                "VERFAHREN",
                "Die Techniker informieren.",
                "",
                "NÄCHSTER SCHRITT",
                "Inspektion veranlassen.");
        Map<String, Object> m = result(
                List.of(finding("- Keine Dokumente gefunden.", "- Keine Dokumente gefunden."),
                        finding("Straßenlaterne defekt", "Die Straßenlaterne ist seit drei Nächten ausgefallen.")),
                List.of(), List.of(), answer);

        sanitizer.sanitize(m);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary = (List<Map<String, Object>>) m.get("primaryFindings");
        assertEquals(1, primary.size(), "evidence-state boilerplate is not a substantive finding");
        assertTrue(primary.get(0).get("label").toString().contains("Straßenlaterne defekt"));
        String cleaned = (String) m.get("decisionAnswer");
        assertFalse(cleaned.contains("Keine Dokumente gefunden."),
                "RECHTSGRUNDLAGE must not contain evidence-state boilerplate");
    }

    @Test
    void sanitize_isIdempotent() {
        Map<String, Object> m = result(
                List.of(finding("- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen",
                                "- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen"),
                        finding("Die Gewerbeanmeldung ist erforderlich", "Die Anmeldung ist erforderlich.")),
                List.of(finding("Gewerbeordnung (GewO), § 2a, § 3", "Gewerbeordnung (GewO), § 2a, § 3")),
                List.of(Map.of("title", "info_gewerbe_anmelden.pdf", "reference", "")),
                ANSWER);

        sanitizer.sanitize(m);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primaryAfterFirst = (List<Map<String, Object>>) m.get("primaryFindings");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondaryAfterFirst = (List<Map<String, Object>>) m.get("secondaryFindings");

        sanitizer.sanitize(m);

        assertEquals(primaryAfterFirst.size(), ((List<?>) m.get("primaryFindings")).size());
        assertEquals(secondaryAfterFirst.size(), ((List<?>) m.get("secondaryFindings")).size());
        assertEquals(1, primaryAfterFirst.size());
        assertEquals(0, secondaryAfterFirst.size());
    }
}
