package verwaltungsassistent.web.service;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a stored Fallbriefing as a deliberately designed administrative
 * document: the Thymeleaf template {@code templates/pdf/briefing.html} (same
 * Bezirksamt letterhead, banner and footer family as the Entscheidungsvorlage)
 * is converted to A4 PDF with OpenHTMLToPDF — CSS-level typography, spacing and
 * structure, no hand-drawn PDFBox layout. The briefing is rendered as-is —
 * nothing is re-generated, so no AI calls are triggered by an export.
 */
@Service
public class FallbriefingPdfExporter {

    private static final Logger log = LoggerFactory.getLogger(FallbriefingPdfExporter.class);

    /** Application version from pom.xml (via {@code app.version}); shown in the PDF footer. */
    @Value("${app.version:1.0.0-RC2}")
    String appVersion;

    @Autowired(required = false)
    private org.thymeleaf.spring6.SpringTemplateEngine springTemplateEngine;

    private volatile TemplateEngine fallbackTemplateEngine;

    /** Exports the stored briefing as PDF bytes (HTML/CSS → A4 via OpenHTMLToPDF). */
    public byte[] export(CaseBriefingService.Briefing b) throws IOException {
        return export(b, Map.of());
    }

    /**
     * Exports the stored briefing as PDF bytes. {@code extra} trägt den
     * Vorgangskontext für die Kopfzeile: {@code caseOrigin}, {@code caseCategory},
     * {@code caseOwnerName}, {@code caseOwnerEmail}, {@code caseClosed} —
     * fehlende Werte rendern neutrale Platzhalter.
     */
    public byte[] export(CaseBriefingService.Briefing b, Map<String, Object> extra) throws IOException {
        Map<String, Object> model = new HashMap<>();
        model.put("vorgangsnummer", b.vorgangsnummer());
        model.put("fallname", b.fallname());
        model.put("erstelltAm", b.erstelltAm());
        model.put("kurzfassung", b.kurzfassung());
        model.put("sachverhalt", b.sachverhalt());
        model.put("erkenntnisse", b.erkenntnisse() != null ? b.erkenntnisse() : java.util.List.of());
        model.put("belege", b.belege() != null ? b.belege() : java.util.List.of());
        model.put("zustaendigkeit", b.zustaendigkeit());
        model.put("offenePunkte", b.offenePunkte() != null ? b.offenePunkte() : java.util.List.of());
        model.put("quellenlage", b.quellenlage());
        model.put("bearbeitungsstand", b.bearbeitungsstand());
        model.put("appVersion", appVersion);
        model.put("caseOrigin", extra.getOrDefault("caseOrigin", ""));
        model.put("caseCategory", extra.getOrDefault("caseCategory", ""));
        model.put("caseOwnerName", extra.getOrDefault("caseOwnerName", ""));
        model.put("caseOwnerEmail", extra.getOrDefault("caseOwnerEmail", ""));
        boolean caseClosed = Boolean.TRUE.equals(extra.get("caseClosed"));
        model.put("caseClosed", caseClosed);
        // Abgeschlossener Vorgang: Schritte, die das Abschließen selbst
        // beschreiben ("Vorgang dokumentieren und abschließen"), sind durch
        // den Abschluss-Status bereits erledigt — die PDF sagt dann
        // "Vorgang abgeschlossen und dokumentiert." statt einen erledigten
        // Schritt als offene Handlung zu führen.
        java.util.List<String> steps = b.naechsteSchritte() != null
                ? new java.util.ArrayList<>(b.naechsteSchritte()) : new java.util.ArrayList<>();
        if (caseClosed) {
            steps.removeIf(s -> s != null
                    && s.toLowerCase(Locale.GERMANY).contains("abschließen"));
        }
        model.put("naechsteSchritte", steps);

        // Synopse-Abschnitte (Phase 2D.16): Die Kurzfassung kann die Abschnitte
        // der Entscheidungsanalyse als fortlaufenden Text enthalten
        // ("KURZANTWORT … ENTSCHEIDUNG … VERFAHREN … NÄCHSTER SCHRITT").
        // Für die PDF werden sie in EIGENE Zeilen/Blöcke zerlegt (bestehendes
        // Layout-Muster: Label-Zeile + Textblock) — keine <br>-Tricks im Text.
        // Enthält die Kurzfassung keine Abschnittsstruktur, bleibt die bisherige
        // einteilige Darstellung unverändert erhalten.
        model.put("synopse", parseSynopse(b.kurzfassung()));

        Context context = new Context(Locale.GERMANY, model);
        String html = templateEngine().process("pdf/briefing", context);
        if (html == null || html.isBlank()) {
            throw new IOException("Fallbriefing-Template lieferte leere Ausgabe");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, "");
        builder.toStream(out);
        try {
            builder.run();
        } catch (Exception e) {
            throw new IOException("Fallbriefing-PDF konnte nicht erzeugt werden", e);
        }
        return out.toByteArray();
    }

