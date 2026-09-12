package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.service.DocumentViewerService.SourceView;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fragment-level tests for the clickable "Durch Quellen belegt" badge, the
 * enriched provenance fields and the shared progress-panel active-line
 * marker. The assistant answer with citations is covered by
 * AssistantProgressLifecycleTest because it needs a web context for @{...}
 * links; corpus evaluation renders with literal URLs and can be checked here
 * with a plain context.
 */
class SourcesDialogFragmentTest {

    private TemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setDialect(new SpringStandardDialect());
        return engine;
    }

    private SourceView sourceView() {
        return new SourceView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                3, "AV zu §55 LHO Berlin", 4,
                "Beschaffungen zwischen 1.000 € und 10.000 € können als Direktauftrag vergeben werden.",
                0.95, "Primär", "success", List.of("Keyword", "Vektor"),
                23, "Direktaufträge nach §55 LHO", "PDF", "Vertrag",
                "Vollständiger Abschnittstext mit allen Einzelheiten zum Direktauftrag.");
    }

    @Test
    void corpusEvaluation_withCitations_rendersClickableBadgeAndRealProvenance() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("evalQuestion", "Darf der Direktauftrag verwendet werden?");
        ctx.setVariable("evalAnswer", "Ja, unterhalb der Schwellenwerte.");
        ctx.setVariable("evalGrounded", true);
        // Die Auswertung liefert die DOKUMENT-GRUPPIERTE Beleg-Sicht
        // (groupByDocument) — der Quellen-Dialog erwartet chunkIds.
        ctx.setVariable("evalCitations", List.of(new verwaltungsassistent.web.service.DocumentViewerService.SourceGroupView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "AV zu §55 LHO Berlin", 3, 4,
                "Beschaffungen zwischen 1.000 € und 10.000 € können als Direktauftrag vergeben werden.",
                0.95, "PDF", "Vertrag",
                List.of("22222222-2222-2222-2222-222222222222"), 1)));
        ctx.setVariable("evalStrategy", "Regelbasiert");
        ctx.setVariable("evalExpectedKeywords", List.of("Direktauftrag"));
        ctx.setVariable("evalKeywordHits", List.of("Direktauftrag"));

        String html = engine().process("corpus/fragments", Set.of("caseEvaluation"), ctx);

        assertTrue(html.contains("Durch Quellen belegt"), "badge must be present for grounded answers");
        assertTrue(html.contains("Quellen anzeigen"), "badge must invite opening the sources");
        assertTrue(html.contains("badge--clickable"), "badge must be rendered as a control");
        assertTrue(html.contains("modal-overlay"), "sources dialog must be rendered");
        assertTrue(html.contains("AV zu §55 LHO Berlin"), "source title must be real provenance");
        assertTrue(html.contains("Version 3"), "document version must be shown");
        assertTrue(html.contains("S. 4"), "page number must be shown");
        assertTrue(html.contains("Beschaffungen zwischen 1.000 € und 10.000 €"), "excerpt must be shown");
        assertTrue(html.contains("0,95"), "confidence must be shown in German number format");
        assertTrue(html.contains("PDF"), "document type must be shown");
        assertTrue(html.contains("Vertrag"), "document category must be shown");
        assertTrue(html.contains("11111111-1111-1111-1111-111111111111"), "document id must be shown");
        assertTrue(html.contains("href=\"/documents/11111111-1111-1111-1111-111111111111\""),
                "document link must point to the real document");
    }

    @Test
    void corpusEvaluation_groundedWithoutCitations_doesNotClaimSources() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("evalQuestion", "Frage?");
        ctx.setVariable("evalAnswer", "Antwort");
        ctx.setVariable("evalGrounded", true);
        ctx.setVariable("evalCitations", List.of());
        ctx.setVariable("evalStrategy", "Regelbasiert");
        ctx.setVariable("evalExpectedKeywords", List.of());
        ctx.setVariable("evalKeywordHits", List.of());

        String html = engine().process("corpus/fragments", Set.of("caseEvaluation"), ctx);

        assertFalse(html.contains("Durch Quellen belegt"),
                "must not claim sources without evidence");
        assertFalse(html.contains("modal-overlay"), "no dialog without sources");
        // Regelbasierter Pfad ohne Dokument-Zitate: ehrliches Badge
        // "Aus strukturiertem Wissen belegt" statt erfundener Quellen.
        assertTrue(html.contains("Aus strukturiertem Wissen belegt"),
                "the rule path shows the honest structured-knowledge badge");
    }

    @Test
    void assistantAnswer_groundedWithoutCitations_doesNotClaimSources() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("answered", true);
        ctx.setVariable("answerText", "Für diese Frage liegen keine ausreichenden Informationen vor.");
        ctx.setVariable("grounded", true);
        ctx.setVariable("strategy", "Hybride Suche");
        ctx.setVariable("citations", List.of());
        ctx.setVariable("authorities", List.of());

        String html = engine().process("assistant/fragments", Set.of("answer"), ctx);

        assertFalse(html.contains("Durch Quellen belegt"),
                "must not claim sources without evidence");
        assertFalse(html.contains("modal-overlay"), "no dialog without sources");
        assertTrue(html.contains("Eingeschränkte Quellenlage"), "honest fallback badge");
    }

    @Test
    void sharedProgressPanel_marksActiveLineForAnimation() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("title", "E-Mail wird analysiert");
        ctx.setVariable("messages", List.of("Die E-Mail wird gelesen …",
                "Anliegen und Fachbereich werden erkannt …"));
        ctx.setVariable("pollUrl", "/emails/analyze/progress/x");
        ctx.setVariable("emptyMessage", "Die E-Mail wird entgegengenommen …");
        ctx.setVariable("hint", "Die Bearbeitung kann einen Moment dauern.");
        ctx.setVariable("error", null);

        String html = engine().process("fragments/progress", Set.of("progressPanel"), ctx);

        assertTrue(html.contains("progress-terminal__line--active"),
                "active stage line must be marked for the dots animation");
        assertTrue(html.contains("progress-terminal__message"),
                "stage message spans must be addressable");
        assertTrue(html.contains("Anliegen und Fachbereich werden erkannt"),
                "existing stage text must be preserved");
    }

    @Test
    void sharedProgressPanel_emptyMessages_marksEmptyLineAsActive() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("title", "E-Mail wird analysiert");
        ctx.setVariable("messages", null);
        ctx.setVariable("pollUrl", "/emails/analyze/progress/x");
        ctx.setVariable("emptyMessage", "Die E-Mail wird entgegengenommen …");
        ctx.setVariable("hint", "Die Bearbeitung kann einen Moment dauern.");
        ctx.setVariable("error", null);

        String html = engine().process("fragments/progress", Set.of("progressPanel"), ctx);

        assertTrue(html.contains("progress-terminal__line--active"),
                "the empty-state line must animate while no stage message exists yet");
        assertTrue(html.contains("Die E-Mail wird entgegengenommen"));
    }
}
