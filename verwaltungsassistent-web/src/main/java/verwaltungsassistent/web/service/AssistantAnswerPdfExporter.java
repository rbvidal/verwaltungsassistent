package verwaltungsassistent.web.service;

import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.SourceCitation;
import verwaltungsassistent.web.service.JobProgressService.AssistantOutcome;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders an Assistent answer as a professional information document: the
 * Thymeleaf template {@code templates/pdf/assistant-answer.html} (same
 * letterhead family as the Fallbriefing) is converted to A4 PDF with
 * OpenHTMLToPDF. Conceptually separate from the formal case decision document
 * (Entscheidungsvorlage) — the assistant answer is an informational export of
 * the pipeline answer with its citations. Nothing is re-generated, so no AI
 * calls are triggered by an export.
 */
@Service
public class AssistantAnswerPdfExporter {

    private static final Logger log = LoggerFactory.getLogger(AssistantAnswerPdfExporter.class);
    private static final DateTimeFormatter EXPORT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'Uhr'");

    /** Application version from pom.xml (via {@code app.version}); shown in the PDF footer. */
    @Value("${app.version:1.0.0-RC2}")
    String appVersion;

    @Autowired(required = false)
    private org.thymeleaf.spring6.SpringTemplateEngine springTemplateEngine;

    private volatile TemplateEngine fallbackTemplateEngine;

    /** Exports the completed assistant answer as PDF bytes (HTML/CSS → A4 via OpenHTMLToPDF). */
    public byte[] export(String question, AssistantOutcome outcome, LocalDateTime generatedAt) throws IOException {
        Map<String, Object> model = new HashMap<>();
        model.put("frage", question != null ? question : "");
        model.put("antwort", outcome.answerText() != null ? outcome.answerText() : "");
        model.put("grounded", outcome.grounded());
        model.put("strategie", outcome.strategy() != null ? outcome.strategy() : "—");
        model.put("konfidenz", outcome.confidencePct() != null ? outcome.confidencePct() + " %" : "—");
        model.put("quellenlage", outcome.grounded() ? "Durch Quellen belegt" : "Eingeschränkte Quellenlage");
        model.put("belege", belege(outcome.citations()));
        model.put("grundlagen", grundlagen(outcome.authorities()));
        model.put("exportDatum", generatedAt != null ? EXPORT_FMT.format(generatedAt) : "—");
        model.put("appVersion", appVersion);

        Context context = new Context(Locale.GERMANY, model);
        String html = templateEngine().process("pdf/assistant-answer", context);
        if (html == null || html.isBlank()) {
            throw new IOException("Assistentenantwort-Template lieferte leere Ausgabe");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, "");
        builder.toStream(out);
        try {
            builder.run();
        } catch (Exception e) {
            throw new IOException("Assistentenantwort-PDF konnte nicht erzeugt werden", e);
        }
        return out.toByteArray();
    }

    /** Beleg-Zeilen: Dokumenttitel, Seite, Auszug — nichts Erfundenes. */
    private static List<Map<String, Object>> belege(List<SourceCitation> citations) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (citations == null) {
            return out;
        }
        for (SourceCitation c : citations) {
            // Defensiv gegen Belege ohne Dokument-Identität (z. B. gespeicherte
            // Antworten mit eingeschränkter Quellenlage): niemals null-Werte
            // in Titel/Auszug rendern — der Export muss auch dann gelingen.
            if (c == null) {
                continue;
            }
            String title = c.title() != null && !c.title().isBlank()
                    ? c.title()
                    : (c.documentId() != null ? c.documentId().toString() : "Dokument");
            Map<String, Object> row = new HashMap<>();
            row.put("titel", title);
            row.put("seite", c.pageNumber() != null ? "S. " + c.pageNumber() : "");
            row.put("auszug", c.excerpt() != null ? c.excerpt() : "");
            out.add(row);
        }
        return out;
    }

    /** Rechtsgrundlagen-Zeilen aus den Autoritätsreferenzen. */
    private static List<Map<String, Object>> grundlagen(List<AuthorityReference> authorities) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (authorities == null) {
            return out;
        }
        for (AuthorityReference a : authorities) {
            if (a == null) {
                continue;
            }
            String title = a.entryTitle() != null && !a.entryTitle().isBlank()
                    ? a.entryTitle()
                    : (a.referenceId() != null && !a.referenceId().isBlank()
                            ? a.referenceId() : "Rechtsgrundlage");
            String excerpt = a.excerpt() != null && !a.excerpt().isBlank()
                    ? a.excerpt()
                    : (a.basis() != null && !a.basis().isBlank() ? a.basis() : "");
            Map<String, Object> row = new HashMap<>();
            row.put("titel", title);
            row.put("auszug", excerpt);
            out.add(row);
        }
        return out;
    }

    private TemplateEngine templateEngine() {
        if (springTemplateEngine != null) {
            return springTemplateEngine;
        }
        if (fallbackTemplateEngine == null) {
            synchronized (this) {
                if (fallbackTemplateEngine == null) {
                    org.thymeleaf.templateresolver.ClassLoaderTemplateResolver resolver =
                            new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
                    resolver.setPrefix("templates/");
                    resolver.setSuffix(".html");
                    resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
                    resolver.setCharacterEncoding("UTF-8");
                    resolver.setCacheable(true);
                    TemplateEngine engine = new TemplateEngine();
                    engine.setTemplateResolver(resolver);
                    fallbackTemplateEngine = engine;
                }
            }
        }
        return fallbackTemplateEngine;
    }
}
