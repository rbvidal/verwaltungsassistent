package verwaltungsassistent.web.pdfplayground;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Isoliertes PDF-Playground — kein Spring, keine Datenbank, nur {@code main()}.
 *
 * <p>Demonstriert das reine Vorlagen-Overlay-Prinzip der Entscheidungsvorlage: Die drei
 * gelieferten Einseiten-Vorlagen (blue/green/warning) sind das unveränderliche Design;
 * die dynamischen Fall- und Analysedaten werden über {@link PlaygroundTemplatePdfRenderer}
 * in die vorgesehenen Bereiche überlagert. Drei repräsentative Modelle (grounded / normal /
 * fail-closed) werden gerendert und unter {@code target/pdf-playground/} abgelegt — zum
 * schnellen visuellen Abgleich gegen die Vorlagen.
 *
 * <p>Aufruf (nach {@code mvn -q -pl verwaltungsassistent-web compile}):
 * <pre>
 * cd verwaltungsassistent-web
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/cp.txt" verwaltungsassistent.web.pdfplayground.EntscheidungPdfPlayground
 * </pre>
 */
public final class EntscheidungPdfPlayground {

    public static void main(String[] args) throws Exception {
        Path out = Path.of("target", "pdf-playground").toAbsolutePath();
        Files.createDirectories(out);

        renderAndWrite(out, "entscheidung-green.pdf", greenModel());
        renderAndWrite(out, "entscheidung-blue.pdf", blueModel());
        renderAndWrite(out, "entscheidung-warning.pdf", warningModel());
        System.out.println("Playground-PDFs geschrieben nach " + out);
    }

    private static void renderAndWrite(Path dir, String name, Map<String, Object> model)
            throws Exception {
        PlaygroundTemplatePdfRenderer.TemplateKind kind = selectTemplate(model);
        byte[] pdf = PlaygroundTemplatePdfRenderer.render(kind, model);
        Files.write(dir.resolve(name), pdf);
        System.out.println("  " + name + "  <- " + kind + "  (" + pdf.length + " bytes)");
    }

    private static PlaygroundTemplatePdfRenderer.TemplateKind selectTemplate(Map<String, Object> model) {
        boolean grounded = Boolean.TRUE.equals(model.get("grounded"));
        String answer = String.valueOf(model.getOrDefault("decisionAnswer", ""));
        boolean insufficientAnswer = answer.contains("keine ausreichenden Informationen");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary = (List<Map<String, Object>>) model.getOrDefault("primaryFindings", List.of());
        boolean failClosed = insufficientAnswer || (!grounded && primary.isEmpty());

        if (grounded && !failClosed) return PlaygroundTemplatePdfRenderer.TemplateKind.GREEN;
        if (failClosed) return PlaygroundTemplatePdfRenderer.TemplateKind.WARNING;
        return PlaygroundTemplatePdfRenderer.TemplateKind.BLUE;
    }

    // ── Representative models (structure like DecisionWorkspaceController) ──

    private static Map<String, Object> baseModel(String caseName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseName", caseName);
        m.put("casePhase", "Einrichtung");
        m.put("documentCount", "1");
        m.put("confidenceScore", "68 %");
        return m;
    }

    private static Map<String, Object> greenModel() {
        Map<String, Object> m = baseModel("Gewerbeanmeldung");
        m.put("grounded", true);
        m.put("confidenceScore", "68 %");
        m.put("decisionAnswer", "Für die Gewerbeanmeldung sind ein Personaldokument und das "
                + "Gewerbeanmeldeformular erforderlich. Die Anmeldung ist vor Betriebsbeginn "
                + "bei der zuständigen Stelle einzureichen.");
        m.put("primaryFindings", findings(
                "Gewerbeanmeldung bei Beginn des Betriebs erforderlich",
                "Die Gewerbeanmeldung ist gleichzeitig mit dem Beginn des Betriebs vorzunehmen.",
                "Personaldokument und Gewerbeanmeldeformular notwendig",
                "Das Formular kann online genutzt oder ausgedruckt eingereicht werden."));
        m.put("secondaryFindings", findings(
                "Keine weiteren Erkenntnisse erforderlich", ""));
        m.put("missingDocs", List.of());
        m.put("coverageIssues", List.of());
        m.put("proceduralFindings", findings(
                "Gewerbeanmeldung vor dem 1. September einreichen", ""));
        return m;
    }

    private static Map<String, Object> blueModel() {
        Map<String, Object> m = baseModel("Ummeldung nach Umzug");
        m.put("grounded", false);
        m.put("confidenceScore", "58 %");
        m.put("decisionAnswer", "Für die Ummeldung ist die Anmeldung der neuen Wohnung innerhalb "
                + "von zwei Wochen nach dem Einzug bei der Meldebehörde erforderlich. Die "
                + "Erreichbarkeit der zuständigen Stelle sollte vorab geprüft werden.");
        m.put("primaryFindings", findings(
                "Anmeldung innerhalb von zwei Wochen nach Einzug",
                "Die neue Hauptwohnung ist fristgerecht anzumelden.",
                "Unterlagen zur Anmeldung erforderlich",
                "Personaldokument und ggf. Wohnungsgeberbestätigung."));
        m.put("secondaryFindings", findings(
                "Bearbeitungszeit der Meldebehörde nicht belegt", ""));
        m.put("missingDocs", List.of());
        m.put("coverageIssues", List.of());
        m.put("proceduralFindings", findings(
                "Termin im Bürgeramt vereinbaren", ""));
        return m;
    }

    private static Map<String, Object> warningModel() {
        Map<String, Object> m = baseModel("Ummeldung nach Umzug");
        m.put("grounded", false);
        m.put("confidenceScore", "nicht bewertbar");
        m.put("decisionAnswer", "Für diese Frage liegen in der Wissensbasis keine ausreichenden "
                + "Informationen vor.");
        m.put("primaryFindings", List.of());
        m.put("secondaryFindings", List.of());
        m.put("missingDocs", List.of());
        m.put("coverageIssues", List.of(
                Map.of("text", "Identität und aktuelle Anschrift anhand der vorgelegten Unterlagen prüfen."),
                Map.of("text", "Prüfen, ob weitere Nachweise für die Ummeldung erforderlich sind."),
                Map.of("text", "Falls Angaben fehlen, diese bei der antragstellenden Person nachfordern.")));
        m.put("proceduralFindings", List.of());
        return m;
    }

    private static List<Map<String, Object>> findings(String... labelDescPairs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i + 1 < labelDescPairs.length; i += 2) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("label", labelDescPairs[i]);
            f.put("description", labelDescPairs[i + 1]);
            out.add(f);
        }
        return out;
    }
}
