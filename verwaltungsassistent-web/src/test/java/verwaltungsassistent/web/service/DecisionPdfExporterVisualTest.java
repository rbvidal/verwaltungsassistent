package verwaltungsassistent.web.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class DecisionPdfExporterVisualTest {

    @Test
    void export_samplePdfForVisualInspection(@TempDir Path tempDir) throws Exception {
        ChromiumPdfRenderer renderer = new ChromiumPdfRenderer("");
        DecisionPdfExporter exporter = new DecisionPdfExporter(renderer);
        exporter.appVersion = "1.0.0-RC2";
        String fullClaim = "Die Gewerbeanmeldung muss vor Beginn des Betriebs erfolgen und es ist "
                + "unklar, welche spezifischen Unterlagen für Kapitalgesellschaften eingereicht werden "
                + "müssen - dieser Satz ist absichtlich länger als 120 Zeichen, damit der vollständige "
                + "Text die Ausgabe des gekürzten Labels übersteigt.";

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("caseName", "Gewerbeanmeldung");
        data.put("workspaceCode", "WS-2026-0001");
        data.put("casePhase", "Entscheidung");
        data.put("caseType", "Gewerbeanmeldung");
        data.put("documentCount", 2);
        data.put("requestedAt", "26.08.2026 10:00");
        data.put("generatedAt", "26.08.2026 12:00");
        data.put("confidenceScore", "61 %");
        data.put("decisionAnswer", "Die Gewerbeanmeldung kann bewilligt werden, sofern die vollständigen Unterlagen vorliegen.");
        data.put("grounded", false);
        data.put("primaryFindings", List.of(Map.of(
                "label", fullClaim.substring(0, 120) + "...",
                "description", fullClaim)));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of("Handelsregisterauszug", "Gesellschafterliste"));
        data.put("coverageIssues", List.of("Begrenzte Quellenlage: Rechtsgrundlagen konnten nicht vollständig abgedeckt werden."));
        data.put("authorities", List.of());
        data.put("evidenceItems", List.of(Map.of("title", "info_gewerbe_anmelden.pdf", "pageNumber", 1)));

        byte[] pdf = exporter.export(data);
        Path out = tempDir.resolve("gewerbeanmeldung-entscheidung.pdf");
        Files.write(out, pdf);
        // Also write a copy next to the module for visual inspection.
        Path fixed = Path.of("target/gewerbeanmeldung-entscheidung.pdf").toAbsolutePath();
        Files.createDirectories(fixed.getParent());
        Files.write(fixed, pdf);

        try (PDDocument doc = PDDocument.load(pdf)) {
            System.out.println("PDF pages: " + doc.getNumberOfPages());
        }
    }
}
