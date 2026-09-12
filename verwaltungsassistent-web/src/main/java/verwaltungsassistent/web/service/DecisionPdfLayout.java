package verwaltungsassistent.web.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemeinsames Seiten-Layout für behördliche PDF-Exporte (Entscheidung,
 * Fallbriefing). Zwei Varianten:
 *
 * <ul>
 *   <li>Die klassischen Methoden ({@link #municipalHeader()}, {@link #metadataBadges(String...)},
 *       {@link #section(int, String)}, {@link #bullet(String)} usw.) liefern einen schlichten,
 *       textbasierten Verwaltungsbrief.</li>
 *   <li>Die neuen {@code reference...}-Methoden reproduzieren die visuelle Referenzvorlage
 *       mit abgerundeten Karten, Symbolen, der zweispaltigen Begründung und dem
 *       Freigabestatus.</li>
 * </ul>
 *
 * <p>Schriften: Helvetica (Standard-14-Type1, in PDFBox eingebettet). Umlaute sind über
 * WinAnsiEncoding abgedeckt; nicht abgedeckte Zeichen (z. B. Gedankenstrich „—") werden
 * ersetzt.</p>
 */
public class DecisionPdfLayout {

    public static final PDRectangle PAGE = PDRectangle.A4;
    public static final float MARGIN_LEFT = 48;
    public static final float MARGIN_RIGHT = 48;
    public static final float MARGIN_TOP = 42;
    public static final float MARGIN_BOTTOM = 52;
    public static final float CONTENT_WIDTH = PAGE.getWidth() - MARGIN_LEFT - MARGIN_RIGHT;
    /** Spaltenmitte für die zweispaltige Metadaten-Tabelle. */
    public static final float MID = MARGIN_LEFT + CONTENT_WIDTH / 2f;
    public static final float FOOTER_RULE_Y = 52f;

    /* ---- Referenz-Farbpalette ---- */
    public static final float[] RED = {0.717f, 0.109f, 0.109f};
    public static final float[] BLUE = {0.082f, 0.396f, 0.753f};
    public static final float[] BLUE_LIGHT = {0.906f, 0.945f, 0.992f};
    public static final float[] ORANGE = {0.937f, 0.424f, 0.0f};
    public static final float[] ORANGE_BG = {1.0f, 0.953f, 0.878f};
    public static final float[] GREEN = {0.220f, 0.557f, 0.235f};
    public static final float[] GRAY_BG = {0.961f, 0.961f, 0.961f};
    public static final float[] GRAY_BORDER = {0.851f, 0.851f, 0.851f};
    public static final float[] DARK = {0.12f, 0.12f, 0.12f};
    public static final float[] MEDIUM_GRAY = {0.45f, 0.45f, 0.45f};

    protected final PDDocument doc;
    protected final PDFont regular;
    protected final PDFont bold;
    protected final PDFont italic;
    protected PDPageContentStream cs;
    protected float y;

    public DecisionPdfLayout(PDDocument doc, PDFont regular, PDFont bold, PDFont italic) throws IOException {
        this.doc = doc;
        this.regular = regular;
        this.bold = bold;
        this.italic = italic;
        newPage();
    }

    void newPage() throws IOException {
        doc.addPage(new PDPage(PAGE));
        cs = new PDPageContentStream(doc, doc.getPage(doc.getNumberOfPages() - 1));
        y = PAGE.getHeight() - MARGIN_TOP;
    }

    void close() throws IOException {
        cs.close();
    }

    void ensure(float needed) throws IOException {
        if (y - needed < MARGIN_BOTTOM) {
            cs.close();
            newPage();
        }
    }

    float width(String text, PDFont font, float size) throws IOException {
        return font.getStringWidth(sanitize(text)) / 1000f * size;
    }

    void show(String text, PDFont font, float size, float x) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
    }

    void show(String text, PDFont font, float size, float x, float yPos) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
        cs.newLineAtOffset(x, yPos);
        cs.showText(sanitize(text));
        cs.endText();
    }

    /** Rechtsbündig (Ende bei xRight). */
    void showRight(String text, PDFont font, float size, float xRight) throws IOException {
        show(text, font, size, xRight - width(text, font, size));
    }

    List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return lines;
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.isEmpty()) {
                line.append(word);
                continue;
            }
            String candidate = line + " " + word;
            if (width(candidate, font, size) <= maxWidth) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    void paragraph(String text, float indent) throws IOException {
        if (text == null || text.isEmpty()) return;
        List<String> lines = wrap(text, regular, 9.5f, CONTENT_WIDTH - indent);
        ensure(lines.size() * 12.5f + 4);
        for (String line : lines) {
            show(line, regular, 9.5f, MARGIN_LEFT + indent);
            y -= 12.5f;
        }
        y -= 2;
    }

    /**
     * Eine Feststellung/Erkenntnis mit eckigem Marker + Detail. Die Pipeline
     * erzeugt häufig Beschreibungen, die dem Label entsprechen oder dessen
     * gekürzte Präfix-Variante sind — jede Information erscheint genau einmal:
     * identisches Label/Detail → nur Marker; Label = gekürztes Präfix des
     * Details → nur das vollständige Detail; sonst Marker + Detail.
     */
    void finding(String label, String description) throws IOException {
        String labelTrimmed = label != null ? label.trim() : "";
        if (description != null && !description.isBlank()
                && !labelTrimmed.isEmpty()
                && truncatedPrefix(labelTrimmed, description.trim()) != null) {
            bullet(description);
            return;
        }
        bullet(label);
        if (description == null || description.isBlank()) return;
        if (labelTrimmed.equalsIgnoreCase(description.trim())) return;
        paragraph(description, 14);
    }

    static String truncatedPrefix(String label, String description) {
        String base = label.endsWith("…")
                ? label.substring(0, label.length() - 1).trim()
                : (label.endsWith("...")
                        ? label.substring(0, label.length() - 3).trim() : null);
        if (base == null || base.isEmpty()) return null;
        if (base.length() < 20) return null;
        return description.toLowerCase().startsWith(base.toLowerCase()) ? base : null;
    }

    /**
     * Briefkopf im Stil der ursprünglichen schlichten Vorlage: links „BEZIRKSAMT NORDSTADT /
     * VON POTSDAM", rechts der Kontaktblock (Zimmer/Telefon), darunter der
     * Vertraulichkeitsvermerk und die Adresszeilen, abschließend eine dezente
     * Linie.
     */
    void municipalHeader() throws IOException {
        ensure(120);
        show("BEZIRKSAMT", bold, 13f, MARGIN_LEFT);
        y -= 15;
        show("NORDSTADT", bold, 13f, MARGIN_LEFT);
        y -= 13;
        show("VON POTSDAM", bold, 9.5f, MARGIN_LEFT);
        y -= 6;

        // Kontaktblock rechtsbündig neben dem Briefkopf
        showRight("Bürgerdienste", bold, 8.5f, PAGE.getWidth() - MARGIN_RIGHT);
        y -= 11;
        showRight("Zimmer: 2.15", regular, 8f, PAGE.getWidth() - MARGIN_RIGHT);
        y -= 10;
        showRight("0331 / 90295-1234", regular, 8f, PAGE.getWidth() - MARGIN_RIGHT);
        y -= 14;

        confidentialBanner();
        y -= 12;

        show("Bezirksamt Nordstadt von Potsdam", regular, 8.5f, MARGIN_LEFT);
        y -= 11;
        show("Abt. Stadtentwicklung und Bürgerdienste", regular, 8.5f, MARGIN_LEFT);
        y -= 11;
        show("Amt für Bürgerdienste · Bürgeramt Nordstadt", regular, 8.5f, MARGIN_LEFT);
        y -= 11;
        show("Fiktive Straße 24a-26 · 14467 Potsdam", regular, 8.5f, MARGIN_LEFT);
        y -= 11;
        show("0331 / 90295-XXXX  |  info@ba-nordstadt.potsdam.de", regular, 8.5f, MARGIN_LEFT);
        y -= 5;

        cs.setStrokingColor(0.55f, 0.55f, 0.55f);
        cs.moveTo(MARGIN_LEFT, y);
        cs.lineTo(PAGE.getWidth() - MARGIN_RIGHT, y);
        cs.stroke();
        y -= 8;
    }

    /** Rotes Vertraulichkeits-Banner wie in der Referenzvorlage. */
    void confidentialBanner() throws IOException {
        float bannerHeight = 18f;
        float bannerY = y - bannerHeight;
        cs.setNonStrokingColor(RED[0], RED[1], RED[2]);
        cs.addRect(MARGIN_LEFT, bannerY, CONTENT_WIDTH, bannerHeight);
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        String text = "INTERNES DOKUMENT - NUR FÜR DIE VERWALTUNG";
        float textWidth = width(text, bold, 9f);
        float textX = MARGIN_LEFT + (CONTENT_WIDTH - textWidth) / 2f;
        float textY = bannerY + (bannerHeight - 9f) / 2f + 1.5f;
        cs.beginText();
        cs.setFont(bold, 9f);
        cs.newLineAtOffset(textX, textY);
        cs.showText(text);
        cs.endText();
        cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
        y -= bannerHeight;
    }

    /** Metadaten als Badge-Zeile unter dem Titel (Vorlagen-Stil). */
    void metadataBadges(String... labelValuePairs) throws IOException {
        if (labelValuePairs == null || labelValuePairs.length % 2 != 0) return;
        ensure(26);
        float x = MARGIN_LEFT;
        float badgeHeight = 18f;
        float paddingX = 8f;
        float gap = 6f;
        for (int i = 0; i < labelValuePairs.length; i += 2) {
            String label = labelValuePairs[i];
            String value = labelValuePairs[i + 1];
            if (value == null || value.isEmpty()) continue;
            String line = label + ": " + value;
            float w = width(line, bold, 8f) + 2 * paddingX;
            if (x + w > PAGE.getWidth() - MARGIN_RIGHT) {
                x = MARGIN_LEFT;
                y -= badgeHeight + gap;
                ensure(badgeHeight + gap);
            }
            float badgeY = y - badgeHeight;
            cs.setNonStrokingColor(GRAY_BG[0], GRAY_BG[1], GRAY_BG[2]);
            cs.addRect(x, badgeY, w, badgeHeight);
            cs.fill();
            cs.setStrokingColor(GRAY_BORDER[0], GRAY_BORDER[1], GRAY_BORDER[2]);
            cs.addRect(x, badgeY, w, badgeHeight);
            cs.stroke();
            cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
            cs.beginText();
            cs.setFont(bold, 8f);
            cs.newLineAtOffset(x + paddingX, badgeY + (badgeHeight - 8f) / 2f + 1f);
            cs.showText(sanitize(line));
            cs.endText();
            x += w + gap;
        }
        if (x != MARGIN_LEFT) y -= badgeHeight + gap;
        cs.setStrokingColor(0.55f, 0.55f, 0.55f);
        cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
    }

    void title(String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        ensure(24);
        show(text, bold, 15.5f, MARGIN_LEFT);
        y -= 21;
    }

    void subtitle(String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        ensure(16);
        show(text, italic, 9f, MARGIN_LEFT);
        y -= 15;
    }

    /** Metadaten-Zeile „Label: Wert" (linke Spalte, Einrückung ab MARGIN_LEFT). */
    void metaRow(String label, String value) throws IOException {
        metaRowAt(label, value, MARGIN_LEFT);
    }

    /** Metadaten-Zeile in der rechten Spalte (ab Dokumentmitte). */
    void metaRowRight(String label, String value) throws IOException {
        metaRowAt(label, value, MID);
    }

    private void metaRowAt(String label, String value, float x) throws IOException {
        ensure(14);
        float labelWidth = width(label + ":  ", bold, 9f);
        show(label + ":", bold, 9f, x);
        if (value != null && !value.isEmpty()) {
            show("  " + value, regular, 9f, x + labelWidth);
        }
        y -= 14;
    }

    void rule() throws IOException {
        ensure(14);
        cs.setStrokingColor(0.75f, 0.75f, 0.75f);
        cs.moveTo(MARGIN_LEFT, y);
        cs.lineTo(PAGE.getWidth() - MARGIN_RIGHT, y);
        cs.stroke();
        y -= 12;
    }

    /** Nummerierter GROSSBUCHSTABEN-Abschnitt im Stil der Referenzvorlage. */
    void section(int number, String text) throws IOException {
        ensure(22);
        show(number + ". " + text.toUpperCase(), bold, 10.5f, MARGIN_LEFT);
        y -= 18;
    }

    void subheading(String text) throws IOException {
        ensure(16);
        show(text.toUpperCase(), bold, 9f, MARGIN_LEFT);
        y -= 14;
    }

    /** Aufzählung mit eckigem Marker (kleines gefülltes Quadrat wie in der Vorlage). */
    void bullet(String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        List<String> lines = wrap(text, regular, 9.5f, CONTENT_WIDTH - 18);
        ensure(lines.size() * 12.5f + 4);
        float markerX = MARGIN_LEFT;
        float markerSize = 3.2f;
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) {
                cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
                cs.addRect(markerX, y - 3.2f, markerSize, markerSize);
                cs.fill();
            }
            show(lines.get(i), regular, 9.5f, MARGIN_LEFT + 12);
            y -= 12.5f;
        }
        y -= 2;
    }

    void note(String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        List<String> lines = wrap(text, italic, 8.5f, CONTENT_WIDTH);
        ensure(lines.size() * 11.5f + 4);
        for (String line : lines) {
            show(line, italic, 8.5f, MARGIN_LEFT);
            y -= 11.5f;
        }
    }

    void spacer(float pts) {
        y -= pts;
    }

    /* ========================================================================
     *  NEU: Visuelle Referenzvorlage (abgerundete Karten, Symbole, zweispaltig)
     * ======================================================================== */

    /** Zeichnet ein abgerundetes Rechteck (Kontur und/oder Füllung). */
    void roundedRect(float x, float yBottom, float w, float h, float r) throws IOException {
        float x0 = x, y0 = yBottom, x1 = x + w, y1 = yBottom + h;
        cs.moveTo(x0 + r, y0);
        cs.lineTo(x1 - r, y0);
        cs.curveTo(x1, y0, x1, y0, x1, y0 + r);
        cs.lineTo(x1, y1 - r);
        cs.curveTo(x1, y1, x1, y1, x1 - r, y1);
        cs.lineTo(x0 + r, y1);
        cs.curveTo(x0, y1, x0, y1, x0, y1 - r);
        cs.lineTo(x0, y0 + r);
        cs.curveTo(x0, y0, x0, y0, x0 + r, y0);
        cs.closePath();
    }

    void setFill(float[] c) throws IOException {
        cs.setNonStrokingColor(c[0], c[1], c[2]);
    }

    void setStroke(float[] c) throws IOException {
        cs.setStrokingColor(c[0], c[1], c[2]);
    }

    /* ---- Symbole (einfache Vektorzeichnungen in einem Begrenzungsrechteck) ---- */

    void drawShield(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setFill(color);
        cs.moveTo(x0 + s * 0.5f, y0 + s * 0.05f);
        cs.curveTo(x0 + s * 0.92f, y0 + s * 0.22f, x0 + s * 0.92f, y0 + s * 0.55f, x0 + s * 0.5f, y0 + s * 0.95f);
        cs.curveTo(x0 + s * 0.08f, y0 + s * 0.55f, x0 + s * 0.08f, y0 + s * 0.22f, x0 + s * 0.5f, y0 + s * 0.05f);
        cs.closePath();
        cs.fill();
    }

    void drawLock(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setFill(color);
        // Bügel
        cs.moveTo(x0 + s * 0.25f, y0 + s * 0.55f);
        cs.curveTo(x0 + s * 0.25f, y0 + s * 0.82f, x0 + s * 0.75f, y0 + s * 0.82f, x0 + s * 0.75f, y0 + s * 0.55f);
        cs.lineTo(x0 + s * 0.65f, y0 + s * 0.55f);
        cs.curveTo(x0 + s * 0.65f, y0 + s * 0.72f, x0 + s * 0.35f, y0 + s * 0.72f, x0 + s * 0.35f, y0 + s * 0.55f);
        cs.closePath();
        cs.fill();
        // Korpus
        cs.addRect(x0 + s * 0.18f, y0 + s * 0.18f, s * 0.64f, s * 0.45f);
        cs.fill();
        // Schlüsselloch
        setFill(new float[]{1f, 1f, 1f});
        cs.addRect(x0 + s * 0.44f, y0 + s * 0.35f, s * 0.12f, s * 0.18f);
        cs.fill();
        cs.addRect(x0 + s * 0.47f, y0 + s * 0.30f, s * 0.06f, s * 0.10f);
        cs.fill();
    }

    void drawBuilding(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        float colW = s * 0.12f, gap = s * 0.10f;
        float baseY = y0 + s * 0.12f;
        // Stufen/Sockel
        cs.addRect(x0 + s * 0.10f, baseY, s * 0.80f, s * 0.06f);
        cs.fill();
        // Säulen
        for (int i = 0; i < 4; i++) {
            cs.addRect(x0 + s * 0.18f + i * (colW + gap), baseY + s * 0.06f, colW, s * 0.50f);
            cs.fill();
        }
        // Dreieck-Giebel
        cs.moveTo(x0 + s * 0.08f, baseY + s * 0.56f);
        cs.lineTo(x0 + s * 0.50f, baseY + s * 0.84f);
        cs.lineTo(x0 + s * 0.92f, baseY + s * 0.56f);
        cs.closePath();
        cs.fill();
        // Kreis im Giebel
        setFill(new float[]{1f, 1f, 1f});
        cs.addRect(x0 + s * 0.43f, baseY + s * 0.62f, s * 0.14f, s * 0.14f);
        cs.fill();
    }

    void drawBuildingLight(float x, float yBottom, float size, float[] color) throws IOException {
        drawBuilding(x, yBottom, size, color);
    }

    void drawPerson(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        // Kopf
        cs.addRect(x0 + s * 0.32f, y0 + s * 0.60f, s * 0.36f, s * 0.30f);
        cs.fill();
        // Körper
        cs.moveTo(x0 + s * 0.12f, y0 + s * 0.58f);
        cs.curveTo(x0 + s * 0.12f, y0 + s * 0.30f, x0 + s * 0.88f, y0 + s * 0.30f, x0 + s * 0.88f, y0 + s * 0.58f);
        cs.lineTo(x0 + s * 0.12f, y0 + s * 0.58f);
        cs.closePath();
        cs.fill();
    }

    void drawFolder(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        cs.moveTo(x0 + s * 0.08f, y0 + s * 0.70f);
        cs.lineTo(x0 + s * 0.28f, y0 + s * 0.70f);
        cs.lineTo(x0 + s * 0.36f, y0 + s * 0.58f);
        cs.lineTo(x0 + s * 0.92f, y0 + s * 0.58f);
        cs.curveTo(x0 + s * 0.96f, y0 + s * 0.58f, x0 + s * 0.98f, y0 + s * 0.54f, x0 + s * 0.98f, y0 + s * 0.48f);
        cs.lineTo(x0 + s * 0.98f, y0 + s * 0.18f);
        cs.curveTo(x0 + s * 0.98f, y0 + s * 0.10f, x0 + s * 0.94f, y0 + s * 0.08f, x0 + s * 0.88f, y0 + s * 0.08f);
        cs.lineTo(x0 + s * 0.12f, y0 + s * 0.08f);
        cs.curveTo(x0 + s * 0.06f, y0 + s * 0.08f, x0 + s * 0.02f, y0 + s * 0.10f, x0 + s * 0.02f, y0 + s * 0.18f);
        cs.lineTo(x0 + s * 0.02f, y0 + s * 0.58f);
        cs.curveTo(x0 + s * 0.02f, y0 + s * 0.66f, x0 + s * 0.06f, y0 + s * 0.70f, x0 + s * 0.08f, y0 + s * 0.70f);
        cs.closePath();
        cs.fill();
    }

    void drawDocument(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        cs.moveTo(x0 + s * 0.12f, y0 + s * 0.88f);
        cs.lineTo(x0 + s * 0.60f, y0 + s * 0.88f);
        cs.lineTo(x0 + s * 0.88f, y0 + s * 0.60f);
        cs.lineTo(x0 + s * 0.88f, y0 + s * 0.12f);
        cs.curveTo(x0 + s * 0.88f, y0 + s * 0.06f, x0 + s * 0.82f, y0 + s * 0.02f, x0 + s * 0.74f, y0 + s * 0.02f);
        cs.lineTo(x0 + s * 0.20f, y0 + s * 0.02f);
        cs.curveTo(x0 + s * 0.12f, y0 + s * 0.02f, x0 + s * 0.06f, y0 + s * 0.06f, x0 + s * 0.06f, y0 + s * 0.12f);
        cs.lineTo(x0 + s * 0.06f, y0 + s * 0.78f);
        cs.curveTo(x0 + s * 0.06f, y0 + s * 0.84f, x0 + s * 0.08f, y0 + s * 0.88f, x0 + s * 0.12f, y0 + s * 0.88f);
        cs.closePath();
        cs.fill();
        // Linien
        setStroke(new float[]{1f, 1f, 1f});
        cs.setLineWidth(0.5f);
        for (int i = 0; i < 3; i++) {
            float ly = y0 + s * 0.28f + i * s * 0.14f;
            cs.moveTo(x0 + s * 0.18f, ly);
            cs.lineTo(x0 + s * 0.62f, ly);
            cs.stroke();
        }
        cs.setLineWidth(1f);
    }

    void drawCheckmark(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        cs.setLineWidth(s * 0.12f);
        cs.moveTo(x0 + s * 0.18f, y0 + s * 0.48f);
        cs.lineTo(x0 + s * 0.42f, y0 + s * 0.24f);
        cs.lineTo(x0 + s * 0.82f, y0 + s * 0.76f);
        cs.stroke();
        cs.setLineWidth(1f);
    }

    void drawWarningTriangle(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        cs.moveTo(x0 + s * 0.50f, y0 + s * 0.88f);
        cs.lineTo(x0 + s * 0.92f, y0 + s * 0.12f);
        cs.curveTo(x0 + s * 0.95f, y0 + s * 0.05f, x0 + s * 0.90f, y0 + s * 0.02f, x0 + s * 0.84f, y0 + s * 0.02f);
        cs.lineTo(x0 + s * 0.16f, y0 + s * 0.02f);
        cs.curveTo(x0 + s * 0.10f, y0 + s * 0.02f, x0 + s * 0.05f, y0 + s * 0.05f, x0 + s * 0.08f, y0 + s * 0.12f);
        cs.closePath();
        cs.fill();
        // Ausrufezeichen
        setFill(new float[]{1f, 1f, 1f});
        cs.addRect(x0 + s * 0.44f, y0 + s * 0.28f, s * 0.12f, s * 0.32f);
        cs.fill();
        cs.addRect(x0 + s * 0.44f, y0 + s * 0.12f, s * 0.12f, s * 0.10f);
        cs.fill();
    }

    void drawQuestion(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        // Kreis
        cs.addRect(x0 + s * 0.10f, y0 + s * 0.28f, s * 0.80f, s * 0.62f);
        cs.fill();
        // Punkt
        setFill(new float[]{1f, 1f, 1f});
        cs.addRect(x0 + s * 0.44f, y0 + s * 0.12f, s * 0.12f, s * 0.12f);
        cs.fill();
        // Fragezeichenbogen (vereinfacht)
        cs.addRect(x0 + s * 0.40f, y0 + s * 0.36f, s * 0.20f, s * 0.32f);
        cs.fill();
    }

    void drawChecklist(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        // Klemmbrett
        cs.addRect(x0 + s * 0.12f, y0 + s * 0.18f, s * 0.76f, s * 0.70f);
        cs.fill();
        // Clip
        setFill(new float[]{1f, 1f, 1f});
        cs.addRect(x0 + s * 0.36f, y0 + s * 0.78f, s * 0.28f, s * 0.10f);
        cs.fill();
        // Häkchen + Linien
        cs.setLineWidth(0.5f);
        for (int i = 0; i < 3; i++) {
            float ly = y0 + s * 0.30f + i * s * 0.14f;
            // Kästchen
            cs.addRect(x0 + s * 0.20f, ly, s * 0.10f, s * 0.10f);
            cs.fill();
            // Strich
            cs.moveTo(x0 + s * 0.36f, ly + s * 0.04f);
            cs.lineTo(x0 + s * 0.76f, ly + s * 0.04f);
            cs.stroke();
        }
        cs.setLineWidth(1f);
    }

    void drawStamp(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setStroke(color);
        setFill(color);
        // Griff
        cs.addRect(x0 + s * 0.40f, y0 + s * 0.62f, s * 0.20f, s * 0.30f);
        cs.fill();
        // Grundplatte
        cs.addRect(x0 + s * 0.15f, y0 + s * 0.18f, s * 0.70f, s * 0.44f);
        cs.fill();
        // Innenrand
        setStroke(new float[]{1f, 1f, 1f});
        cs.setLineWidth(0.5f);
        cs.addRect(x0 + s * 0.22f, y0 + s * 0.24f, s * 0.56f, s * 0.30f);
        cs.stroke();
        cs.setLineWidth(1f);
    }

    /* ---- Referenz-Kopfzeile mit Logo, Kontaktblock und Vorgangskarte ---- */

    /**
     * Kompletter Kopf im Stil der Referenzvorlage: rotes Vertraulichkeits-Banner mit
     * Schloss-Symbol, links der Bezirksamt-Logo-Block, rechts die abgerundete
     * Vorgangsinfokarte, darunter Titel, Status-Karten und Datumszeile.
     */
    void referenceHeader(String caseName, String caseType,
                         String ownerName, String ownerRole, String ownerRoom,
                         String ownerPhone, String ownerEmail,
                         String workspaceCode, String fileReference) throws IOException {
        ensure(170);

        // 1. Rotes Banner mit Icon
        float bannerH = 28f;
        float bannerY = y - bannerH;
        setFill(RED);
        roundedRect(MARGIN_LEFT, bannerY, CONTENT_WIDTH, bannerH, 6f);
        cs.fill();
        float iconSize = 16f;
        drawShield(MARGIN_LEFT + 12, bannerY + (bannerH - iconSize) / 2f, iconSize, new float[]{1f, 1f, 1f});
        drawLock(MARGIN_LEFT + 14, bannerY + (bannerH - iconSize) / 2f + 2f, iconSize * 0.55f, RED);

        String titleLine = "INTERNES DOKUMENT – NUR FÜR DIE VERWALTUNG";
        show(titleLine, bold, 10.5f, MARGIN_LEFT + 12 + iconSize + 10, bannerY + (bannerH + 10.5f) / 2f + 2f);
        String subLine = "Vertrauliche Informationen – keine Weitergabe an Dritte";
        show(subLine, regular, 7.5f, MARGIN_LEFT + 12 + iconSize + 10, bannerY + (bannerH - 7.5f) / 2f - 1f);
        y -= bannerH + 8;

        // 2. Logo-Block links
        float logoSize = 38f;
        setFill(BLUE);
        cs.addRect(MARGIN_LEFT, y - logoSize, logoSize, logoSize);
        cs.fill();
        drawBuilding(MARGIN_LEFT + 6, y - logoSize + 6, logoSize - 12, new float[]{1f, 1f, 1f});

        float tx = MARGIN_LEFT + logoSize + 10;
        float ty = y;
        show("BEZIRKSAMT", bold, 12f, tx);
        ty -= 15;
        show("NORDSTADT", bold, 12f, tx, ty);
        ty -= 13;
        show("VON POTSDAM", bold, 9f, tx, ty);
        ty -= 12;
        show("Abt. Stadtentwicklung und Bürgerdienste", regular, 8.5f, tx, ty);
        ty -= 12;
        show("Amt für Bürgerdienste · Bürgeramt Nordstadt", regular, 8.5f, tx, ty);
        ty -= 12;
        // Zeile mit Pin + Adresse
        drawPin(tx, ty + 2, 8f, BLUE);
        show("Fiktive Straße 24a-26 · 14467 Potsdam", regular, 8f, tx + 10, ty);
        ty -= 12;
        // Telefon + E-Mail
        show("0331 / 90295-XXXX", regular, 8f, tx, ty);
        show("|", regular, 8f, tx + width("0331 / 90295-XXXX", regular, 8f) + 8, ty);
        show("info@ba-nordstadt.potsdam.de", regular, 8f, tx + width("0331 / 90295-XXXX", regular, 8f) + 18, ty);

        // 3. Rechte Vorgangskarte
        float cardW = 185f;
        float cardH = 128f;
        float cardX = PAGE.getWidth() - MARGIN_RIGHT - cardW;
        float cardY = y - cardH;
        setFill(new float[]{1f, 1f, 1f});
        roundedRect(cardX, cardY, cardW, cardH, 6f);
        cs.fill();
        setStroke(GRAY_BORDER);
        roundedRect(cardX, cardY, cardW, cardH, 6f);
        cs.stroke();

        float cx = cardX + 10;
        float cy = y - 6;
        drawPerson(cx, cy - 16, 16f, BLUE);
        show("Bearbeitet von", bold, 8f, cx + 20, cy - 4);
        cy -= 12;
        show(nullTo(ownerName, "Max Mustermann"), bold, 10f, cx + 20, cy);
        cy -= 10;
        show(nullTo(ownerRole, "Sachbearbeitung Bürgerdienste"), regular, 8f, cx + 20, cy);
        cy -= 10;
        show(nullTo(ownerRoom, "Zimmer: 2.15"), regular, 8f, cx + 20, cy);
        cy -= 10;
        show(nullTo(ownerPhone, "0331 / 90295-1234"), regular, 8f, cx + 20, cy);
        cy -= 10;
        show(nullTo(ownerEmail, "max.mustermann@ba-nordstadt.potsdam.de"), regular, 7.5f, cx + 20, cy);

        // Trennlinie in der Karte
        cs.setStrokingColor(GRAY_BORDER[0], GRAY_BORDER[1], GRAY_BORDER[2]);
        cs.moveTo(cardX + 8, cardY + 54);
        cs.lineTo(cardX + cardW - 8, cardY + 54);
        cs.stroke();

        cy = cardY + 44;
        metaPair(cx, cy, "Vorgangsnummer:", nullTo(workspaceCode, "—"), 8f);
        cy -= 13;
        metaPair(cx, cy, "Aktenzeichen:", nullTo(fileReference, "—"), 8f);
        cy -= 13;
        metaPair(cx, cy, "Fallart:", nullTo(caseType, "—"), 8f);

        y = Math.min(cardY, ty - 6);

        // 4. Titel
        y -= 14;
        ensure(44);
        show(caseName, bold, 18f, MARGIN_LEFT);
        y -= 18;
        show("Entscheidungsvorlage (maschinell erstellt)", regular, 10f, MARGIN_LEFT);
        y -= 6;
    }

    private void drawPin(float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        setFill(color);
        cs.addRect(x0 + s * 0.35f, y0 + s * 0.45f, s * 0.30f, s * 0.45f);
        cs.fill();
        cs.addRect(x0 + s * 0.20f, y0 + s * 0.55f, s * 0.60f, s * 0.30f);
        cs.fill();
    }

    private void metaPair(float x, float yPos, String label, String value, float size) throws IOException {
        show(label, bold, size, x, yPos);
        show(value, regular, size, x + width(label, bold, size) + 4, yPos);
    }

    private static String nullTo(String v, String fallback) {
        return v != null && !v.isBlank() ? v : fallback;
    }

    /** Drei Status-Karten nebeneinander (Bearbeitungsstand, Dokumente, Konfidenz). */
    void statusCards(String phase, String documentCount, String confidence) throws IOException {
        ensure(48);
        float cardW = (CONTENT_WIDTH - 16f) / 3f;
        float cardH = 40f;
        String[] labels = {"Bearbeitungsstand", "Dokumente im Vorgang", "Gesamtkonfidenz"};
        String[] values = {nullTo(phase, "—"), nullTo(documentCount, "—"), nullTo(confidence, "—")};

        for (int i = 0; i < 3; i++) {
            float cx = MARGIN_LEFT + i * (cardW + 8f);
            float cy = y;
            setFill(GRAY_BG);
            roundedRect(cx, y - cardH, cardW, cardH, 5f);
            cs.fill();
            setStroke(GRAY_BORDER);
            roundedRect(cx, y - cardH, cardW, cardH, 5f);
            cs.stroke();

            float iconSize = 20f;
            if (i == 0) drawFolder(cx + 10, cy - cardH + (cardH - iconSize) / 2f, iconSize, BLUE);
            else if (i == 1) drawDocument(cx + 10, cy - cardH + (cardH - iconSize) / 2f, iconSize, BLUE);
            else drawShield(cx + 10, cy - cardH + (cardH - iconSize) / 2f, iconSize, GREEN);

            float tx = cx + 10 + iconSize + 8;
            show(labels[i], regular, 7.5f, tx, cy - 10);
            show(values[i], bold, 12f, tx, cy - 26);
        }
        y -= cardH + 6;
    }

    /** Analyse- und Export-Datum mit vertikalem Trenner. */
    void dateLine(String requestedAt, String generatedAt) throws IOException {
        ensure(14);
        String left = "Analyse erstellt am: " + nullTo(requestedAt, "—");
        String right = "Export erstellt am: " + nullTo(generatedAt, "—");
        show(left, regular, 8.5f, MARGIN_LEFT);
        float sepX = MARGIN_LEFT + width(left, regular, 8.5f) + 12;
        cs.setStrokingColor(0.75f, 0.75f, 0.75f);
        cs.moveTo(sepX, y - 1);
        cs.lineTo(sepX, y + 7);
        cs.stroke();
        show(right, regular, 8.5f, sepX + 12);
        y -= 12;
    }

    /** Orange Empfehlungsbox mit Warnsymbol. */
    void recommendationBox(int number, String title, String answer, boolean grounded) throws IOException {
        ensure(80);
        // Schätze Höhe
        List<String> answerLines = wrap(nullTo(answer, ""), regular, 9.5f, CONTENT_WIDTH - 58);
        String note = grounded
                ? "Durch Quellen belegt - die Empfehlung ist durch die herangezogenen Unterlagen gedeckt."
                : "Hinweis: Eingeschränkte Quellenlage — die Empfehlung ist vor der Umsetzung besonders sorgfältig zu prüfen.";
        List<String> noteLines = wrap(note, italic, 8.5f, CONTENT_WIDTH - 58);
        float boxH = 18 + answerLines.size() * 12.5f + 6 + noteLines.size() * 11f + 10;

        float boxY = y - boxH;
        setFill(ORANGE_BG);
        roundedRect(MARGIN_LEFT, boxY, CONTENT_WIDTH, boxH, 6f);
        cs.fill();
        setStroke(ORANGE);
        roundedRect(MARGIN_LEFT, boxY, CONTENT_WIDTH, boxH, 6f);
        cs.stroke();

        float iconSize = 26f;
        drawWarningTriangle(MARGIN_LEFT + 14, boxY + boxH - iconSize - 10, iconSize, ORANGE);

        float tx = MARGIN_LEFT + 14 + iconSize + 12;
        show(number + ". " + title.toUpperCase(), bold, 11f, tx, y - 14);
        float cy = y - 28;
        for (String line : answerLines) {
            show(line, regular, 9.5f, tx, cy);
            cy -= 12.5f;
        }
        cy -= 2;
        setFill(ORANGE);
        for (String line : noteLines) {
            show(line, italic, 8.5f, tx, cy);
            cy -= 11f;
        }
        y = boxY - 8;
    }

    /** Zwei gleich hohe Spalten nebeneinander (linke und rechte Inhalte). */
    void twoColumnBlock(TwoColumnContent left, TwoColumnContent right) throws IOException {
        ensure(30);
        float colGap = 18f;
        float colW = (CONTENT_WIDTH - colGap) / 2f;
        float topY = y;

        // Linke Spalte
        float leftY = renderColumn(left, MARGIN_LEFT, colW, topY);
        // Rechte Spalte
        float rightY = renderColumn(right, MARGIN_LEFT + colW + colGap, colW, topY);

        // Vertikaler Trennstrich zwischen den Spalten (im Referenz-Stil)
        float bottomY = Math.min(leftY, rightY) + 8;
        cs.setStrokingColor(GRAY_BORDER[0], GRAY_BORDER[1], GRAY_BORDER[2]);
        cs.setLineDashPattern(new float[]{2f, 2f}, 0);
        cs.moveTo(MARGIN_LEFT + colW + colGap / 2f, topY);
        cs.lineTo(MARGIN_LEFT + colW + colGap / 2f, bottomY);
        cs.stroke();
        cs.setLineDashPattern(new float[]{}, 0);

        y = bottomY - 8;
    }

    private float renderColumn(TwoColumnContent col, float x, float colW, float startY) throws IOException {
        if (col == null || col.sections.isEmpty()) return startY;
        float cy = startY;
        for (Section s : col.sections) {
            cy = ensureAndRenderSection(s, x, colW, cy);
        }
        return cy;
    }

    private float ensureAndRenderSection(Section s, float x, float colW, float startY) throws IOException {
        float cy = startY;
        if (cy - 24 < MARGIN_BOTTOM) {
            cs.close();
            newPage();
            cy = y;
        }
        // Sektionsüberschrift mit Icon
        float iconSize = 18f;
        drawIcon(s.icon, x, cy - iconSize - 2, iconSize, BLUE);
        show(s.number + ". " + s.title.toUpperCase(), bold, 10f, x + iconSize + 6, cy - 4);
        cy -= 22;

        for (Block b : s.blocks) {
            if (cy - 14 < MARGIN_BOTTOM) {
                cs.close();
                newPage();
                cy = y;
            }
            if (b.subheading != null && !b.subheading.isEmpty()) {
                setFill(BLUE);
                show(b.subheading, bold, 8.5f, x, cy);
                cy -= 12;
            }
            for (String item : b.items) {
                cy = renderBullet(item, x, colW, cy);
            }
            cy -= 2;
        }
        return cy;
    }

    private float renderBullet(String text, float x, float maxW, float startY) throws IOException {
        if (text == null || text.isEmpty()) return startY;
        List<String> lines = wrap(text, regular, 9f, maxW - 14);
        if (startY - lines.size() * 11 < MARGIN_BOTTOM) {
            cs.close();
            newPage();
            startY = y;
        }
        float cy = startY;
        cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
        cs.addRect(x, cy - 3f, 3f, 3f);
        cs.fill();
        for (int i = 0; i < lines.size(); i++) {
            show(lines.get(i), regular, 9f, x + 10, cy);
            cy -= 11f;
        }
        cy -= 2;
        return cy;
    }

    private void drawIcon(Icon icon, float x, float yBottom, float size, float[] color) throws IOException {
        switch (icon) {
            case DOCUMENT -> drawDocument(x, yBottom, size, color);
            case QUESTION -> drawQuestion(x, yBottom, size, color);
            case CHECKLIST -> drawChecklist(x, yBottom, size, color);
            case FOLDER -> drawFolder(x, yBottom, size, color);
            case SHIELD -> drawShield(x, yBottom, size, color);
            case BUILDING -> drawBuilding(x, yBottom, size, color);
            case WARNING -> drawWarningTriangle(x, yBottom, size, color);
        }
    }

    public enum Icon { DOCUMENT, QUESTION, CHECKLIST, FOLDER, SHIELD, BUILDING, WARNING }

    public static class TwoColumnContent {
        final List<Section> sections = new ArrayList<>();
        public TwoColumnContent section(int number, String title, Icon icon) {
            Section s = new Section(number, title, icon);
            sections.add(s);
            return this;
        }
        public TwoColumnContent block(String subheading, List<String> items) {
            if (!sections.isEmpty()) sections.getLast().blocks.add(new Block(subheading, items));
            return this;
        }
    }

    static class Section {
        final int number;
        final String title;
        final Icon icon;
        final List<Block> blocks = new ArrayList<>();
        Section(int number, String title, Icon icon) {
            this.number = number;
            this.title = title;
            this.icon = icon;
        }
    }

    static class Block {
        final String subheading;
        final List<String> items;
        Block(String subheading, List<String> items) {
            this.subheading = subheading;
            this.items = items != null ? items : List.of();
        }
    }

    /** Untere Freigabe-Status-Karte mit Stempel-Symbol, Unterschriften und Disclaimer. */
    void releaseStatusBox(String disclaimer, String versionText) throws IOException {
        ensure(92);
        float boxH = 78f;
        float boxY = y - boxH;
        setFill(new float[]{1f, 1f, 1f});
        roundedRect(MARGIN_LEFT, boxY, CONTENT_WIDTH, boxH, 6f);
        cs.fill();
        setStroke(GRAY_BORDER);
        roundedRect(MARGIN_LEFT, boxY, CONTENT_WIDTH, boxH, 6f);
        cs.stroke();

        float iconSize = 32f;
        drawStamp(MARGIN_LEFT + 14, boxY + boxH - iconSize - 14, iconSize, BLUE);

        float tx = MARGIN_LEFT + 14 + iconSize + 12;
        show("PRÜF- UND FREIGABESTATUS", bold, 11f, tx, y - 14);

        float cy = y - 34;
        float leftColW = (CONTENT_WIDTH - iconSize - 40) / 2f;
        String[] labels = {"Geprüft von:", "Datum:", "Freigegeben von:", "Datum:"};
        for (String label : labels) {
            show(label, bold, 8.5f, tx, cy);
            cs.setStrokingColor(0.6f, 0.6f, 0.6f);
            cs.moveTo(tx + width(label, bold, 8.5f) + 6, cy + 1);
            cs.lineTo(tx + leftColW, cy + 1);
            cs.stroke();
            cy -= 16;
        }

        // Disclaimer rechts
        float rightX = tx + leftColW + 16;
        List<String> discLines = wrap(disclaimer, regular, 8f, CONTENT_WIDTH - (rightX - MARGIN_LEFT) - 10);
        cy = y - 34;
        for (String line : discLines) {
            show(line, regular, 8f, rightX, cy);
            cy -= 11.5f;
        }

        // Wasserzeichen-Gebäude rechts unten
        drawBuilding(PAGE.getWidth() - MARGIN_RIGHT - 38, boxY + 8, 32f, new float[]{0.85f, 0.85f, 0.85f});

        y = boxY - 10;
    }

    /**
     * Fußzeile im Stil der Referenzvorlage: Gebäude-Icon links, Version/Dokumentart,
     * zentrale Behördenbezeichnung, Seitenzahl und Exportzeitstempel rechts.
     */
    public static void writeReferenceFooters(PDDocument doc, PDFont regular, PDFont bold,
            String versionText, String exportAt) throws IOException {
        int pageCount = doc.getNumberOfPages();
        for (int i = 0; i < pageCount; i++) {
            PDPage page = doc.getPage(i);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setStrokingColor(0.6f, 0.6f, 0.6f);
                cs.moveTo(MARGIN_LEFT, FOOTER_RULE_Y);
                cs.lineTo(PAGE.getWidth() - MARGIN_RIGHT, FOOTER_RULE_Y);
                cs.stroke();

                float y0 = FOOTER_RULE_Y - 18;
                // Gebäude-Icon
                DecisionPdfLayout temp = null; // Icon-Zeichnung inline
                float iconSize = 14f;
                setFillStatic(cs, BLUE);
                cs.addRect(MARGIN_LEFT, y0 - iconSize + 4, iconSize, iconSize);
                cs.fill();
                drawBuildingStatic(cs, MARGIN_LEFT + 2, y0 - iconSize + 5, iconSize - 4, new float[]{1f, 1f, 1f});

                // Linke Spalte
                cs.beginText();
                cs.setFont(bold, 8f);
                cs.setNonStrokingColor(DARK[0], DARK[1], DARK[2]);
                cs.newLineAtOffset(MARGIN_LEFT + iconSize + 6, y0);
                cs.showText(sanitize(versionText));
                cs.endText();

                cs.beginText();
                cs.setFont(regular, 8f);
                cs.newLineAtOffset(MARGIN_LEFT + iconSize + 6, y0 - 11);
                cs.showText("Entscheidungsvorlage");
                cs.endText();

                // Mittlere Spalte
                String[] center = {
                        "Bezirksamt Nordstadt von Potsdam",
                        "Amt für Bürgerdienste · Bürgeramt Nordstadt"
                };
                for (int j = 0; j < center.length; j++) {
                    float cw = regular.getStringWidth(sanitize(center[j])) / 1000f * 8f;
                    cs.beginText();
                    cs.setFont(regular, 8f);
                    cs.newLineAtOffset((PAGE.getWidth() - cw) / 2f, y0 - j * 11);
                    cs.showText(sanitize(center[j]));
                    cs.endText();
                }

                // Rechte Spalte
                String pageNum = "Seite " + (i + 1) + " von " + pageCount;
                float rightW = regular.getStringWidth(sanitize(pageNum)) / 1000f * 8f;
                cs.beginText();
                cs.setFont(regular, 8f);
                cs.newLineAtOffset(PAGE.getWidth() - MARGIN_RIGHT - rightW, y0);
                cs.showText(sanitize(pageNum));
                cs.endText();

                if (exportAt != null && !exportAt.isEmpty()) {
                    String exportLine = "Export: " + exportAt;
                    float ew = regular.getStringWidth(sanitize(exportLine)) / 1000f * 8f;
                    cs.beginText();
                    cs.setFont(regular, 8f);
                    cs.newLineAtOffset(PAGE.getWidth() - MARGIN_RIGHT - ew, y0 - 11);
                    cs.showText(sanitize(exportLine));
                    cs.endText();
                }
            }
        }
    }

    private static void setFillStatic(PDPageContentStream cs, float[] c) throws IOException {
        cs.setNonStrokingColor(c[0], c[1], c[2]);
    }

    private static void drawBuildingStatic(PDPageContentStream cs, float x, float yBottom, float size, float[] color) throws IOException {
        float s = size, x0 = x, y0 = yBottom;
        cs.setStrokingColor(color[0], color[1], color[2]);
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        float colW = s * 0.12f, gap = s * 0.10f;
        float baseY = y0 + s * 0.12f;
        cs.addRect(x0 + s * 0.10f, baseY, s * 0.80f, s * 0.06f);
        cs.fill();
        for (int i = 0; i < 4; i++) {
            cs.addRect(x0 + s * 0.18f + i * (colW + gap), baseY + s * 0.06f, colW, s * 0.50f);
            cs.fill();
        }
        cs.moveTo(x0 + s * 0.08f, baseY + s * 0.56f);
        cs.lineTo(x0 + s * 0.50f, baseY + s * 0.84f);
        cs.lineTo(x0 + s * 0.92f, baseY + s * 0.56f);
        cs.closePath();
        cs.fill();
        cs.setNonStrokingColor(1f, 1f, 1f);
        cs.addRect(x0 + s * 0.43f, baseY + s * 0.62f, s * 0.14f, s * 0.14f);
        cs.fill();
    }

    /**
     * Fußzeile im Stil der Referenzvorlage: Signaturzeilen („von:" /
     * „Freigegeben von:") und Versionszeile vor der Trennlinie, darunter die
     * Dokumentbezeichnung links und die Seitenzahl rechts. Der fiktive
     * Kommunal-Block der bisherigen Vorlage entfällt zugunsten der
     * Referenz-Fußzeile.
     */
    public static void writePageFooters(PDDocument doc, PDFont regular, PDFont bold,
            String leftText, String centerText, String versionText) throws IOException {
        int pageCount = doc.getNumberOfPages();
        for (int i = 0; i < pageCount; i++) {
            PDPage page = doc.getPage(i);
            boolean lastPage = (i == pageCount - 1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                if (lastPage) {
                    writeSignatureLines(cs, regular, versionText);
                }
                cs.setStrokingColor(0.6f, 0.6f, 0.6f);
                cs.moveTo(MARGIN_LEFT, FOOTER_RULE_Y);
                cs.lineTo(PAGE.getWidth() - MARGIN_RIGHT, FOOTER_RULE_Y);
                cs.stroke();

                cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
                cs.setFont(regular, 8f);
                cs.beginText();
                cs.newLineAtOffset(MARGIN_LEFT, FOOTER_RULE_Y - 10);
                cs.showText(sanitize(leftText));
                cs.endText();
                String pageNum = "Seite " + (i + 1) + " von " + pageCount;
                float rightTextWidth = regular.getStringWidth(sanitize(pageNum)) / 1000f * 8f;
                cs.beginText();
                cs.newLineAtOffset(PAGE.getWidth() - MARGIN_RIGHT - rightTextWidth, FOOTER_RULE_Y - 10);
                cs.showText(sanitize(pageNum));
                cs.endText();
                if (centerText != null && !centerText.isEmpty()) {
                    float centerWidth = regular.getStringWidth(sanitize(centerText)) / 1000f * 8f;
                    cs.beginText();
                    cs.newLineAtOffset((PAGE.getWidth() - centerWidth) / 2f, FOOTER_RULE_Y - 10);
                    cs.showText(sanitize(centerText));
                    cs.endText();
                }
            }
        }
    }

    /** Versionszeile + Signaturzeilen auf der letzten Seite über der Trennlinie. */
    private static void writeSignatureLines(PDPageContentStream cs, PDFont regular,
                                            String versionText) throws IOException {
        float y0 = FOOTER_RULE_Y - 42;
        cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
        cs.setFont(regular, 7.5f);
        cs.beginText();
        cs.newLineAtOffset(MARGIN_LEFT, FOOTER_RULE_Y - 24);
        cs.showText(sanitize(versionText));
        cs.endText();
        cs.setNonStrokingColor(0.25f, 0.25f, 0.25f);
        cs.setFont(regular, 8.5f);
        cs.beginText();
        cs.newLineAtOffset(MARGIN_LEFT, y0);
        cs.showText("von:");
        cs.endText();
        cs.beginText();
        cs.newLineAtOffset(MID, y0);
        cs.showText("Freigegeben von:");
        cs.endText();
        cs.setStrokingColor(0.45f, 0.45f, 0.45f);
        float lineY = y0 - 3;
        cs.moveTo(MARGIN_LEFT + 22, lineY);
        cs.lineTo(MID - 6, lineY);
        cs.stroke();
        cs.moveTo(MID + 62, lineY);
        cs.lineTo(PAGE.getWidth() - MARGIN_RIGHT, lineY);
        cs.stroke();
    }

    /** Ersetzt Zeichen, die in der Helvetica-WinAnsi-Codierung fehlen. */
    static String sanitize(String text) {
        if (text == null || text.indexOf('—') < 0) return text;
        return text.replace('—', '-');
    }
}
