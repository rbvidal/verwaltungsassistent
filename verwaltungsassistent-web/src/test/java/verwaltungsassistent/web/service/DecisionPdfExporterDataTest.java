package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPdfExporterDataTest {

    @Test
    void templateData_mapsDocuments() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(new ChromiumPdfRenderer(""));
        exporter.appVersion = "9.9.9-test";

        Map<String, Object> model = Map.of(
                "caseName", "Test",
                "workspaceCode", "WS-1",
                "documentNames", List.of("A.pdf", "B.pdf"),
                "primaryFindings", List.of(),
                "secondaryFindings", List.of(),
                "missingDocs", List.of(),
                "coverageIssues", List.of(),
                "proceduralFindings", List.of()
        );

        Method m = DecisionPdfExporter.class.getDeclaredMethod("buildTemplateData", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) m.invoke(exporter, model);

        @SuppressWarnings("unchecked")
        List<String> docs = (List<String>) data.get("documents");
        assertEquals(List.of("A.pdf", "B.pdf"), docs);
    }

    @Test
    void templateData_parsesSemanticAnswerSections() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(new ChromiumPdfRenderer(""));
        exporter.appVersion = "9.9.9-test";

        String answer = """
                KURZANTWORT
                Kurzantworttext.

                ENTSCHEIDUNG
                Die Antragstellung ist zulässig.

                RECHTSGRUNDLAGE
                - § 14 GewO
                - § 35 VwVfG

                VERFAHREN
                Antrag prüfen.

                NÄCHSTER SCHRITT
                Bescheid erlassen.
                """;

        Map<String, Object> model = Map.of(
                "caseName", "Test",
                "workspaceCode", "WS-1",
                "documentNames", List.of(),
                "primaryFindings", List.of(),
                "secondaryFindings", List.of(),
                "missingDocs", List.of(),
                "coverageIssues", List.of(),
                "proceduralFindings", List.of(),
                "decisionAnswer", answer
        );

        Method m = DecisionPdfExporter.class.getDeclaredMethod("buildTemplateData", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) m.invoke(exporter, model);

        assertEquals("Kurzantworttext.", data.get("kurzantwort"));
        assertEquals("Die Antragstellung ist zulässig.", data.get("entscheidung"));
        assertEquals("- § 14 GewO\n- § 35 VwVfG", data.get("rechtsgrundlage"));
        assertEquals("", data.get("recommendation"));

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) data.get("naechsteSchritte");
        assertTrue(steps.contains("Antrag prüfen."), "VERFAHREN should become a next step");
        assertTrue(steps.contains("Bescheid erlassen."), "NÄCHSTER SCHRITT should become a next step");
        assertFalse(steps.stream().anyMatch(s -> s.contains("Entscheidungsvorlage prüfen")),
                "generic fallback must not replace real answer sections");
    }
}
