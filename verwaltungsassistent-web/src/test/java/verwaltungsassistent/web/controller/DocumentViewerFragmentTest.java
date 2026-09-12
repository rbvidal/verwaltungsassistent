package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.service.DocumentViewerService.ChunkView;
import verwaltungsassistent.web.service.DocumentViewerService.DocumentView;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fragment-level tests for the reusable document viewer body
 * (documents/fragments :: documentViewer): metadata, chunk entries and the
 * no-chunks empty state. Rendered with a plain context like the other
 * fragment tests.
 */
class DocumentViewerFragmentTest {

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

    private DocumentView view(List<ChunkView> chunks) {
        return new DocumentView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Reisepass beantragen", "PDF", "Handbuch",
                "Bereit", "success", 2, "1,2 MB",
                "Abgeschlossen", "17.08.2026 10:00", "17.08.2026 12:30",
                "admin@verwaltungsassistent.local", "admin@verwaltungsassistent.local",
                null, null, false, false, chunks);
    }

    @Test
    void documentViewer_rendersMetadataAndChunks() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("view", view(List.of(
                new ChunkView(UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        0, 2, "Reisepass beantragen", 1,
                        "Der Antrag ist im Bürgeramt zu stellen.", null))));

        String html = engine().process("documents/fragments", Set.of("documentViewer"), ctx);

        assertTrue(html.contains("Reisepass beantragen"), "title must be shown");
        assertTrue(html.contains("PDF"), "type must be shown");
        assertTrue(html.contains("Handbuch"), "category must be shown");
        assertTrue(html.contains("Bereit"), "status must be shown");
        assertTrue(html.contains("Version 2"), "version must be shown");
        assertTrue(html.contains("Abgeschlossen"), "ingestion status must be shown");
        assertTrue(html.contains("Dokument-ID"), "document id must be labeled as metadata");
        assertTrue(html.contains("11111111-1111-1111-1111-111111111111"), "uuid as metadata");
        assertTrue(html.contains("1,2 MB"), "file size must be shown");
        assertTrue(html.contains("17.08.2026 10:00"), "created date must be shown");
        assertTrue(html.contains("Abschnitt 0"), "chunk index must be shown");
        assertTrue(html.contains("Seite 2"), "chunk page must be shown");
        assertTrue(html.contains("Der Antrag ist im Bürgeramt zu stellen."), "chunk text must be shown");
    }

    @Test
    void documentViewer_withoutChunks_showsHonestEmptyState() {
        Context ctx = new Context(Locale.GERMANY);
        ctx.setVariable("view", view(List.of()));

        String html = engine().process("documents/fragments", Set.of("documentViewer"), ctx);

        assertTrue(html.contains("keine indexierten Textabschnitte"),
                "no chunks must show an honest empty state");
        assertTrue(html.contains("Reisepass beantragen"), "metadata still rendered");
    }
}
