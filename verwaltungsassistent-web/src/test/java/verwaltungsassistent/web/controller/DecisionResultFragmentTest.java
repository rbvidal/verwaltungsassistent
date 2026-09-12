package verwaltungsassistent.web.controller;

import reasoning.ai.model.ConfidenceProfile;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused test for the completion state of the decision result fragment:
 * after a successful analysis the PDF action is immediately available
 * (prominent "PDF öffnen" in the result header). Without a job id (e.g. a
 * restored legacy run) the inline action is not rendered — the page-level
 * summary keeps the export link there.
 */
class DecisionResultFragmentTest {

    private static TemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setDialect(new SpringStandardDialect());
        return engine;
    }

    private Context baseContext(String jobId) {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("analysisComplete", true);
        ctx.setVariable("caseId", "case-1");
        ctx.setVariable("caseName", "Ummeldung nach Umzug");
        ctx.setVariable("jobId", jobId);
        // Phase 2D.3: Abschnitt "Entscheidung der Sachbearbeitung" — im
        // Standard-Kontext ist die Entscheidung noch offen und der Renderer
        // kein Leitungs-Konto (die Aktion ist sichtbar).
        ctx.setVariable("supervisory", false);
        ctx.setVariable("decisionInfo", null);
        // Nur der Live-Abschluss-Render öffnet die PDF automatisch; ein
        // wiederhergestelltes Ergebnis (ohne autoOpenPdf) darf es nicht.
        ctx.setVariable("autoOpenPdf", jobId != null);
        ctx.setVariable("decisionAnswer", "KURZANTWORT\nDie Ummeldung ist erfolgt.");
        ctx.setVariable("confidence", new ConfidenceProfile(
                0.8, 0.7, 0.75, 0.7, 0.76, "geprüft"));
        ctx.setVariable("confidenceScore", "76%");
        ctx.setVariable("overallFillClass", "quality-indicator__fill--high");
        ctx.setVariable("sourceFillClass", "quality-indicator__fill--high");
        ctx.setVariable("completenessFillClass", "quality-indicator__fill--high");
        ctx.setVariable("coverageFillClass", "quality-indicator__fill--high");
        ctx.setVariable("grounded", true);
        ctx.setVariable("primaryFindings", List.of());
        ctx.setVariable("secondaryFindings", List.of());
        ctx.setVariable("proceduralFindings", List.of());
        ctx.setVariable("evidenceItems", List.of());
        ctx.setVariable("authorities", List.of());
        ctx.setVariable("coverageIssues", List.of());
        ctx.setVariable("missingDocs", List.of());
        return ctx;
    }

    @Test
    void completedAnalysis_offersPdfActionInTheResultHeader() {
        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"),
                baseContext("job-42"));

        assertTrue(html.contains("PDF öffnen"), "completion state offers the PDF action immediately");
        // Issue 2: ÖFFNEN (inline) statt Download — der Browser zeigt die PDF an,
        // es erscheint kein Speichern-Dialog.
        assertTrue(html.contains("export-pdf?job=job-42&amp;inline=true"),
                "the PDF action opens the PDF inline (browser viewer), not as download");
        // Issue 7: erst NACH erfolgreichem Abschluss wird die fertige PDF in
        // einem neuen Browser-Tab geöffnet (_blank) — beim Start der Analyse
        // wird KEIN zweiter Tab vorab erzeugt (der reservierte Tab
        // "entscheidungspdf" existiert nicht mehr).
        assertTrue(html.contains("window.open(url, '_blank')"),
                "the completion fragment opens the finished PDF in a new browser tab");
        assertTrue(html.contains("/decision/export-pdf?job="),
                "the auto-open script targets the finished job's PDF");
        assertTrue(html.contains("&amp;inline=true"),
                "the auto-open script uses the inline (display) endpoint");
    }

    @Test
    void restoredLegacyResult_withoutJobId_doesNotRenderInlinePdfAction() {
        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"),
                baseContext(null));

        assertFalse(html.contains("PDF öffnen"),
                "without a live job the inline PDF action stays hidden (page-level link remains)");
        assertFalse(html.contains("window.open(url, '_blank')"),
                "without a live job no auto-open script is rendered");
    }

    /**
     * Issue 1: Ein wiederhergestelltes Ergebnis, dessen persistiertes
     * Ergebnis eine jobId trägt, darf die PDF NICHT automatisch öffnen —
     * sonst öffnet z. B. die Entscheidungs-Seite während einer NEUEN
     * Analyse sofort einen ungewollten PDF-Tab. autoOpenPdf wird ausschließlich
     * im Live-Abschluss-Render gesetzt.
     */
    @Test
    void restoredResult_withJobId_doesNotAutoOpenPdf() {
        Context ctx = baseContext("job-42");
        ctx.setVariable("autoOpenPdf", false);
        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"), ctx);

        assertFalse(html.contains("window.open(url, '_blank')"),
                "a restored result must not auto-open the PDF tab");
        assertTrue(html.contains("PDF öffnen"),
                "the explicit PDF action remains available on a restored result");
    }

    /** Phase 2C.11: evidenceGroups tragen chunkIds — die Quell-Gruppe bietet
     *  „Quelle öffnen" mit exakter Chunk-Angabe; ohne chunkIds bleibt nur der
     *  ehrliche Dokument-Link (keine erfundene Passage). */
    @Test
    void evidenceGroup_withChunkIds_offersExactSourceLink() {
        String chunk = java.util.UUID.randomUUID().toString();
        String doc = java.util.UUID.randomUUID().toString();
        Context ctx = baseContext("job-1");
        Map<String, Object> group = new java.util.LinkedHashMap<>();
        group.put("documentId", doc);
        group.put("title", "Richtlinie Wohngeld");
        group.put("tier", "Primär");
        group.put("pageNumber", 3);
        group.put("chunkIds", chunk);
        group.put("items", List.of());
        // Der Abschnitt „Unterstützende Dokumente" ist über evidenceItems
        // gegated — beide Variablen sind nötig, damit die Gruppe gerendert wird.
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("documentId", doc);
        item.put("title", "Richtlinie Wohngeld");
        item.put("excerpt", "Auszug");
        item.put("chunkId", chunk);
        ctx.setVariable("evidenceItems", List.of(item));
        ctx.setVariable("evidenceGroups", List.of(group));
        ctx.setVariable("analysisHighlightQuery", "Wohngeld+Unterlagen");

        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"), ctx);

        assertTrue(html.contains("Quelle öffnen · S. 3"), "source action shows the page");
        assertTrue(html.contains("chunks=" + chunk), "viewer targets the evidence chunk");
        assertTrue(html.contains("/documents/" + doc + "/view"), "source document referenced");
    }

    /**
     * Phase 2D.3: Ohne dokumentierte Entscheidung zeigt der Abschnitt der
     * Sachbearbeitung den "Noch offen"-Zustand mit der Dokumentations-Aktion —
     * die Empfehlung der KI (Abschnitte 1–8) bleibt klar davon getrennt.
     */
    @Test
    void openDecision_offersEmployeeConfirmAction() {
        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"),
                baseContext("job-1"));

        assertTrue(html.contains("Entscheidung der Sachbearbeitung"),
                "the employee decision is a separate, explicit step");
        assertTrue(html.contains("Noch nicht dokumentiert"),
                "the open state is communicated honestly");
        assertTrue(html.contains("Eigene Entscheidung festlegen"),
                "the employee establishes their OWN decision (separate from the AI recommendation)");
        assertTrue(html.contains("/cases/case-1/decision/confirm"),
                "the confirm action targets the guarded server endpoint");
    }

    /**
     * Phase 2D.3: Leitungs-Konten (supervisory, schreibgeschützt) sehen die
     * dokumentierte/offene Entscheidung, aber niemals die Dokumentations-Aktion.
     */
    @Test
    void supervisory_neverSeesTheConfirmAction() {
        Context ctx = baseContext("job-1");
        ctx.setVariable("supervisory", true);
        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"), ctx);

        assertTrue(html.contains("Noch nicht dokumentiert"),
                "the open decision state stays visible for the read-only Leitungs-Konto");
        assertFalse(html.contains("/cases/case-1/decision/confirm"),
                "read-only Leitungs-Konto gets no operational confirmation form (no confirm endpoint)");
        assertTrue(html.contains("Leitungs-Konten sind schreibgeschützt"),
                "the read-only state is explained");
    }

    /**
     * Phase 2D.3: Ist die Entscheidung bereits dokumentiert (wer, wann,
     * Analyse-Version), wird der Zustand angezeigt und die Aktion entfernt —
     * eine Dokumentation wird nie überschrieben.
     */
    @Test
    void documentedDecision_showsStateAndHidesAction() {
        Context ctx = baseContext("job-1");
        ctx.setVariable("decisionInfo", new DecisionWorkspaceController.DecisionInfo(
                "Erika Beispiel", "02.09.2026 11:05", 2));
        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"), ctx);

        assertTrue(html.contains("Entscheidung dokumentiert"),
                "the documented state badge is displayed");
        assertTrue(html.contains("Erika Beispiel"),
                "the deciding employee is named");
        assertTrue(html.contains("Analyse #2"),
                "the decision references the analysis version it is based on");
        assertFalse(html.contains("Noch nicht dokumentiert"),
                "the open state is replaced by the documented state");
        assertFalse(html.contains("/cases/case-1/decision/confirm"),
                "an already documented decision offers no confirmation form (no confirm endpoint)");
    }

    @Test
    void evidenceGroup_withoutChunkIds_opensDocumentWithoutFakePassage() {
        String doc = java.util.UUID.randomUUID().toString();
        Context ctx = baseContext("job-1");
        Map<String, Object> group = new java.util.LinkedHashMap<>();
        group.put("documentId", doc);
        group.put("title", "Richtlinie Wohngeld");
        group.put("tier", "Primär");
        group.put("pageNumber", 3);
        group.put("chunkIds", "");
        group.put("items", List.of());
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("documentId", doc);
        item.put("title", "Richtlinie Wohngeld");
        item.put("excerpt", "Auszug");
        item.put("chunkId", null);
        ctx.setVariable("evidenceItems", List.of(item));
        ctx.setVariable("evidenceGroups", List.of(group));
        ctx.setVariable("analysisHighlightQuery", "Wohngeld+Unterlagen");

        String html = engine().process("cases/decision-fragments", Set.of("decisionResult"), ctx);

        assertTrue(html.contains("Quelle öffnen · S. 3"), "fallback action still offered");
        assertFalse(html.contains("chunks="), "no fake passage targeting without chunks");
    }

    /**
     * Regression (Warteseite der Entscheidungsvorlage): Die Warte-Seite muss
     * BEIDES zeigen — die orange Pipeline-KNOTEN-Visualisierung (renderNodes,
     * pipeline-node-Klassen inkl. Verbindungslinien aus application.css) UND
     * die bisherigen Verarbeitungs-/Statusmeldungen (renderStages, „✓ … /
     * ▶ …"). Knoten und Meldungen sind komplementär, nicht austauschbar.
     */
    @Test
    void pdfWaitTemplate_showsPipelineNodesANDStatusMessages() throws Exception {
        String template = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/cases/pdf-wait.html"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(template.contains("function renderNodes"),
                "the pdf-wait page must render pipeline nodes from the job state");
        assertTrue(template.contains("pipeline-node pipeline-node--"),
                "node states (done/active/pending) reuse the shared pipeline CSS");
        assertFalse(template.contains("pipeline-node::after { display: none"),
                "the shared connecting lines between the nodes must stay visible");
        assertTrue(template.contains("function renderStages"),
                "the processing/status messages (✓ … / ▶ …) stay rendered beneath the nodes");
        assertTrue(template.contains("id=\"pdf-wait-stages\""),
                "the status-message list element is present");
        assertTrue(template.contains("renderStages(state.messages)"),
                "messages are refreshed together with the node states on every poll");
    }
}