    /** Marken der Entscheidungsanalyse-Abschnitte innerhalb der Kurzfassung. */
    static final List<String> SYNOPSIS_MARKERS = List.of(
            "KURZANTWORT", "ENTSCHEIDUNG", "RECHTSGRUNDLAGE", "VERFAHREN", "NÄCHSTER SCHRITT");

    /**
     * Zerlegt die Kurzfassung in ihre Abschnitte ({@code label, text}).
     * Abschnitts-Überschriften stehen je auf einer eigenen Zeile; Textzeilen
     * werden dem jeweils aktiven Abschnitt zugeordnet. Liefert eine leere
     * Liste, wenn keine Abschnittsstruktur erkennbar ist (Freitext-
     * Kurzfassungen bleiben unverändert als ein Block).
     */
    static List<Map<String, String>> parseSynopse(String kurzfassung) {
        List<Map<String, String>> sections = new ArrayList<>();
        if (kurzfassung == null || kurzfassung.isBlank()) {
            return sections;
        }
        Map<String, String> current = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : kurzfassung.split("\\R")) {
            String trimmed = line.trim();
            String marker = markerOf(trimmed);
            if (marker != null) {
                if (current != null) {
                    current.put("text", buffer.toString().trim());
                }
                current = new LinkedHashMap<>();
                current.put("label", marker);
                sections.add(current);
                buffer = new StringBuilder();
                continue;
            }
            if (current == null) {
                // Text VOR der ersten Abschnitts-Überschrift: keine erkennbare
                // Abschnittsstruktur — die Kurzfassung bleibt ein Textblock.
                if (!trimmed.isEmpty()) {
                    return List.of();
                }
                continue;
            }
            if (!trimmed.isEmpty()) {
                if (buffer.length() > 0) {
                    buffer.append("\n");
                }
                buffer.append(trimmed);
            }
        }
        if (current != null) {
            current.put("text", buffer.toString().trim());
        }
        // Struktur nur verwenden, wenn mindestens zwei benannte Abschnitte
        // mit Inhalt erkannt wurden (sonst wirkt die Aufteilung wie
        // zufällige Bruchstücke).
        long named = sections.stream().filter(s -> s.get("label") != null && !s.get("label").isEmpty())
                .filter(s -> s.get("text") != null && !s.get("text").isBlank()).count();
        if (named < 2) {
            return List.of();
        }
        return sections.stream().filter(s -> s.get("text") != null && !s.get("text").isBlank()).toList();
    }

    /** Erkannte Abschnitts-Überschrift (case-insensitiv, exakte Zeile), sonst null. */
    private static String markerOf(String line) {
        if (line == null || line.isBlank() || line.length() > 40) {
            return null;
        }
        for (String marker : SYNOPSIS_MARKERS) {
            if (marker.equalsIgnoreCase(line)) {
                return marker;
            }
        }
        return null;
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
