package verwaltungsassistent.web.pdfplayground;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Chromium/Playwright decision-template PDF PoC.
 *
 * <p>Fills the three HTML master templates (green, blue, ocker) with realistic
 * municipal mock data and renders each to PDF + PNG via Chromium/Playwright.
 *
 * <p>Run after {@code mvn -q compile}:
 * <pre>
 * cd verwaltungsassistent-web
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;$(cat target/cp.txt)" verwaltungsassistent.web.pdfplayground.ChromiumPdfProofOfConcept
 * </pre>
 */
public final class ChromiumPdfProofOfConcept {

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path templateDir = projectRoot.resolve("src/test/resources/html_templates");
        Path outDir = projectRoot.resolve("target/pdf-playground");
        Path nodeScript = outDir.resolve("render-chromium.js");

        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }
        if (!Files.exists(nodeScript)) {
            throw new IllegalStateException("Node-Renderer fehlt: " + nodeScript);
        }

        renderVariant(projectRoot, templateDir, outDir, nodeScript, "green",
                templateDir.resolve("entscheidungsvorlage-v2-green.html"),
                outDir.resolve("chromium-green-test.pdf"),
                outDir.resolve("chromium-green-test.png"),
                buildGreenData());

        renderVariant(projectRoot, templateDir, outDir, nodeScript, "blue",
                templateDir.resolve("entscheidungsvorlage-v3-blue.html"),
                outDir.resolve("chromium-blue-test.pdf"),
                outDir.resolve("chromium-blue-test.png"),
                buildBlueData());

        renderVariant(projectRoot, templateDir, outDir, nodeScript, "ocker",
                templateDir.resolve("entscheidungsvorlage-v1-ocker.html"),
                outDir.resolve("chromium-ocker-test.pdf"),
                outDir.resolve("chromium-ocker-test.png"),
                buildOckerData());

        System.out.println("Alle Chromium/Playwright-Varianten erzeugt.");
    }

    private static void renderVariant(Path projectRoot, Path templateDir, Path outDir, Path nodeScript,
                                      String variant, Path templateHtml, Path outputPdf, Path outputPng,
                                      DecisionTemplateData data) throws Exception {
        if (!Files.exists(templateHtml)) {
            throw new IllegalStateException("HTML-Vorlage fehlt: " + templateHtml);
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        Path dataJson = outDir.resolve("chromium-" + variant + "-data.json");
        mapper.writeValue(dataJson.toFile(), data);

        ProcessBuilder pb = new ProcessBuilder(
                "node", nodeScript.toString(),
                templateHtml.toString(),
                outputPdf.toString(),
                outputPng.toString(),
                dataJson.toString()
        );
        pb.directory(projectRoot.toFile());
        pb.inheritIO();

        Process process = pb.start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Rendering für " + variant + " fehlgeschlagen (Exit " + exit + ")");
        }

        if (!Files.exists(outputPdf) || !Files.exists(outputPng)) {
            throw new IllegalStateException("Ausgabedateien für " + variant + " fehlen");
        }
    }

    private static DecisionTemplateData buildGreenData() {
        DecisionTemplateData d = new DecisionTemplateData();
        d.setVariant("green");
        d.setCaseTitle("Gewerbeanmeldung");
        d.setProcessingStatus("Abgeschlossen");
        d.setConfidence("68 %");
        d.setDocuments(List.of("Gewerbeanmeldung.pdf", "Personalausweis.pdf", "Gesellschafterliste.pdf"));
        d.setProcessorName("Max Mustermann");
        d.setProcessorRole("Sachbearbeitung Bürgerdienste");
        d.setProcessorRoom("Zimmer: 2.15");
        d.setProcessorPhone("0331 / 90295-1234");
        d.setProcessorEmail("max.mustermann@ba-nordstadt.berlin.de");
        d.setVorgangsnummer("VG-2025-08143");
        d.setAktenzeichen("A-2025-0043");
        d.setFallart("Gewerbeanmeldung");
        d.setRecommendation("Für die Gewerbeanmeldung sind ein Personaldokument und das Gewerbeanmeldeformular erforderlich. " +
                "Die Anmeldung ist vor Betriebsbeginn bei der zuständigen Stelle einzureichen.");
        d.setKernfeststellungen(List.of(
                "Die Gewerbeanmeldung ist gleichzeitig mit dem Beginn des Betriebs vorzunehmen.",
                "Das Formular kann online genutzt oder ausgedruckt eingereicht werden.",
                "Ein Personaldokument ist erforderlich."
        ));
        d.setWeitereErkenntnisse(List.of("Keine weiteren Erkenntnisse erforderlich."));
        d.setOffenePunkte(List.of("Gewerbeanmeldung vor dem 1. September einreichen."));
        d.setNaechsteSchritte(List.of(
                "Gewerbeanmeldung vor Betriebsbeginn einreichen.",
                "Personaldokument bereithalten.",
                "Vollständigkeit der Unterlagen prüfen."
        ));
        d.setFooterPage("Seite 1/1");
        d.setExportDate("28.08.2026");
        return d;
    }

    private static DecisionTemplateData buildBlueData() {
        DecisionTemplateData d = new DecisionTemplateData();
        d.setVariant("blue");
        d.setCaseTitle("Ummeldung nach Umzug");
        d.setProcessingStatus("In Prüfung");
        d.setConfidence("61 %");
        d.setDocuments(List.of("Ummeldungsantrag.pdf", "Meldebescheinigung.pdf", "Wohnungsgeberbestätigung.pdf"));
        d.setProcessorName("Erika Musterfrau");
        d.setProcessorRole("Sachbearbeitung Bürgerdienste");
        d.setProcessorRoom("Zimmer: 1.07");
        d.setProcessorPhone("0331 / 90295-5678");
        d.setProcessorEmail("erika.musterfrau@ba-nordstadt.berlin.de");
        d.setVorgangsnummer("VG-2025-08192");
        d.setAktenzeichen("A-2025-0092");
        d.setFallart("Ummeldung");
        d.setRecommendation("Die Ummeldung kann vorgenommen werden, sobald die Wohnungsgeberbestätigung vollständig und " +
                "plausibel ist. Eine Prüfung der Meldeadresse gegen das Einwohnermeldeverzeichnis ist empfohlen.");
        d.setKernfeststellungen(List.of(
                "Der Antragsteller hat einen neuen Hauptwohnsitz angegeben.",
                "Die Wohnungsgeberbestätigung liegt als Scan vor.",
                "Eine Abgleichung mit dem Melderegister steht noch aus."
        ));
        d.setWeitereErkenntnisse(List.of("Keine weiteren Erkenntnisse erforderlich."));
        d.setOffenePunkte(List.of(
                "Wohnungsgeberbestätigung auf Vollständigkeit prüfen.",
                "Melderegisterabgleich durchführen."
        ));
        d.setNaechsteSchritte(List.of(
                "Fehlende Angaben beim Antragsteller nachfragen.",
                "Nach erfolgreicher Prüfung Ummeldung bestätigen.",
                "Historische Meldedaten archivieren."
        ));
        d.setFooterPage("Seite 1/1");
        d.setExportDate("28.08.2026");
        return d;
    }

    private static DecisionTemplateData buildOckerData() {
        DecisionTemplateData d = new DecisionTemplateData();
        d.setVariant("ocker");
        d.setCaseTitle("Sondernutzungserlaubnis");
        d.setProcessingStatus("In Bearbeitung");
        d.setConfidence("42 %");
        d.setDocuments(List.of("Antrag_Sondernutzung.pdf"));
        d.setProcessorName("Hans Beispiel");
        d.setProcessorRole("Sachbearbeitung Ordnungsamt");
        d.setProcessorRoom("Zimmer: 3.22");
        d.setProcessorPhone("0331 / 90295-9012");
        d.setProcessorEmail("hans.beispiel@ba-nordstadt.berlin.de");
        d.setVorgangsnummer("VG-2025-08211");
        d.setAktenzeichen("A-2025-0156");
        d.setFallart("Sondernutzung");
        d.setRecommendation("Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor. " +
                "Eine manuelle Recherche im Fachverfahren sowie ggf. beim Ordnungsamt ist erforderlich.");
        d.setKernfeststellungen(List.of(
                "Es liegt lediglich ein unvollständiger Antrag vor.",
                "Rechtsgrundlagen zur Sondernutzung öffentlicher Flächen sind nicht im Korpus erfasst."
        ));
        d.setWeitereErkenntnisse(List.of("Keine maschinell verwertbaren Erkenntnisse."));
        d.setOffenePunkte(List.of(
                "Vollständiger Antrag mit Lageplan einholen.",
                "Zuständige Fachstelle ermitteln."
        ));
        d.setNaechsteSchritte(List.of(
                "Antragsteller um ergänzende Unterlagen bitten.",
                "Nach Eingabe der Unterlagen erneut prüfen.",
                "Ggf. Anhörung der Nachbarn vorsehen."
        ));
        d.setFooterPage("Seite 1/1");
        d.setExportDate("28.08.2026");
        return d;
    }
}
