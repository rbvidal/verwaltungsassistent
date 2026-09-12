package verwaltungsassistent.web.pdfplayground;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Playground-only template overlay renderer for the three supplied test decision
 * templates (blue / green / warning).
 *
 * <p>The supplied PDFs are immutable, image-based one-page A4 designs. This
 * renderer loads the chosen template from {@code target/pdf-playground/} and
 * writes only the dynamic values into the blank content fields. It never redraws
 * static labels, headings, cards, borders, icons, or decorative elements that are
 * already part of the template artwork.
 *
 * <p>Every dynamic field has its own typography and bounding box configuration.
 */
public final class PlaygroundTemplatePdfRenderer {

    public enum TemplateKind {
        BLUE("template-blue-test.pdf"),
        GREEN("template-green-test.pdf"),
        WARNING("template-warning-test.pdf");

        final String resource;

        TemplateKind(String resource) {
            this.resource = resource;
        }
    }

    // ── Dynamic field configuration (PDF points, origin bottom-left) ──
    // Coordinates measured against the authoritative *-test.pdf templates.

    // Dynamic case name in the narrow band below the static title and above the status cards.
    private static final Field CASE_NAME = Field.builder()
            .name("CASE_NAME")
            .x(55f).y(730f).width(180f).height(10f)
            .font(PDType1Font.HELVETICA_BOLD).fontSize(8f)
            .lineSpacing(10f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(1)
            .build();

    // Three status cards.
    private static final Field CARD_PHASE = Field.builder()
            .name("CARD_PHASE")
            .x(45f).y(720f).width(135f).height(18f)
            .font(PDType1Font.HELVETICA_BOLD).fontSize(10f)
            .lineSpacing(12f)
            .hAlign(HAlign.CENTER).vAlign(VAlign.CENTER)
            .maxLines(1)
            .build();

    private static final Field CARD_CONFIDENCE = Field.builder()
            .name("CARD_CONFIDENCE")
            .x(45f).y(685f).width(135f).height(18f)
            .font(PDType1Font.HELVETICA_BOLD).fontSize(10f)
            .lineSpacing(12f)
            .hAlign(HAlign.CENTER).vAlign(VAlign.CENTER)
            .maxLines(1)
            .build();

    private static final Field CARD_DOCS = Field.builder()
            .name("CARD_DOCS")
            .x(205f).y(718f).width(110f).height(52f)
            .font(PDType1Font.HELVETICA).fontSize(7.5f)
            .lineSpacing(9f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(6)
            .build();

    // BEARBEITET VON box.
    private static final Field BEARBEITER = Field.builder()
            .name("BEARBEITER")
            .x(400f).y(770f).width(145f).height(12f)
            .font(PDType1Font.HELVETICA).fontSize(9f)
            .lineSpacing(11f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(1)
            .build();

    private static final Field EMAIL = Field.builder()
            .name("EMAIL")
            .x(400f).y(722f).width(145f).height(11f)
            .font(PDType1Font.HELVETICA).fontSize(7.5f)
            .lineSpacing(9f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(1)
            .build();

    private static final Field VORGANGSNUMMER = Field.builder()
            .name("VORGANGSNUMMER")
            .x(375f).y(700f).width(75f).height(10f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(10f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(1)
            .build();

    private static final Field AKZENZEICHEN = Field.builder()
            .name("AKZENZEICHEN")
            .x(460f).y(700f).width(80f).height(10f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(10f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(1)
            .build();

    private static final Field FALLART = Field.builder()
            .name("FALLART")
            .x(420f).y(665f).width(120f).height(10f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(10f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(1)
            .build();

    // Section 1 recommendation body.
    private static final Field RECOMMENDATION = Field.builder()
            .name("RECOMMENDATION")
            .x(55f).y(618f).width(500f).height(24f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(9.5f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(2)
            .build();

    // Section 2 findings.
    private static final Field KERN_FINDINGS = Field.builder()
            .name("KERN_FINDINGS")
            .x(50f).y(555f).width(215f).height(70f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(9.5f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(7)
            .build();

    private static final Field MORE_FINDINGS = Field.builder()
            .name("MORE_FINDINGS")
            .x(50f).y(465f).width(215f).height(95f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(9.5f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(10)
            .build();

    // Section 3 open points.
    private static final Field OPEN_POINTS = Field.builder()
            .name("OPEN_POINTS")
            .x(315f).y(555f).width(220f).height(150f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(9.5f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(15)
            .build();

    // Section 4 next steps.
    private static final Field NEXT_STEPS = Field.builder()
            .name("NEXT_STEPS")
            .x(315f).y(430f).width(220f).height(90f)
            .font(PDType1Font.HELVETICA).fontSize(8f)
            .lineSpacing(9.5f)
            .hAlign(HAlign.LEFT).vAlign(VAlign.TOP)
            .maxLines(9)
            .build();

    private PlaygroundTemplatePdfRenderer() {
    }

    /**
     * Renders one of the three test templates with the supplied dynamic model.
     *
     * @param kind  template variant
     * @param model map containing the dynamic values
     * @return PDF bytes
     * @throws IOException if the template cannot be loaded or saved
     */
    public static byte[] render(TemplateKind kind, Map<String, Object> model) throws IOException {
        Path templatePath = Path.of("target", "pdf-playground", kind.resource);
        if (!Files.exists(templatePath)) {
            throw new IOException("PDF-Vorlage fehlt: " + templatePath.toAbsolutePath());
        }
        byte[] templateBytes = Files.readAllBytes(templatePath);
        try (ByteArrayInputStream in = new ByteArrayInputStream(templateBytes)) {
            try (PDDocument doc = PDDocument.load(in)) {
                PDPage page = doc.getPage(0);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    drawText(cs, CASE_NAME, str(model, "caseName"));
                    drawBearbeitetVon(cs, model);
                    drawCards(cs, model);
                    drawRecommendation(cs, model, kind);
                    drawFindings(cs, model);
                    drawOpenPoints(cs, model);
                    drawNextSteps(cs, model);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    // ── Field renderers ──

    private static void drawBearbeitetVon(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        drawText(cs, BEARBEITER, str(model, "bearbeiter"));
        drawText(cs, EMAIL, str(model, "email"));
        drawText(cs, VORGANGSNUMMER, str(model, "vorgangsnummer"));
        drawText(cs, AKZENZEICHEN, str(model, "aktenzeichen"));
        drawText(cs, FALLART, str(model, "fallart"));
    }

    private static void drawCards(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        drawText(cs, CARD_PHASE, str(model, "casePhase"));

        String conf = str(model, "confidenceScore");
        if (conf.isBlank() || "0 %".equals(conf)) {
            conf = "nicht bewertbar";
        }
        drawText(cs, CARD_CONFIDENCE, conf);

        List<String> docNames = strings(model, "documentNames");
        if (!docNames.isEmpty()) {
            drawText(cs, CARD_DOCS, String.join("\n", docNames));
        }
    }

    private static void drawRecommendation(PDPageContentStream cs, Map<String, Object> model,
                                           TemplateKind kind) throws IOException {
        // The warning template already prints the fail-closed recommendation text as static artwork.
        if (kind == TemplateKind.WARNING) {
            return;
        }
        String answer = str(model, "decisionAnswer");
        if (answer.isBlank()) {
            answer = "Keine ausreichend belegte Empfehlung verfügbar.";
        }
        drawText(cs, RECOMMENDATION, answer);
    }

    private static void drawFindings(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        List<String> core = strings(model, "primaryFindings");
        List<String> more = strings(model, "secondaryFindings");

        if (!core.isEmpty()) {
            drawText(cs, KERN_FINDINGS, bulletList(core));
        }
        if (!more.isEmpty()) {
            drawText(cs, MORE_FINDINGS, bulletList(more));
        }
    }

    private static void drawOpenPoints(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        List<String> points = strings(model, "openPoints");
        if (!points.isEmpty()) {
            drawText(cs, OPEN_POINTS, String.join("\n", points));
            return;
        }
        // Fallback for older model shape.
        List<String> fallback = new ArrayList<>();
        for (Map<String, Object> m : list(model, "missingDocs")) {
            Object text = m.get("text");
            Object label = m.get("label");
            String v = String.valueOf(text != null ? text : label != null ? label : "").trim();
            if (!v.isBlank() && !"null".equals(v)) fallback.add("• " + v);
        }
        for (Map<String, Object> c : list(model, "coverageIssues")) {
            Object text = c.get("text");
            Object label = c.get("label");
            String v = String.valueOf(text != null ? text : label != null ? label : "").trim();
            if (!v.isBlank() && !"null".equals(v)) fallback.add("• " + v);
        }
        if (!fallback.isEmpty()) {
            drawText(cs, OPEN_POINTS, String.join("\n", fallback));
        }
    }

    private static void drawNextSteps(PDPageContentStream cs, Map<String, Object> model) throws IOException {
        List<String> steps = strings(model, "nextSteps");
        if (!steps.isEmpty()) {
            drawText(cs, NEXT_STEPS, String.join("\n", steps));
        }
    }

    private static String bulletList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("• ").append(item);
        }
        return sb.toString();
    }

    // ── Model helpers ──

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> model, String key) {
        Object v = model.get(key);
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

    private static List<String> strings(Map<String, Object> model, String key) {
        Object v = model.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> raw) {
            for (Object o : raw) {
                if (o != null) out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static String str(Map<String, Object> model, String key) {
        Object v = model.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    // ── Text layout engine ──

    private static void drawText(PDPageContentStream cs, Field f, String text) throws IOException {
        if (text == null || text.isBlank()) return;

        float size = f.fontSize;
        List<Line> lines = layout(text, f, size);

        // Shrink font if content does not fit.
        float minSize = Math.max(6f, f.fontSize * 0.65f);
        while (!fits(lines, f, size) && size > minSize) {
            size -= 0.25f;
            lines = layout(text, f, size);
        }

        // Truncate to maxLines if still overflowing.
        if (lines.size() > f.maxLines) {
            lines = lines.subList(0, f.maxLines);
        }

        float blockHeight = lines.size() * f.lineSpacing;
        float firstBaseline;
        switch (f.vAlign) {
            case BOTTOM:
                firstBaseline = f.y - f.height + blockHeight;
                break;
            case CENTER:
                firstBaseline = f.y - (f.height - blockHeight) / 2f;
                break;
            case TOP:
            default:
                // y is the desired first baseline for TOP fields.
                firstBaseline = f.y;
                break;
        }

        float y = firstBaseline;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (y < f.y - f.height) break;
            float x = f.x;
            if (f.hAlign == HAlign.CENTER) {
                float w = stringWidth(line.text, f.font, size);
                x = f.x + (f.width - w) / 2f;
            } else if (f.hAlign == HAlign.RIGHT) {
                float w = stringWidth(line.text, f.font, size);
                x = f.x + f.width - w;
            }
            beginText(cs, f.font, size, x, y);
            cs.showText(sanitize(line.text));
            endText(cs);
            y -= f.lineSpacing;
        }
    }

    private static boolean fits(List<Line> lines, Field f, float size) {
        if (lines.size() > f.maxLines) return false;
        float totalHeight = lines.size() * f.lineSpacing;
        return totalHeight <= f.height + 0.001f;
    }

    private static List<Line> layout(String text, Field f, float size) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            if (raw.isBlank()) {
                lines.add(new Line(""));
                continue;
            }
            String[] words = raw.trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (line.isEmpty() || stringWidth(candidate, f.font, size) <= f.width) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(new Line(line.toString()));
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(new Line(line.toString()));
        }
        return lines;
    }

    private static float stringWidth(String s, PDFont font, float size) {
        try {
            return font.getStringWidth(sanitize(s)) / 1000f * size;
        } catch (IOException e) {
            return size * s.length() * 0.5f;
        }
    }

    private static void beginText(PDPageContentStream cs, PDFont font, float size,
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

    // ── Field / style model ──

    private enum HAlign { LEFT, CENTER, RIGHT }
    private enum VAlign { TOP, CENTER, BOTTOM }

    private record Line(String text) {
    }

    private static final class Field {
        final String name;
        final float x;
        final float y;
        final float width;
        final float height;
        final PDFont font;
        final float fontSize;
        final float lineSpacing;
        final HAlign hAlign;
        final VAlign vAlign;
        final int maxLines;

        private Field(String name, float x, float y, float width, float height,
                      PDFont font, float fontSize, float lineSpacing,
                      HAlign hAlign, VAlign vAlign, int maxLines) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.font = font;
            this.fontSize = fontSize;
            this.lineSpacing = lineSpacing;
            this.hAlign = hAlign;
            this.vAlign = vAlign;
            this.maxLines = maxLines;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private String name;
            private float x;
            private float y;
            private float width;
            private float height;
            private PDFont font;
            private float fontSize;
            private float lineSpacing;
            private HAlign hAlign = HAlign.LEFT;
            private VAlign vAlign = VAlign.TOP;
            private int maxLines = 1;

            Builder name(String name) {
                this.name = name;
                return this;
            }

            Builder x(float x) {
                this.x = x;
                return this;
            }

            Builder y(float y) {
                this.y = y;
                return this;
            }

            Builder width(float width) {
                this.width = width;
                return this;
            }

            Builder height(float height) {
                this.height = height;
                return this;
            }

            Builder font(PDFont font) {
                this.font = font;
                return this;
            }

            Builder fontSize(float fontSize) {
                this.fontSize = fontSize;
                if (this.lineSpacing <= 0f) {
                    this.lineSpacing = fontSize * 1.2f;
                }
                return this;
            }

            Builder lineSpacing(float lineSpacing) {
                this.lineSpacing = lineSpacing;
                return this;
            }

            Builder hAlign(HAlign hAlign) {
                this.hAlign = hAlign;
                return this;
            }

            Builder vAlign(VAlign vAlign) {
                this.vAlign = vAlign;
                return this;
            }

            Builder maxLines(int maxLines) {
                this.maxLines = maxLines;
                return this;
            }

            Field build() {
                return new Field(name, x, y, width, height, font, fontSize, lineSpacing,
                        hAlign, vAlign, maxLines);
            }
        }
    }
}
