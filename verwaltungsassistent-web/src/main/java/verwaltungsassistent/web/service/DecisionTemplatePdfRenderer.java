package verwaltungsassistent.web.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rendert die Entscheidungsvorlage auf Basis der gelieferten EINSEITIGEN
 * PDF-Vorlagen (template-blue/green/warning): Die Vorlagenseite ist das
 * unveränderliche Design („fertige Vordrucke"), die dynamischen Fall- und
 * Analysedaten werden als Text in die dafür vorgesehenen Bereiche überlagert.
 *
 * <p>Vorlagenauswahl (deterministisch, auf den vorhandenen Analysefeldern):
 * <ul>
 *   <li>GREEN — {@code grounded == true} (durch Quellen belegtes Ergebnis)</li>
 *   <li>WARNING — nicht belegt und fail-closed / keine Kernfeststellungen /
 *       unzureichende Quellenlage (erfordert menschliche Prüfung)</li>
 *   <li>BLUE — alle übrigen nutzbaren Analysen (normal/informativ)</li>
 * </ul>
 *
 * <p>Es wird KEIN HTML/CSS-Layout nachgebaut: Die Vorlagenbilder bleiben
 * unverändert, nur die Textfelder werden überlagert. Die Textpassung
 * (Umbruch, Breite, Höhe, Schriftgröße) wird pro Feld begrenzt; zu lange
 * Inhalte werden gekürzt statt über andere Bereiche zu laufen.
 */
public final class DecisionTemplatePdfRenderer {

    /** Vorlagenauswahl basierend auf dem vorhandenen Analyse-Modell. */
    public static TemplateKind selectTemplate(Map<String, Object> model) {
        boolean grounded = Boolean.TRUE.equals(model.get("grounded"));
        String answer = str(model, "decisionAnswer");
        boolean insufficientAnswer = answer != null && answer.contains("keine ausreichenden Informationen");
        boolean noCoreFindings = list(model, "primaryFindings").isEmpty();
        boolean insufficientCoverage = !list(model, "coverageIssues").isEmpty()
                && !list(model, "coverageIssues").stream()
                        .map(i -> String.valueOf(i.getOrDefault("type", "")).toLowerCase())
                        .allMatch(t -> t.contains("hinweis") || t.contains("info"));
        boolean failClosed = insufficientAnswer || (noCoreFindings && !grounded);

        if (grounded && !failClosed) return TemplateKind.GREEN;
        if (failClosed || insufficientCoverage) return TemplateKind.WARNING;
        return TemplateKind.BLUE;
    }

    public enum TemplateKind {
        GREEN("template-green.pdf"),
        BLUE("template-blue.pdf"),
        WARNING("template-warning.pdf");

        final String resource;
        TemplateKind(String resource) {
            this.resource = resource;
        }
    }

    // ── Layout (Punktkoordinaten, Ursprung unten links, A4 595×842) ──
    // Gemessen an den gelieferten V2-Vorlagen: statischer Text nur im Kopf
    // (≈47–140 pt von oben), Empfehlungsbox ≈168–217 pt, darunter leere
    // Felder für Titel/Karten/Abschnitte.
    private static final float PAGE_W = 595.28f;
    private static final float PAGE_H = 841.89f;
    private static final float MARGIN_X = 46f;

    // Rechtecke für die dynamischen Felder (y = Grundlinie, Ursprung unten).
    private static final float TITLE_Y = 727f;          // Falltitel (Band ≈108–112 pt von oben)
    private static final float SUBTITLE_Y = 700f;       // „Entscheidungsvorlage (maschinell erstellt)" (≈137–140)
    private static final float CARD_LABEL_Y = 610f;     // Beschriftungen der drei Karten
    private static final float CARD_VALUE_Y = 594f;     // Werte der drei Karten
    private static final float[] CARD_CX = {110f, 292f, 474f};
    private static final float REC_BOX_TOP = 674f;      // Empfehlungsbox (Oberkante, ≈168 pt von oben)
    private static final float REC_BOX_BOTTOM = 625f;   // Unterkante (≈217 pt von oben)
    private static final float REC_HEAD_Y = 658f;
    private static final float REC_TEXT_Y = 640f;
    private static final float REC_TEXT_W = 500f;
    private static final float REC_TEXT_H = 16f;   // 2 Zeilen à 11,25 pt — nie unter die Box-Unterkante (625)
    private static final float SECT_L_X = 46f;          // linke Spalte
    private static final float SECT_R_X = 310f;         // rechte Spalte
    private static final float SECT_W = 240f;
    private static final float SECT2_HEAD_Y = 488f;
    private static final float SECT2_HEAD_R_Y = 488f;
    private static final float SECT3_HEAD_Y = 372f;
    private static final float SECT2_MINY = 380f;
    private static final float SECT3_MINY = 262f;
    private static final float SECT4_MINY = 262f;

    private DecisionTemplatePdfRenderer() {
    }

    /** Erzeugt das Entscheidungs-PDF: Vorlagenseite + Textüberlagerung. */
    public static byte[] render(TemplateKind kind, Map<String, Object> model) throws IOException {
        try (InputStream in = DecisionTemplatePdfRenderer.class
                .getResourceAsStream("/templates/pdf/" + kind.resource)) {
            if (in == null) {
                throw new IOException("PDF-Vorlage fehlt: " + kind.resource);
            }
            try (PDDocument doc = PDDocument.load(in)) {
                PDPage page = doc.getPage(0);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    drawTitle(cs, model);
                    drawCards(cs, model);
                    drawRecommendation(cs, model, kind);
                    drawSections(cs, model);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    // ── Feld-Zeichnungen ──

    private static void drawTitle(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        String title = str(model, "caseName");
        text(cs, PDType1Font.HELVETICA_BOLD, 16f, fit(title, 500f, 16f),
                MARGIN_X, TITLE_Y, 500f, 18f);
        text(cs, PDType1Font.HELVETICA, 9f, "Entscheidungsvorlage (maschinell erstellt)",
                MARGIN_X, SUBTITLE_Y, 500f, 11f);
    }

    private static void drawCards(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        String phase = str(model, "casePhase");
        String docs = String.valueOf(model.get("documentCount"));
        String conf = str(model, "confidenceScore");
        if (conf.isEmpty() || "0 %".equals(conf)) {
            conf = "nicht bewertbar";
        }
        String[] labels = {"BEARBEITUNGSSTAND", "DOKUMENTE IM VORGANG", "GESAMTKONFIDENZ"};
        String[] values = {phase, docs, conf};
        for (int i = 0; i < 3; i++) {
            textCentered(cs, PDType1Font.HELVETICA, 7f, labels[i], CARD_CX[i], CARD_LABEL_Y, 150f);
            textCentered(cs, PDType1Font.HELVETICA_BOLD, 13f, fit(values[i], 140f, 13f),
                    CARD_CX[i], CARD_VALUE_Y, 140f);
        }
    }

    private static void drawRecommendation(PDPageContentStream cs, Map<String, Object> model,
                                           TemplateKind kind) throws IOException {
        String heading = "1   EMPFEHLUNG";
        String answer = str(model, "decisionAnswer");
        text(cs, PDType1Font.HELVETICA_BOLD, 10f, heading, MARGIN_X, REC_HEAD_Y, 500f, 13f);
        String body;
        if (kind == TemplateKind.WARNING) {
            body = answer;
        } else {
            body = answer;
        }
        if (body.isBlank()) {
            body = "Keine ausreichend belegte Empfehlung verfügbar.";
        }
        text(cs, PDType1Font.HELVETICA, 9f, body, MARGIN_X, REC_TEXT_Y, REC_TEXT_W, REC_TEXT_H);
    }

    private static void drawSections(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        // 2. FESTSTELLUNGEN (linke Spalte oben): Kernfeststellungen + Weitere Erkenntnisse
        section(cs, MARGIN_X, SECT2_HEAD_Y, SECT_W, "2   FESTSTELLUNGEN ZUM SACHVERHALT", SECT2_MINY,
                coreFindings(model), "Kernfeststellungen",
                additionalFindings(model), "Weitere Erkenntnisse");

        // 3. OFFENE PUNKTE (rechte Spalte)
        section(cs, SECT_R_X, SECT2_HEAD_R_Y, SECT_W, "3   OFFENE PUNKTE UND FEHLENDE INFORMATIONEN",
                SECT3_MINY, openPoints(model), null, null, null);

        // 4. EMPFOHLENE NÄCHSTE SCHRITTE (linke Spalte darunter)
        section(cs, MARGIN_X, SECT3_HEAD_Y, SECT_W, "4   EMPFOHLENE NÄCHSTE SCHRITTE",
                SECT4_MINY, nextSteps(model), null, null, null);
    }

    private static void section(PDPageContentStream cs, float x, float headY, float w,
                                String heading, float minY,
                                List<String> items, String itemLabel,
                                List<String> items2, String itemLabel2) throws IOException {
        text(cs, PDType1Font.HELVETICA_BOLD, 8.5f, heading, x, headY, w, 11f);
        float y = headY - 15f;
        if (itemLabel != null && !items.isEmpty()) {
            text(cs, PDType1Font.HELVETICA_BOLD, 7.5f, itemLabel, x, y, w, 10f);
            y -= 11f;
        }
        for (String item : items) {
            y = wrap(cs, "• " + item, PDType1Font.HELVETICA, 7.5f, x, y, w, 9.5f, minY);
            y -= 3f;
        }
        if (itemLabel2 != null && !items2.isEmpty()) {
            y -= 4f;
            text(cs, PDType1Font.HELVETICA_BOLD, 7.5f, itemLabel2, x, y, w, 10f);
            y -= 11f;
            for (String item : items2) {
                y = wrap(cs, "• " + item, PDType1Font.HELVETICA, 7.5f, x, y, w, 9.5f, minY);
                y -= 3f;
            }
        }
    }

    // ── Modell-Extraktion (bestehende strukturierte Felder) ──

    private static List<String> coreFindings(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : list(model, "primaryFindings")) {
            String label = String.valueOf(f.getOrDefault("label", "")).trim();
            String desc = String.valueOf(f.getOrDefault("description", "")).trim();
            out.add(desc.isBlank() ? label : desc);
        }
        return out;
    }

    private static List<String> additionalFindings(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : list(model, "secondaryFindings")) {
            String label = String.valueOf(f.getOrDefault("label", "")).trim();
            String desc = String.valueOf(f.getOrDefault("description", "")).trim();
            out.add(desc.isBlank() ? label : desc);
        }
        return out;
    }

    private static List<String> openPoints(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Object m : list(model, "missingDocs")) {
            if (m instanceof Map<?, ?> mm) {
                Object label = mm.get("label");
                Object role = mm.get("role");
                String v = String.valueOf(label != null ? label : role != null ? role : "");
                if (!v.isBlank() && !"null".equals(v)) out.add(v);
            }
        }
        for (Object c : list(model, "coverageIssues")) {
            if (c instanceof Map<?, ?> cc) {
                Object text = cc.get("text");
                Object label = cc.get("label");
                String v = String.valueOf(text != null ? text : label != null ? label : "");
                if (!v.isBlank() && !"null".equals(v)) out.add(v);
            }
        }
        return out;
    }

    private static List<String> nextSteps(Map<String, Object> model) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : list(model, "proceduralFindings")) {
            String label = String.valueOf(f.getOrDefault("label", "")).trim();
            String desc = String.valueOf(f.getOrDefault("description", "")).trim();
            out.add(desc.isBlank() ? label : desc);
        }
        return out;
    }

    // ── Texthilfen (Umbruch, Passung, Begrenzung) ──

    private static void text(PDPageContentStream cs, PDType1Font font, float size, String s,
                             float x, float y, float maxW, float maxH) throws IOException {
        if (s == null || s.isBlank()) return;
        float yStart = y;
        for (String line : wrapText(s, font, size, maxW)) {
            if (yStart - y > maxH) break;
            beginText(cs, font, size, x, y);
            cs.showText(line);
            endText(cs);
            y -= size * 1.25f;
        }
    }

    private static void textCentered(PDPageContentStream cs, PDType1Font font, float size,
                                     String s, float cx, float y, float maxW) throws IOException {
        if (s == null || s.isBlank()) return;
        float w = stringWidth(s, font, size);
        float x = cx - Math.min(w, maxW) / 2f;
        beginText(cs, font, size, x, y);
        cs.showText(sanitize(s));
        endText(cs);
    }

    /** Zeichnet einen umbrochenen Absatz; gibt die neue y-Position zurück. */
    private static float wrap(PDPageContentStream cs, String s, PDType1Font font, float size,
                              float x, float y, float maxW, float lineH, float minY) throws IOException {
        if (s == null || s.isBlank()) return y;
        float cy = y;
        for (String line : wrapText(s, font, size, maxW)) {
            if (cy - lineH < minY) break;
            beginText(cs, font, size, x, cy);
            cs.showText(sanitize(line));
            endText(cs);
            cy -= lineH;
        }
        return cy;
    }

    /** Reduziert die Schriftgröße, wenn der Text breiter als die Fläche ist (Kürzung als letzter Ausweg). */
    private static String fit(String s, float maxW, float size) {
        if (s == null || s.isBlank()) return s;
        float w = stringWidth(s, PDType1Font.HELVETICA_BOLD, size);
        if (w <= maxW) return s;
        // längste Zeile anpassen
        String out = s;
        while (out.length() > 10 && stringWidth(out, PDType1Font.HELVETICA_BOLD, size) > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out.trim() + "…";
    }

    private static List<String> wrapText(String s, PDType1Font font, float size, float maxW) {
        List<String> lines = new ArrayList<>();
        if (s == null || s.isBlank()) return lines;
        for (String raw : s.split("\\n")) {
            if (raw.isBlank()) {
                lines.add("");
                continue;
            }
            String[] words = raw.trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (stringWidth(candidate, font, size) <= maxW || line.isEmpty()) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static float stringWidth(String s, PDType1Font font, float size) {
        try {
            return font.getStringWidth(sanitize(s)) / 1000f * size;
        } catch (IOException e) {
            return size * s.length() * 0.5f;
        }
    }

    private static void beginText(PDPageContentStream cs, PDType1Font font, float size,
                                  float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
    }

    private static void endText(PDPageContentStream cs) throws IOException {
        cs.endText();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('—', '-').replace('–', '-');
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> raw) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Map<?, ?> entry) {
                    out.add((Map<String, Object>) entry);
                }
            }
            return out;
        }
        return List.of();
    }
}
