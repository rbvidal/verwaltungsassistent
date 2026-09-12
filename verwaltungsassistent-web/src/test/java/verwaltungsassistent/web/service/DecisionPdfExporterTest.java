package verwaltungsassistent.web.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionPdfExporterTest {

    @Test
    void export_producesPdfWithCompleteFindingText() throws Exception {
        ChromiumPdfRenderer renderer = testRenderer();
        DecisionPdfExporter exporter = new DecisionPdfExporter(renderer);
        exporter.appVersion = "9.9.9-test";
        String fullClaim = "Die Gewerbeanmeldung muss vor Beginn des Betriebs erfolgen und es ist "
                + "unklar, welche spezifischen Unterlagen für Kapitalgesellschaften eingereicht werden "
                + "müssen - dieser Satz ist absichtlich länger als 120 Zeichen, damit der vollständige "
                + "Text die Ausgabe des gekürzten Labels übersteigt.";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseName", "Fall Gewerbeanmeldung");
        data.put("workspaceCode", "WS-001");
        data.put("casePhase", "Analyse");
        data.put("processingStatus", "In Prüfung");
        data.put("caseType", "Gewerbeanmeldung");
        data.put("documentCount", 2);
        data.put("documentNames", List.of("Gewerbeanmeldung.pdf", "Personalausweis.pdf"));
        data.put("requestedAt", "18.08.2026 10:00");
        data.put("generatedAt", "18.08.2026 12:00");
        data.put("confidenceScore", "87 %");
        data.put("decisionAnswer", "Die Gewerbeanmeldung sollte bewilligt werden.");
        data.put("grounded", true);
        data.put("caseOwnerDisplay", "Max Mustermann");
        data.put("caseOwnerRole", "Sachbearbeitung Bürgerdienste");
        data.put("caseOwnerRoom", "Zimmer: 2.15");
        data.put("caseOwnerPhone", "0331 / 90295-1234");
        data.put("caseOwnerEmail", "max.mustermann@ba-nordstadt.berlin.de");
        data.put("analysisQuestion", "Analysiere den Fall \"Fall Gewerbeanmeldung\": Erstelle 1) Faktenzusammenfassung, 2) anwendbare Vorschriften, 3) Handlungsschritte, 4) Risiken, 5) Empfehlung.");
        data.put("primaryFindings", List.of(Map.of(
                "label", fullClaim.substring(0, 120) + "...",
                "description", fullClaim)));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of(
                Map.of("label", "Gründungsunterlagen", "role", "ESTABLISHING_DOCUMENT"),
                Map.of("label", "Schriftverkehr", "role", "CORRESPONDENCE")));
        data.put("coverageIssues", List.of(Map.of(
                "label", "Begrenzte Abdeckung", "text", "Einige Aspekte konnten nicht durch Quellen belegt werden.")));
        data.put("authorities", List.of(Map.of(
                "title", "Gewerbeordnung", "reference", "§ 14 GewO", "excerpt", "Wer ein Gewerbe beginnt, hat dies anzuzeigen.")));
        data.put("evidenceItems", List.of(Map.of("title", "info_gewerbe_anmelden.pdf", "pageNumber", 1)));
        data.put("proceduralFindings", List.of(Map.of(
                "label", "Prüfung abschließen", "description", "Prüfen Sie die Unterlagen auf Vollständigkeit.")));

        byte[] pdf = exporter.export(data);

        assertTrue(pdf.length > 1000, "PDF should contain content");
        assertTrue(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF"), "PDF magic header");

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            // The layout wraps long lines, so compare on normalized whitespace.
            String normalized = text.replaceAll("\\s+", " ").trim();
            // Referenz-Stil: Briefkopf Bezirksamt Nordstadt + Kontaktblock
            assertTrue(normalized.contains("BEZIRKSAMT"), "letterhead expected");
            assertTrue(normalized.contains("NORDSTADT"));
            assertTrue(normalized.contains("VON POTSDAM"));
            assertTrue(normalized.contains("INTERNES DOKUMENT – NUR FÜR DIE VERWALTUNG"),
                    "confidentiality note expected");
            assertTrue(normalized.contains("Vertrauliche Informationen – keine Weitergabe an Dritte"));
            assertTrue(normalized.contains("Bezirksamt Nordstadt von Potsdam"));
            assertTrue(normalized.contains("Zimmer: 2.15"), "right contact block expected");
            assertTrue(normalized.contains("0331 / 90295-1234"));
            // Titelkopf: Fallname als Titel + Dokumenttyp als Untertitel
            assertTrue(normalized.contains("Fall Gewerbeanmeldung"), "case name as title expected");
            assertTrue(normalized.contains("ENTSCHEIDUNGSVORLAGE (MASCHINELL ERSTELLT)"));
            // Fallart uses the normalized caseCategory, not the full case title.
            assertTrue(normalized.contains("Gewerbeanmeldung"), "case category expected");
            // Zweispaltige Metadaten-Tabelle
            assertTrue(normalized.contains("Vorgangsnummer:"));
            assertTrue(normalized.contains("WS-001"));
            assertTrue(normalized.contains("BEARBEITET VON"));
            assertTrue(normalized.contains("GESAMTKONFIDENZ"));
            assertTrue(normalized.contains("DOKUMENTE IM VORGANG"));
            // Nummerierte GROSSBUCHSTABEN-Abschnitte im Referenz-Stil.
            assertTrue(normalized.contains("1. EMPFEHLUNG"), "reference section 1 expected");
            assertTrue(normalized.contains("2. FESTSTELLUNGEN ZUM SACHVERHALT"),
                    "reference section 2 expected");
            assertTrue(normalized.contains("3. OFFENE PUNKTE UND FEHLENDE INFORMATIONEN"),
                    "reference section 3 expected");
            assertTrue(normalized.contains("4. EMPFOHLENE NÄCHSTE SCHRITTE"),
                    "reference section 4 expected");
            assertTrue(normalized.contains("PRÜF- UND FREIGABESTATUS"));
            assertTrue(normalized.contains("Kernfeststellungen"));
            // The complete finding text must appear uncapped AND exactly once
            // in the document (no duplicated passages).
            String fullClaimNormalized = fullClaim.replaceAll("\\s+", " ").trim();
            assertEquals(1, countOccurrences(normalized, fullClaimNormalized),
                    "finding text must appear exactly once, got: " + normalized);
            assertTrue(normalized.contains("Gründungsunterlagen"));
            assertTrue(normalized.contains("Seite 1/1"), "footer with page number expected");
            // Signaturzeilen
            assertTrue(normalized.contains("Geprüft von:"), "signature line expected");
            assertTrue(normalized.contains("Freigegeben von:"));
            assertTrue(normalized.contains("Verwaltungsassistent"),
                    "footer must show product name, got: " + normalized);
            // Fiktive Adresse (alle Daten fiktiv, keine echten Kontakte).
            assertTrue(normalized.contains("Fiktive Straße") && normalized.contains("14467 Potsdam"),
                    "fictional address expected");
            // Internal role identifiers must never leak into the exported document.
            assertFalse(normalized.contains("ESTABLISHING_DOCUMENT"));
            assertFalse(normalized.contains("CORRESPONDENCE"));
        }
    }

    /**
     * A finding whose description is identical to its label (the pipeline
     * frequently produces this) must not be rendered twice in the PDF.
     */
    @Test
    void export_findingWithIdenticalLabelAndDescription_rendersTextOnlyOnce() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(testRenderer());
        exporter.appVersion = "9.9.9-test";
        String claim = "Die Gewerbeanmeldung muss vor Beginn des Betriebs erfolgen.";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseName", "Fall Gewerbeanmeldung");
        data.put("workspaceCode", "WS-002");
        data.put("casePhase", "Analyse");
        data.put("caseType", "Gewerbeanmeldung");
        data.put("documentCount", 1);
        data.put("requestedAt", "18.08.2026 10:00");
        data.put("generatedAt", "18.08.2026 12:00");
        data.put("confidenceScore", "87 %");
        data.put("decisionAnswer", "Die Gewerbeanmeldung sollte bewilligt werden.");
        data.put("grounded", true);
        data.put("primaryFindings", List.of(Map.of(
                "label", claim, "description", claim)));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of());
        data.put("coverageIssues", List.of());
        data.put("authorities", List.of());
        data.put("evidenceItems", List.of());
        data.put("proceduralFindings", List.of());

        byte[] pdf = exporter.export(data);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String normalized = text.replaceAll("\\s+", " ").trim();
            assertEquals(1, countOccurrences(normalized, claim),
                    "identical label/description must appear exactly once, got: " + normalized);
        }
    }

    /**
     * Post-Phase-2B-Review §1: Wurde keine spezifische Rechtsgrundlage
     * abgerufen (structuralConfidence = 0), erklärt die PDF die reduzierte
     * Gesamtkonfidenz unter "Weitere Erkenntnisse" — die Mitarbeiterin sieht
     * nicht nur eine unerklärte Zahl. Das Konfidenzmodell selbst bleibt
     * unverändert (nur die Erläuterung wird ergänzt).
     */
    @Test
    void export_reducedStructuralConfidence_explainsConfidenceInPdf() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(testRenderer());
        exporter.appVersion = "9.9.9-test";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseName", "Fall Gewerbeanmeldung");
        data.put("workspaceCode", "WS-003");
        data.put("casePhase", "Analyse");
        data.put("caseType", "Gewerbeanmeldung");
        data.put("documentCount", 1);
        data.put("requestedAt", "18.08.2026 10:00");
        data.put("generatedAt", "18.08.2026 12:00");
        data.put("confidenceScore", "64 %");
        // Hohe Quellenlage/Abdeckung, aber KEINE spezifische Rechtsgrundlage:
        // genau die Konstellation aus dem Review-Befund (64 % trotz 94 %).
        data.put("confidence", new reasoning.ai.model.ConfidenceProfile(
                0.94, 0.90, 0.0, 1.0, 0.64, "Keine spezifische Rechtsgrundlage abgerufen."));
        data.put("decisionAnswer", "Die Gewerbeanmeldung sollte bewilligt werden.");
        data.put("grounded", true);
        data.put("primaryFindings", List.of(Map.of(
                "label", "Gewerbe anmeldepflichtig", "description", "Die Gewerbeanmeldung ist anzeigepflichtig.")));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of());
        data.put("coverageIssues", List.of());
        data.put("authorities", List.of());
        data.put("evidenceItems", List.of());
        data.put("proceduralFindings", List.of());

        byte[] pdf = exporter.export(data);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String normalized = text.replaceAll("\\s+", " ").trim();
            assertTrue(normalized.contains("Hinweis: Die Gesamtkonfidenz ist reduziert, da für einzelne "
                            + "Aussagen keine spezifische Rechtsgrundlage abgerufen wurde"),
                    "PDF muss die reduzierte Konfidenz erklären, got: " + normalized);
            assertTrue(normalized.contains("Nicht durch Belege gedeckte Punkte werden bei der "
                            + "Gesamtkonfidenz berücksichtigt"),
                    "PDF muss die Nicht-Belege-Berücksichtigung nennen, got: " + normalized);
        }
    }

    /**
     * Issue-5-Regression über den PRODUKTIONS-PFAD: Ein Analyse-Ergebnis, das
     * einen reinen Dokumentverweis ("info_gewerbe_anmelden.pdf, Abschnitt:
     * Erforderliche Unterlagen") als Kernfeststellung enthält, wird — genau wie
     * in DecisionWorkspaceController (persist/render/export) — VOR dem Export
     * durch den AnalysisResultSanitizer normalisiert. Das erzeugte PDF darf den
     * Verweis nicht als Feststellung zeigen; die substantielle Feststellung
     * bleibt erhalten.
     */
    @Test
    void export_sanitizedModel_doesNotContainDocumentReferenceAsFinding() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(testRenderer());
        exporter.appVersion = "9.9.9-test";
        String docReference = "- info_gewerbe_anmelden.pdf, Abschnitt: Erforderliche Unterlagen";
        String substantive = "Die Gewerbeanmeldung muss vor Beginn des Betriebs erfolgen.";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseName", "Fall Gewerbeanmeldung");
        data.put("workspaceCode", "WS-003");
        data.put("casePhase", "Analyse");
        data.put("caseType", "Gewerbeanmeldung");
        data.put("documentCount", 1);
        data.put("requestedAt", "18.08.2026 10:00");
        data.put("generatedAt", "18.08.2026 12:00");
        data.put("confidenceScore", "87 %");
        data.put("grounded", true);
        data.put("decisionAnswer", String.join("\n",
                "KURZANTWORT",
                "Die Gewerbeanmeldung ist erforderlich.",
                "",
                "ENTSCHEIDUNG",
                "Die Anmeldung ist vorzunehmen.",
                "",
                "RECHTSGRUNDLAGE",
                docReference,
                "",
                "VERFAHREN",
                "Unterlagen einreichen.",
                "",
                "NÄCHSTER SCHRITT",
                "Termin buchen."));
        data.put("primaryFindings", List.of(
                Map.of("label", docReference, "description", docReference),
                Map.of("label", substantive, "description", substantive)));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of());
        data.put("coverageIssues", List.of());
        data.put("authorities", List.of());
        data.put("evidenceItems", List.of(Map.of("title", "info_gewerbe_anmelden.pdf")));
        data.put("proceduralFindings", List.of());

        // Produktionsreihenfolge: Sanitizer läuft VOR dem Export (wie im Controller).
        new AnalysisResultSanitizer().sanitize(data);
        byte[] pdf = exporter.export(data);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String normalized = text.replaceAll("\\s+", " ").trim();
            assertFalse(normalized.contains("Abschnitt: Erforderliche Unterlagen"),
                    "pure document reference must not appear as a finding in the PDF");
            assertFalse(normalized.contains("info_gewerbe_anmelden.pdf, Abschnitt"),
                    "no naked document reference in the PDF");
            assertTrue(normalized.contains(substantive),
                    "the substantive finding stays in the PDF");
            assertFalse(normalized.contains("Rechtsgrundlage: - info_gewerbe_anmelden.pdf"),
                    "RECHTSGRUNDLAGE must not carry the document reference");
        }
    }

    /**
     * Issue 6: Die Entscheidungsvorlage dokumentiert die zugeordnete
     * Bürgerkommunikation im Abschnitt DOKUMENTE IM VORGANG als
     * "E-Mail: <Betreff>" — ein Fall ohne E-Mails bleibt ohne Referenz.
     */
    @Test
    void export_includesEmailReferencesInDocumentsSection() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(testRenderer());
        exporter.appVersion = "9.9.9-test";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseName", "Fall Verkehrsschild");
        data.put("workspaceCode", "WS-004");
        data.put("casePhase", "Analyse");
        data.put("caseType", "Allgemein");
        data.put("documentCount", 1);
        data.put("requestedAt", "18.08.2026 10:00");
        data.put("generatedAt", "18.08.2026 12:00");
        data.put("confidenceScore", "87 %");
        data.put("decisionAnswer", "Die Reparatur ist zu veranlassen.");
        data.put("grounded", true);
        data.put("primaryFindings", List.of(Map.of(
                "label", "Das Verkehrsschild ist beschädigt.", "description", "Das Verkehrsschild ist beschädigt.")));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of());
        data.put("coverageIssues", List.of());
        data.put("authorities", List.of());
        data.put("evidenceItems", List.of());
        data.put("proceduralFindings", List.of());
        data.put("documentNames", List.of());
        // Zwei zugeordnete E-Mails → zwei Referenzen; eine sehr lange Betreffzeile
        // wird für die Box gekürzt.
        data.put("emailReferences", List.of(
                "E-Mail: Verkehrsschild beschädigt",
                "E-Mail: " + "Sehr langer Betreff einer weiteren Rückmeldung ".repeat(3).trim()));

        byte[] pdf = exporter.export(data);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String normalized = text.replaceAll("\\s+", " ").trim();
            assertTrue(normalized.contains("E-Mail: Verkehrsschild beschädigt"),
                    "associated email subject must appear in the PDF, got: " + normalized);
            assertTrue(normalized.contains("E-Mail: Sehr langer Betreff"),
                    "long email subjects are truncated but present, got: " + normalized);
        }
    }

    /**
     * A label that is the truncated (…) prefix of the description (the
     * pipeline's 120-char label cap produces this) must not repeat the
     * sentence start in the PDF.
     */
    @Test
    void export_findingWithTruncatedLabelPrefix_rendersTextOnlyOnce() throws Exception {
        DecisionPdfExporter exporter = new DecisionPdfExporter(testRenderer());
        exporter.appVersion = "9.9.9-test";
        String claim = "Die Gewerbeanmeldung muss vor Beginn des Betriebs erfolgen und es ist "
                + "unklar, welche spezifischen Unterlagen für Kapitalgesellschaften eingereicht werden "
                + "müssen - dieser Satz ist absichtlich länger als 120 Zeichen, damit der vollständige "
                + "Text die Ausgabe des gekürzten Labels übersteigt.";
        String label = claim.substring(0, 120) + "…";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseName", "Fall Gewerbeanmeldung");
        data.put("workspaceCode", "WS-003");
        data.put("casePhase", "Analyse");
        data.put("caseType", "Gewerbeanmeldung");
        data.put("documentCount", 1);
        data.put("requestedAt", "18.08.2026 10:00");
        data.put("generatedAt", "18.08.2026 12:00");
        data.put("confidenceScore", "87 %");
        data.put("decisionAnswer", "Die Gewerbeanmeldung sollte bewilligt werden.");
        data.put("grounded", true);
        data.put("primaryFindings", List.of(Map.of(
                "label", label, "description", claim)));
        data.put("secondaryFindings", List.of());
        data.put("missingDocs", List.of());
        data.put("coverageIssues", List.of());
        data.put("authorities", List.of());
        data.put("evidenceItems", List.of());
        data.put("proceduralFindings", List.of());

        byte[] pdf = exporter.export(data);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String normalized = text.replaceAll("\\s+", " ").trim();
            String claimNormalized = claim.replaceAll("\\s+", " ").trim();
            // The full sentence appears once (via the bullet); the description
            // paragraph is suppressed because the label is its truncated prefix.
            assertEquals(1, countOccurrences(normalized, claimNormalized),
                    "truncated label prefix must not repeat the sentence, got: " + normalized);
        }
    }

    private static ChromiumPdfRenderer testRenderer() {
        return new ChromiumPdfRenderer("");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
