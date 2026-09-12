package verwaltungsassistent.web.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports the editable citizen answer draft as a compact letter PDF using
 * the same PDFBox layout pattern as the decision proposal export. The text
 * is the saved draft verbatim — nothing is added or re-generated.
 */
@Service
public class AnswerDraftPdfExporter {

    private static final Logger log = LoggerFactory.getLogger(AnswerDraftPdfExporter.class);

    /** Application version from pom.xml (via {@code app.version}); shown in the PDF footer. */
    @org.springframework.beans.factory.annotation.Value("${app.version:1.0.0-RC2}")
    String appVersion;

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN_LEFT = 56;
    private static final float MARGIN_RIGHT = 56;
    private static final float MARGIN_TOP = 64;
    private static final float MARGIN_BOTTOM = 64;
    private static final float CONTENT_WIDTH = PAGE.getWidth() - MARGIN_LEFT - MARGIN_RIGHT;

    /** Exports the draft text as PDF bytes. */
    public byte[] export(String caseName, String workspaceCode, String draftText,
                         String generatedAt, String reviewNote) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font regular = loadFont(doc, "/fonts/DejaVuSans.ttf");
            PDType0Font bold = loadFont(doc, "/fonts/DejaVuSans-Bold.ttf");
            PDType0Font italic = loadFont(doc, "/fonts/DejaVuSans-Oblique.ttf");

            PDPage page = new PDPage(PAGE);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = PAGE.getHeight() - MARGIN_TOP;

            // Header: case reference
            y = text(cs, regular, bold, italic, y, "Antwortentwurf (maschinell erstellt)", bold, 11, true);
            y -= 10;
            y = text(cs, regular, bold, italic, y, caseName + (workspaceCode != null && !workspaceCode.isBlank()
                    ? " — Aktenzeichen " + workspaceCode : ""), regular, 9, false);
            y -= 14;

            // Draft body, wrapped with automatic page breaks. Form textareas
            // POST \r\n line endings; PDFBox has no glyph for \r, so normalize.
            String normalized = draftText.replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n");
            for (String line : lines) {
                if (line.isBlank()) {
                    y -= 8;
                    continue;
                }
                for (String wrapped : wrap(line, regular, 10)) {
                    if (y - 14 < MARGIN_BOTTOM) {
                        cs.close();
                        page = new PDPage(PAGE);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = PAGE.getHeight() - MARGIN_TOP;
                    }
                    y = text(cs, regular, bold, italic, y, wrapped, regular, 10, false);
                    y -= 14;
                }
            }

            // Review note at the end of the letter
            y -= 10;
            if (y - 24 < MARGIN_BOTTOM) {
                cs.close();
                page = new PDPage(PAGE);
                doc.addPage(page);
                cs = new PDPageContentStream(doc, page);
                y = PAGE.getHeight() - MARGIN_TOP;
            }
            y = text(cs, regular, bold, italic, y, reviewNote, italic, 8.5f, true);
            cs.close();

            // Footer on every page
            writeFooters(doc, regular, caseName, generatedAt);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private float text(PDPageContentStream cs, PDType0Font regular, PDType0Font bold,
                       PDType0Font italic, float y, String text, PDType0Font font, float size,
                       boolean wrapBox) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(MARGIN_LEFT, y);
        cs.showText(text);
        cs.endText();
        return y;
    }

    private String[] wrap(String text, PDType0Font font, float size) throws IOException {
        float maxWidth = CONTENT_WIDTH;
        List<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            float w = font.getStringWidth(candidate) / 1000f * size;
            if (w > maxWidth && !current.isEmpty()) {
                out.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) out.add(current.toString());
        return out.toArray(new String[0]);
    }

    private PDType0Font loadFont(PDDocument doc, String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Embedded font not found: " + path);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    private void writeFooters(PDDocument doc, PDType0Font regular, String caseName,
                              String generatedAt) throws IOException {
        int pageCount = doc.getNumberOfPages();
        String left = "Verwaltungsassistent v" + appVersion + " — Antwortentwurf";
        for (int i = 0; i < pageCount; i++) {
            PDPage page = doc.getPage(i);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
                cs.setFont(regular, 8f);
                cs.beginText();
                cs.newLineAtOffset(MARGIN_LEFT, 38);
                cs.showText(left);
                cs.endText();
                String pageNum = "Seite " + (i + 1) + " von " + pageCount;
                float rightTextWidth = regular.getStringWidth(pageNum) / 1000f * 8f;
                cs.beginText();
                cs.newLineAtOffset(PAGE.getWidth() - MARGIN_RIGHT - rightTextWidth, 38);
                cs.showText(pageNum);
                cs.endText();
                if (generatedAt != null && !generatedAt.isEmpty()) {
                    float genWidth = regular.getStringWidth(generatedAt) / 1000f * 8f;
                    cs.beginText();
                    cs.newLineAtOffset((PAGE.getWidth() - genWidth) / 2f, 26);
                    cs.showText(generatedAt);
                    cs.endText();
                }
            }
        }
    }
}
