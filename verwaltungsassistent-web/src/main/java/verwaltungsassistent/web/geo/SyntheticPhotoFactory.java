package verwaltungsassistent.web.geo;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Erzeugt deterministische synthetische Demo-Fotos (JPEG, 640×480) mit
 * eingebettetem EXIF-GPS und Aufnahmezeitpunkt. Die Bilder sind KEINE
 * Texttafeln, sondern vereinfachte Szenen-Darstellungen der gemeldeten
 * Sachverhalte (Baustelle, Straßenschaden, Müllablagerung, defekte
 * Straßenbeleuchtung, Verkehrsschild, Falschparken, verwaistes Fahrzeug,
 * Überflutung) — gezeichnete schematische Bilder, kein Foto. Sie bleiben
 * klar als synthetisches Demo-Material gekennzeichnet („Synthetisches
 * Demo-Bild"): keine Personen, keine Kennzeichen, keine erkennbaren
 * Privatadressen. Die GPS-Positionen sind fest vorgegeben und lösen über den
 * GeoService deterministisch auf das beabsichtigte Brandenburger
 * Zuständigkeitsgebiet auf.
 */
public final class SyntheticPhotoFactory {

    private static final int W = 640;
    private static final int H = 480;

    /** Szenentyp des gemeldeten Sachverhalts (bestimmt die Bild-Darstellung). */
    public enum Scene {
        BAUSTELLE, STRASSENSCHADEN, MUELL, STRASSENBELEUCHTUNG,
        VERKEHRSSCHILD, FALSCHPARKEN, VERLASSENES_FAHRZEUG, UEBERSCHWEMMUNG, GENERIC
    }

    private SyntheticPhotoFactory() {
    }

    /**
     * Baut aus einer archivierten Übungsaufnahme (JPEG, Pixel unverändert bis
     * auf eventuelle Metadaten-Bereinigung) das Demo-Foto für eine
     * Geotagged-Foto-Szene: sämtliche Original-Metadaten (EXIF/XMP/ICC) werden
     * entfernt und durch deterministische Demo-EXIF-Daten (GPS-Position des
     * Szenarios + Aufnahmezeitpunkt) ersetzt. Die Pixel sind ein echtes Foto —
     * keine schematische Zeichnung. Der nachgelagerte Import liest die
     * Koordinaten weiterhin aus dem EXIF (gpsSource = "EXIF").
     */
    public static byte[] demoPhoto(byte[] archiveJpeg, double lat, double lon,
                                   LocalDateTime capturedAt) throws IOException {
        return insertExif(GeoPhotoService.stripJpegMetadata(archiveJpeg), lat, lon, capturedAt);
    }

    /** Schreibt das synthetische Foto inkl. EXIF-GPS; deterministisch pro Aufruf. */
    public static void generate(Path target, String title, String subtitle,
                                double lat, double lon, LocalDateTime capturedAt) throws IOException {
        generate(target, title, subtitle, Scene.GENERIC, lat, lon, capturedAt);
    }

    /** Wie oben, aber mit szenenspezifischer Bild-Darstellung. */
    public static void generate(Path target, String title, String subtitle, Scene scene,
                                double lat, double lon, LocalDateTime capturedAt) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        switch (scene) {
            case BAUSTELLE -> baustelle(g);
            case STRASSENSCHADEN -> strassenschaden(g);
            case MUELL -> muell(g);
            case STRASSENBELEUCHTUNG -> strassenbeleuchtung(g);
            case VERKEHRSSCHILD -> verkehrsschild(g);
            case FALSCHPARKEN -> falschparken(g);
            case VERLASSENES_FAHRZEUG -> verwaistesFahrzeug(g);
            case UEBERSCHWEMMUNG -> ueberschwemmung(g);
            default -> generic(g);
        }

        // Kennzeichnung (klein, am unteren Rand — Szeneninhalt bleibt sichtbar)
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRect(0, H - 22, W, 22);
        g.setColor(new Color(90, 100, 115));
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("Synthetisches Demo-Bild (keine reale Aufnahme)", 10, H - 8);

        // Leichte Fotorealismus-Filter: Körnung und Vignette, damit die
        // schematische Szene nicht wie eine flache Zeichnung wirkt.
        addNoise(g, W, H, 123456789L);
        addVignette(g, W, H);
        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        byte[] jpeg = insertExif(bos.toByteArray(), lat, lon, capturedAt);
        Files.write(target, jpeg);
    }

    // ── Szenen (schematisch, deterministisch) ─────────────────────────────

    /** Sky: heller Himmel nach unten zum Horizont. */
    private static void sky(Graphics2D g, Color top, Color horizon, int horizonY) {
        for (int y = 0; y < horizonY; y++) {
            float t = (float) y / Math.max(1, horizonY);
            g.setColor(blend(top, horizon, t));
            g.drawLine(0, y, W, y);
        }
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Subtle deterministic film grain so the drawing feels less flat. */
    private static void addNoise(Graphics2D g, int w, int h, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        java.awt.image.BufferedImage noise = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = rnd.nextInt(40) - 20;
                int a = 18 + rnd.nextInt(10);
                noise.setRGB(x, y, new Color(clamp(v + 128), clamp(v + 128), clamp(v + 128), a).getRGB());
            }
        }
        g.drawImage(noise, 0, 0, null);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Darkens the corners slightly like a real camera lens. */
    private static void addVignette(Graphics2D g, int w, int h) {
        java.awt.RadialGradientPaint paint = new java.awt.RadialGradientPaint(
                new java.awt.geom.Point2D.Float(w / 2f, h / 2f),
                Math.max(w, h) * 0.75f,
                new float[]{0.0f, 1.0f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 55)});
        java.awt.Paint old = g.getPaint();
        g.setPaint(paint);
        g.fillRect(0, 0, w, h);
        g.setPaint(old);
    }

    /** Baustelle: umgestürzte Absperrzaun-Elemente, Baustellen-Schild, Kies. */
    private static void baustelle(Graphics2D g) {
        sky(g, new Color(150, 185, 220), new Color(225, 232, 238), 200);
        g.setColor(new Color(190, 175, 150));
        g.fillRect(0, 200, W, 160); // Erdreich
        g.setColor(new Color(165, 150, 125));
        g.fillOval(-40, 300, W + 80, 220); // Kieshügel unten
        // Absperrzaun: zwei umgestürzte Elemente (rot-weiße Schrägstreifen)
        absperrzaun(g, 120, 235, 260, -18);
        absperrzaun(g, 330, 260, 300, -30);
        // Aufrecht stehendes Element hinten
        g.translate(500, 240);
        g.rotate(-0.06);
        absperrzaunAtOrigin(g, 70);
        g.rotate(0.06);
        g.translate(-500, -240);
        // Warnschild "Achtung Baustelle"
        g.setColor(new Color(60, 65, 70));
        g.fillRect(40, 210, 8, 90);
        g.setColor(new Color(255, 214, 0));
        g.fillPolygon(new int[]{20, 68, 44}, new int[]{215, 215, 175}, 3);
        g.setColor(new Color(40, 40, 45));
        g.fillPolygon(new int[]{32, 56, 44}, new int[]{205, 205, 185}, 3);
        // Schuttbox
        g.setColor(new Color(120, 95, 60));
        g.fillRect(430, 250, 130, 70);
        g.setColor(new Color(95, 75, 50));
        g.fillRect(430, 250, 130, 8);
    }

    private static void absperrzaun(Graphics2D g, int x, int y, int w, int angle) {
        g.translate(x, y);
        g.rotate(Math.toRadians(angle));
        absperrzaunAtOrigin(g, w);
        g.rotate(-Math.toRadians(angle));
        g.translate(-x, -y);
    }

    private static void absperrzaunAtOrigin(Graphics2D g, int w) {
        for (int i = 0; i < 4; i++) {
            g.setColor(i % 2 == 0 ? new Color(220, 90, 60) : new Color(240, 238, 230));
            g.fillRect(i * (w / 4), 0, w / 4, 22);
        }
        g.setColor(new Color(90, 95, 100));
        g.fillRect(0, 2, w, 4);
        g.fillRect(0, 16, w, 4);
    }

    /** Straßenschaden: Asphalt mit Schlagloch und Rissen, Warnkegel. */
    private static void strassenschaden(Graphics2D g) {
        sky(g, new Color(140, 175, 215), new Color(220, 228, 236), 170);
        g.setColor(new Color(235, 232, 224));
        g.fillRect(0, 160, W, 40); // Gehweg
        g.setColor(new Color(88, 90, 94));
        g.fillRect(0, 200, W, 280); // Asphalt
        g.setColor(new Color(74, 76, 80));
        g.fillRect(0, 200, W, 6); // Bordstein-Schatten
        // Risse
        g.setColor(new Color(52, 54, 58));
        g.setStroke(new BasicStroke(3));
        g.drawLine(90, 250, 140, 300);
        g.drawLine(140, 300, 120, 350);
        g.drawLine(420, 260, 380, 330);
        g.drawLine(380, 330, 410, 400);
        g.drawLine(250, 210, 260, 260);
        // Schlagloch
        g.setColor(new Color(40, 42, 46));
        g.fill(new Ellipse2D.Double(200, 270, 130, 70));
        g.setColor(new Color(58, 60, 64));
        g.setStroke(new BasicStroke(2));
        g.draw(new Ellipse2D.Double(200, 270, 130, 70));
        g.fillOval(215, 280, 30, 16);
        // Warnkegel
        kegel(g, 380, 240, 55);
        kegel(g, 90, 380, 45);
    }

    private static void kegel(Graphics2D g, int x, int y, int h) {
        g.setColor(new Color(230, 90, 60));
        g.fillPolygon(new int[]{x - h / 5, x + h / 5, x}, new int[]{y, y, y - h}, 3);
        g.setColor(new Color(240, 240, 235));
        g.fillRect(x - h / 4, y - h * 3 / 5, h / 2, h / 6);
        g.setColor(new Color(60, 62, 66));
        g.fillRect(x - h / 4, y, h / 2, h / 8);
    }

    /** Müllablagerung: Müllsäcke und Abfallbehälter am Straßenrand. */
    private static void muell(Graphics2D g) {
        sky(g, new Color(150, 185, 215), new Color(228, 232, 236), 190);
        g.setColor(new Color(120, 155, 105));
        g.fillRect(0, 190, W, 90); // Grünstreifen
        g.setColor(new Color(95, 128, 84));
        g.fillRect(0, 190, W, 5);
        g.setColor(new Color(88, 90, 94));
        g.fillRect(0, 280, W, 200); // Straße
        // Müllsäcke (dunkle runde Formen)
        mullsack(g, 120, 235, 70);
        mullsack(g, 205, 250, 55);
        mullsack(g, 280, 240, 62);
        // Abfallbehälter (grün)
        g.setColor(new Color(70, 115, 75));
        g.fillRoundRect(420, 215, 60, 85, 10, 10);
        g.setColor(new Color(55, 95, 60));
        g.fillRect(420, 215, 60, 10);
        g.setColor(new Color(95, 140, 100));
        g.fillRect(425, 260, 50, 8);
        // Verstreutes Papier
        g.setColor(new Color(235, 230, 220));
        g.fillPolygon(new int[]{360, 390, 385, 355}, new int[]{300, 305, 320, 315}, 4);
        g.fillPolygon(new int[]{470, 500, 495, 465}, new int[]{330, 335, 350, 345}, 4);
        // Bordsteinkante
        g.setColor(new Color(70, 72, 76));
        g.fillRect(0, 278, W, 4);
    }

    private static void mullsack(Graphics2D g, int x, int y, int w) {
        g.setColor(new Color(55, 60, 66));
        g.fillOval(x, y, w, (int) (w * 0.75));
        g.setColor(new Color(70, 75, 82));
        g.fillOval(x + 4, y + 3, w - 8, (int) (w * 0.6));
        g.setColor(new Color(45, 48, 53));
        g.fillOval(x + w / 4, y - 6, w / 2, 12); // Knoten
    }

    /** Defekte Straßenbeleuchtung: Abendhimmel, Laterne ohne Licht, Straße. */
    private static void strassenbeleuchtung(Graphics2D g) {
        sky(g, new Color(38, 48, 78), new Color(210, 140, 90), 300);
        // Horizont-Silhouette
        g.setColor(new Color(60, 60, 72));
        g.fillRect(0, 290, W, 10);
        g.setColor(new Color(50, 52, 62));
        g.fillRect(0, 340, W, 140); // Straße
        g.setColor(new Color(120, 120, 130));
        g.fillRect(0, 338, W, 3); // Bordstein
        // Laternenmast
        g.setColor(new Color(70, 74, 82));
        g.fillRect(190, 140, 8, 210);
        g.fillRect(190, 140, 45, 7); // Ausleger
        g.fillRect(228, 140, 5, 24); // Lampenkopf-Halterung
        // Lampenkopf (aus)
        g.setColor(new Color(105, 110, 118));
        g.fillRoundRect(222, 128, 26, 14, 4, 4);
        g.setColor(new Color(90, 94, 102));
        g.fillRect(224, 136, 22, 4);
        // Sockel
        g.setColor(new Color(90, 94, 102));
        g.fillRoundRect(180, 338, 28, 14, 4, 4);
        // Zweite Laterne (ebenfalls aus, klein im Hintergrund)
        g.setColor(new Color(65, 68, 76));
        g.fillRect(500, 190, 6, 150);
        g.fillRect(500, 190, 30, 5);
        // Sterne
        g.setColor(new Color(230, 230, 240));
        g.fillRect(80, 60, 2, 2);
        g.fillRect(420, 40, 2, 2);
        g.fillRect(300, 90, 2, 2);
        g.fillRect(560, 80, 2, 2);
    }

    /** Beschädigtes Verkehrsschild: gebogener Pfosten, Schild mit rotem Ring. */
    private static void verkehrsschild(Graphics2D g) {
        sky(g, new Color(150, 185, 220), new Color(228, 232, 236), 210);
        g.setColor(new Color(200, 195, 185));
        g.fillRect(0, 210, W, 60); // Gehweg
        g.setColor(new Color(88, 90, 94));
        g.fillRect(0, 270, W, 210); // Straße
        g.setColor(new Color(70, 72, 76));
        g.fillRect(0, 268, W, 4);
        // Pfosten (leicht gebogen = beschädigt)
        g.setColor(new Color(120, 124, 130));
        g.setStroke(new BasicStroke(9));
        g.drawLine(320, 120, 330, 300);
        g.drawLine(330, 300, 332, 360);
        g.setStroke(new BasicStroke(1));
        // Schild: weißer Kreis mit rotem Ring
        g.setColor(new Color(255, 255, 255));
        g.fillOval(288, 60, 110, 110);
        g.setColor(new Color(205, 50, 45));
        g.setStroke(new BasicStroke(14));
        g.drawOval(288, 60, 110, 110);
        g.setStroke(new BasicStroke(1));
        // Innen-Symbol (Piktogramm 50)
        g.setColor(new Color(30, 30, 34));
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString("50", 318, 130);
        // Delle im Schild
        g.setColor(new Color(255, 255, 255));
        g.fillOval(360, 95, 18, 10);
        // Baum im Hintergrund
        g.setColor(new Color(95, 130, 85));
        g.fillOval(520, 140, 90, 90);
        g.setColor(new Color(110, 145, 95));
        g.fillOval(500, 165, 60, 60);
        g.setColor(new Color(80, 90, 75));
        g.fillRect(545, 225, 16, 60);
    }

    /** Falschparken: Auto auf Gehweg/Bordstein quer zur Fahrbahn. */
    private static void falschparken(Graphics2D g) {
        sky(g, new Color(145, 180, 218), new Color(226, 232, 238), 200);
        // Gebäudefassade hinten
        g.setColor(new Color(205, 185, 160));
        g.fillRect(0, 120, W, 130);
        g.setColor(new Color(190, 170, 145));
        g.fillRect(0, 120, W, 12);
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 5; c++) {
                g.setColor(new Color(150, 160, 175));
                g.fillRect(40 + c * 120, 145 + r * 60, 70, 40);
                g.setColor(new Color(110, 120, 135));
                g.fillRect(52 + c * 120, 157 + r * 60, 46, 28);
            }
        }
        // Gehweg (breit)
        g.setColor(new Color(222, 218, 210));
        g.fillRect(0, 250, W, 90);
        g.setColor(new Color(200, 196, 188));
        g.fillRect(0, 250, W, 6);
        // Bordstein
        g.setColor(new Color(160, 158, 152));
        g.fillRect(0, 338, W, 10);
        // Fahrbahn
        g.setColor(new Color(92, 94, 98));
        g.fillRect(0, 348, W, 132);
        g.setColor(new Color(220, 220, 210));
        g.fillRect(0, 425, W, 4); // Fahrbahnmarkierung
        // Auto quer über Gehweg + Bordstein
        auto(g, 150, 270, 330, 150, new Color(65, 110, 165), false);
    }

    private static void auto(Graphics2D g, int x, int y, int w, int h,
                             Color body, boolean abgenutzt) {
        // Schatten
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(x - 8, y + h - 12, w + 16, 20);
        // Karosserie
        g.setColor(abgenutzt ? new Color(120, 128, 132) : body);
        g.fillRoundRect(x, y, w, h, 26, 26);
        g.setColor(abgenutzt ? new Color(140, 148, 152) : body.brighter());
        g.fillRoundRect(x + 6, y + 6, w - 12, h - 12, 20, 20);
        // Dach
        g.setColor(abgenutzt ? new Color(110, 118, 122) : body.darker());
        g.fillRoundRect(x + w / 5, y - 26, w * 3 / 5, 40, 16, 16);
        // Fenster
        g.setColor(new Color(160, 190, 215));
        g.fillRoundRect(x + w / 5 + 8, y - 14, w / 6, 24, 6, 6);
        g.fillRoundRect(x + w * 7 / 12 + 2, y - 14, w / 6, 24, 6, 6);
        // Räder
        for (int i = 0; i < 2; i++) {
            g.setColor(new Color(35, 35, 38));
            g.fillRoundRect(x + 14 + i * (w - 52), y + h - 16, 34, 18, 6, 6);
        }
    }

    /** Verwaistes Fahrzeug: abgenutztes Auto, platter Reifen, Wildwuchs. */
    private static void verwaistesFahrzeug(Graphics2D g) {
        sky(g, new Color(160, 190, 215), new Color(225, 230, 234), 210);
        g.setColor(new Color(120, 150, 100));
        g.fillRect(0, 210, W, 130); // Wiese
        g.setColor(new Color(95, 128, 84));
        g.fillRect(0, 210, W, 5);
        g.setColor(new Color(88, 90, 94));
        g.fillRect(0, 340, W, 140); // Straße
        // Auto, abgenutzt
        auto(g, 170, 250, 300, 130, new Color(120, 128, 132), true);
        // Platter Reifen vorne
        g.setColor(new Color(25, 25, 28));
        g.fillOval(180, 362, 40, 16);
        // Rissige Scheibe
        g.setColor(new Color(190, 195, 200));
        g.setStroke(new BasicStroke(1));
        g.drawLine(215, 240, 235, 258);
        g.drawLine(215, 240, 245, 232);
        // Wildwuchs um die Räder
        g.setColor(new Color(70, 105, 60));
        for (int i = 0; i < 8; i++) {
            int gx = 160 + i * 40;
            g.drawLine(gx, 380, gx - 12, 350 + (i % 3) * 10);
            g.drawLine(gx, 380, gx + 14, 345 + (i % 2) * 14);
        }
        // Blätter auf der Motorhaube
        g.setColor(new Color(90, 125, 70));
        g.fillOval(440, 265, 18, 10);
        g.fillOval(455, 280, 14, 9);
    }

    /** Überflutung: Wasser über Fahrbahn und Bordstein, Regen. */
    private static void ueberschwemmung(Graphics2D g) {
        sky(g, new Color(120, 145, 175), new Color(205, 215, 225), 250);
        g.setColor(new Color(110, 105, 95));
        g.fillRect(0, 250, W, 40); // Gehweg (teilweise)
        g.setColor(new Color(70, 72, 76));
        g.fillRect(0, 250, W, 6);
        // Fahrbahn unter Wasser
        g.setColor(new Color(92, 94, 98));
        g.fillRect(0, 290, W, 190);
        g.setColor(new Color(60, 90, 120, 200));
        g.fillRect(0, 300, W, 180); // Wasserfläche
        g.setColor(new Color(75, 110, 145));
        g.fillRect(0, 300, W, 8);
        // Wellenlinien
        g.setColor(new Color(110, 150, 185));
        g.setStroke(new BasicStroke(2));
        for (int i = 0; i < 5; i++) {
            int wy = 330 + i * 30;
            g.drawArc(60 + i * 90, wy, 60, 14, 0, 180);
            g.drawArc(380 - i * 60, wy + 10, 50, 12, 180, 180);
        }
        // Untergegangener Bordstein
        g.setColor(new Color(120, 118, 112));
        g.fillRect(0, 295, W, 8);
        // Regentropfen
        g.setColor(new Color(220, 230, 240, 160));
        for (int i = 0; i < 24; i++) {
            int rx = 20 + i * 26, ry = 30 + (i * 37) % 250;
            g.drawLine(rx, ry, rx - 4, ry + 14);
        }
    }

    /** Generische Außendienstaufnahme: Straßenzug mit Gebäuden. */
    private static void generic(Graphics2D g) {
        sky(g, new Color(150, 185, 220), new Color(228, 232, 236), 200);
        // Häuserzeile
        for (int i = 0; i < 4; i++) {
            int hx = 20 + i * 155;
            g.setColor(new Color(200 + i * 6, 185, 160));
            g.fillRect(hx, 120, 140, 140);
            g.setColor(new Color(150, 160, 175));
            g.fillRect(hx + 20, 145, 45, 55);
            g.setColor(new Color(110, 120, 135));
            g.fillRect(hx + 28, 155, 30, 35);
            g.setColor(new Color(120, 100, 70));
            g.fillRect(hx + 95, 150, 25, 45);
            g.setColor(new Color(170, 150, 130));
            g.fillRect(hx, 200, 140, 5);
        }
        // Gehweg + Straße
        g.setColor(new Color(222, 218, 210));
        g.fillRect(0, 260, W, 60);
        g.setColor(new Color(160, 158, 152));
        g.fillRect(0, 320, W, 8);
        g.setColor(new Color(92, 94, 98));
        g.fillRect(0, 328, W, 152);
        g.setColor(new Color(220, 220, 210));
        g.fillRect(0, 405, W, 4);
        // Baum
        g.setColor(new Color(95, 130, 85));
        g.fillOval(540, 160, 80, 80);
        g.setColor(new Color(80, 90, 75));
        g.fillRect(570, 235, 14, 40);
    }

    // ── EXIF ───────────────────────────────────────────────────────────────

    /**
     * Fügt direkt nach dem SOI ein EXIF-APP1-Segment mit GPS und
     * DateTimeOriginal ein (IFD0 → GPSInfo; die Pixel-/Scan-Daten bleiben
     * unverändert).
     */
    static byte[] insertExif(byte[] jpeg, double lat, double lon, LocalDateTime capturedAt) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // TIFF-Layout: IFD0 (30 B) → GPSIFD (66 B) → 6 Rationals (48 B) → ExifSubIFD (18 B) → ASCII
        int ifd0Count = 2;
        int gpsIfdStart = 8 + 2 + ifd0Count * 12 + 4;
        int rationalsStart = gpsIfdStart + 2 + 5 * 12 + 4;
        int rationalsStart2 = rationalsStart + 24;
        int exifSubIfdStart = rationalsStart2 + 24;

        tiff.write('I'); tiff.write('I');
        writeLeShort(tiff, 42);
        writeLeInt(tiff, 8);
        // IFD0: count=2
        writeLeShort(tiff, ifd0Count);
        // ExifIFD pointer (0x8769, LONG, 1 → exifSubIfdStart)
        writeLeShort(tiff, 0x8769); writeLeShort(tiff, 4); writeLeInt(tiff, 1);
        writeLeInt(tiff, exifSubIfdStart);
        // GPSInfo pointer (0x8825, LONG, 1 → gpsIfdStart)
        writeLeShort(tiff, 0x8825); writeLeShort(tiff, 4); writeLeInt(tiff, 1);
        writeLeInt(tiff, gpsIfdStart);
        writeLeInt(tiff, 0); // next IFD

        // GPSIFD: 5 Einträge (Version, LatRef, Lat, LonRef, Lon) + next
        writeLeShort(tiff, 5);
        writeLeShort(tiff, 0x0000); writeLeShort(tiff, 1); writeLeInt(tiff, 4);
        tiff.write(new byte[]{2, 3, 0, 0});
        writeLeShort(tiff, 0x0001); writeLeShort(tiff, 2); writeLeInt(tiff, 2);
        tiff.write(new byte[]{(byte) (lat >= 0 ? 'N' : 'S'), 0, 0, 0});
        writeLeShort(tiff, 0x0002); writeLeShort(tiff, 5); writeLeInt(tiff, 3);
        writeLeInt(tiff, rationalsStart);
        writeLeShort(tiff, 0x0003); writeLeShort(tiff, 2); writeLeInt(tiff, 2);
        tiff.write(new byte[]{(byte) (lon >= 0 ? 'E' : 'W'), 0, 0, 0});
        writeLeShort(tiff, 0x0004); writeLeShort(tiff, 5); writeLeInt(tiff, 3);
        writeLeInt(tiff, rationalsStart2);
        writeLeInt(tiff, 0);
        writeRational(tiff, Math.abs(lat));
        writeRational(tiff, Math.abs(lon));

        // ExifSubIFD: 1 Eintrag (DateTimeOriginal 0x9003, ASCII) + next
        String date = capturedAt.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")) + "\0";
        byte[] dateBytes = date.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        writeLeShort(tiff, 1);
        writeLeShort(tiff, 0x9003); writeLeShort(tiff, 2); writeLeInt(tiff, dateBytes.length);
        int asciiStart = exifSubIfdStart + 2 + 12 + 4;
        writeLeInt(tiff, asciiStart);
        writeLeInt(tiff, 0);
        tiff.write(dateBytes);

        byte[] tiffBytes = tiff.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + tiffBytes.length + 16);
        out.write(jpeg[0]); out.write(jpeg[1]); // SOI
        int app1Len = 2 + 6 + tiffBytes.length;
        out.write(0xFF); out.write(0xE1);
        out.write((app1Len >> 8) & 0xFF); out.write(app1Len & 0xFF);
        out.write("Exif\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(tiffBytes, 0, tiffBytes.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static void writeRational(ByteArrayOutputStream out, double value) throws IOException {
        int deg = (int) Math.floor(value);
        double minFloat = (value - deg) * 60.0;
        int min = (int) Math.floor(minFloat);
        int sec = (int) Math.round((minFloat - min) * 60.0);
        writeLeInt(out, deg); writeLeInt(out, 1);
        writeLeInt(out, min); writeLeInt(out, 1);
        writeLeInt(out, sec); writeLeInt(out, 1);
    }

    private static void writeLeShort(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
    }

    private static void writeLeInt(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
    }
}
