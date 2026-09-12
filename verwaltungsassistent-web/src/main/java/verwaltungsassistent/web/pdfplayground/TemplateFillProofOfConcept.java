package verwaltungsassistent.web.pdfplayground;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proof-of-Concept für das Befüllen der drei gelieferten Test-Vorlagen.
 *
 * <p>Jede Vorlage wird mit einem eigenen Mock-Modell gerendert, das die neuen
 * dynamischen Felder der Test-Vorlagen nutzt (Falltitel, BEARBEITET VON,
 * Dokumente im Vorgang, Karten, Empfehlung, Feststellungen, offene Punkte,
 * nächste Schritte). Die Vorlagen werden aus {@code target/pdf-playground/}
 * geladen; die Ergebnisse landen im selben Verzeichnis.
 *
 * <p>Aufruf (nach {@code mvn -q compile}):
 * <pre>
 * cd verwaltungsassistent-web
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;$(cat target/cp.txt)" verwaltungsassistent.web.pdfplayground.TemplateFillProofOfConcept
 * </pre>
 */
public final class TemplateFillProofOfConcept {

    public static void main(String[] args) throws Exception {
        Path out = Path.of("target", "pdf-playground").toAbsolutePath();
        Files.createDirectories(out);

        render(out, "template-green-final-test.pdf", PlaygroundTemplatePdfRenderer.TemplateKind.GREEN, greenModel());
        render(out, "template-blue-final-test.pdf", PlaygroundTemplatePdfRenderer.TemplateKind.BLUE, blueModel());
        render(out, "template-warning-final-test.pdf", PlaygroundTemplatePdfRenderer.TemplateKind.WARNING, warningModel());
        System.out.println("PoC-PDFs geschrieben nach " + out);
    }

    private static void render(Path dir, String name, PlaygroundTemplatePdfRenderer.TemplateKind kind,
                               Map<String, Object> model) throws Exception {
        byte[] pdf = PlaygroundTemplatePdfRenderer.render(kind, model);
        Files.write(dir.resolve(name), pdf);
        System.out.println("  " + name + "  <- " + kind + "  (" + pdf.length + " bytes)");
    }

    /** GREEN — quellenbelegter/grounded Fall (Gewerbeanmeldung). */
    private static Map<String, Object> greenModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseName", "Gewerbeanmeldung");
        m.put("casePhase", "Abgeschlossen");
        m.put("confidenceScore", "68 %");
        m.put("documentNames", List.of(
                "Gewerbeanmeldung.pdf",
                "Personalausweis.pdf",
                "Gesellschafterliste.pdf"));
        m.put("bearbeiter", "Max Mustermann");
        m.put("email", "max.mustermann@ba-nordstadt.berlin.de");
        m.put("vorgangsnummer", "VG-2025-08143");
        m.put("aktenzeichen", "A-2025-0043");
        m.put("fallart", "Gewerbeanmeldung");
        m.put("decisionAnswer",
                "Für die Gewerbeanmeldung sind ein Personaldokument und das Gewerbeanmeldeformular "
                        + "erforderlich. Die Anmeldung ist vor Betriebsbeginn bei der zuständigen Stelle "
                        + "einzureichen.");
        m.put("primaryFindings", List.of(
                "Die Gewerbeanmeldung ist gleichzeitig mit dem Beginn des Betriebs vorzunehmen.",
                "Das Formular kann online genutzt oder ausgedruckt eingereicht werden.",
                "Ein Personaldokument ist erforderlich."));
        m.put("secondaryFindings", List.of("Keine weiteren Erkenntnisse erforderlich"));
        m.put("openPoints", List.of("1. Gewerbeanmeldung vor dem 1. September einreichen"));
        m.put("nextSteps", List.of(
                "1. Gewerbeanmeldung vor Betriebsbeginn einreichen.",
                "2. Personaldokument bereithalten.",
                "3. Vollständigkeit der Unterlagen prüfen."));
        return m;
    }

    /** BLUE — normaler/informativer Fall (Ummeldung nach Umzug). */
    private static Map<String, Object> blueModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseName", "Ummeldung nach Umzug");
        m.put("casePhase", "Einrichtung");
        m.put("confidenceScore", "58 %");
        m.put("documentNames", List.of(
                "Meldeantrag.pdf",
                "Wohnungsgeberbestaetigung.pdf",
                "Personalausweis.pdf"));
        m.put("bearbeiter", "Max Mustermann");
        m.put("email", "max.mustermann@ba-nordstadt.berlin.de");
        m.put("vorgangsnummer", "VG-2025-08142");
        m.put("aktenzeichen", "A-2025-0042");
        m.put("fallart", "Ummeldung nach Umzug");
        m.put("decisionAnswer",
                "Für die Ummeldung ist die Anmeldung der neuen Wohnung innerhalb von zwei Wochen "
                        + "nach dem Einzug bei der Meldebehörde erforderlich. Die Erreichbarkeit der "
                        + "zuständigen Stelle sollte vorab geprüft werden.");
        m.put("primaryFindings", List.of(
                "Die neue Hauptwohnung ist fristgerecht anzumelden.",
                "Personaldokument und ggf. Wohnungsgeberbestätigung sind vorzulegen.",
                "Die Anmeldung muss innerhalb von zwei Wochen nach dem Einzug erfolgen."));
        m.put("secondaryFindings", List.of("Bearbeitungszeit der Meldebehörde nicht belegt"));
        m.put("openPoints", List.of("1. Termin im Bürgeramt vereinbaren"));
        m.put("nextSteps", List.of(
                "1. Termin im Bürgeramt vereinbaren.",
                "2. Erforderliche Unterlagen vollständig vorlegen.",
                "3. Ummeldung innerhalb der gesetzlichen Frist durchführen."));
        return m;
    }

    /** WARNING — fail-closed Fall (Wohngeldantrag). */
    private static Map<String, Object> warningModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseName", "Wohngeldantrag");
        m.put("casePhase", "Einrichtung");
        m.put("confidenceScore", "nicht bewertbar");
        m.put("documentNames", List.of(
                "Antrag_Wohngeld.pdf",
                "Einkommensnachweis.pdf",
                "Mietbescheinigung.pdf"));
        m.put("bearbeiter", "Max Mustermann");
        m.put("email", "max.mustermann@ba-nordstadt.berlin.de");
        m.put("vorgangsnummer", "VG-2025-08144");
        m.put("aktenzeichen", "A-2025-0044");
        m.put("fallart", "Wohngeldantrag");
        // Die Warning-Vorlage enthält den fail-closed Hinweistext bereits als statisches Artwork.
        m.put("decisionAnswer",
                "Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor.");
        m.put("primaryFindings", List.of());
        m.put("secondaryFindings", List.of());
        m.put("openPoints", List.of(
                "• Einkommensnachweise für alle Haushaltsmitglieder anfordern.",
                "• Mietvertrag oder Mietbescheinigung zur Wohnfläche einholen.",
                "• Angaben zu weiteren Förderungen/Benefits prüfen."));
        m.put("nextSteps", List.of(
                "1. Fehlende Einkommensnachweise anfordern.",
                "2. Mietvertrag oder Mietbescheinigung nachfordern.",
                "3. Angaben zu weiteren Förderungen prüfen."));
        return m;
    }
}
