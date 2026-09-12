package verwaltungsassistent.web.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a minimal but structurally valid JPEG whose APP1 segment carries an
 * EXIF GPS IFD (little-endian TIFF). Used by the focused tests to verify the
 * local EXIF extraction (metadata-extractor) without external sample files.
 */
public final class ExifJpegHelper {

    private ExifJpegHelper() {
    }

    /** Writes a JPEG with the given GPS position; refs "N"/"S" and "E"/"W". */
    public static void writeGpsJpeg(Path target, double lat, double lon,
                                    String latRef, String lonRef) throws IOException {
        // IFD layout: IFD0 at 8 with one entry (GPSInfo 0x8825 → GPSIFD);
        // IFD0 = 2+12+4 = 18 bytes → GPSIFD at 26; GPSIFD = 2+5*12+4 = 66 bytes → rationals at 92.
        int gpsIfdOffset = 8 + 18;
        int rationalsOffset = gpsIfdOffset + 66;

        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // TIFF header (little endian)
        tiff.write('I'); tiff.write('I');
        writeLeShort(tiff, 42);
        writeLeInt(tiff, 8); // IFD0 offset

        // IFD0 at 8: one entry — GPSInfo pointer (0x8825, LONG, 1) — metadata-extractor
        // 2.19.0 honors the GPS pointer only from the IFD0 directory.
        writeLeShort(tiff, 1);
        writeLeShort(tiff, 0x8825);
        writeLeShort(tiff, 4);
        writeLeInt(tiff, 1);
        writeLeInt(tiff, gpsIfdOffset);
        writeLeInt(tiff, 0); // next IFD

        // GPSIFD: 5 entries (60 bytes) + next-IFD (4 bytes)
        writeLeShort(tiff, 5);
        // GPSVersionID
        writeLeShort(tiff, 0x0000); writeLeShort(tiff, 1); writeLeInt(tiff, 4);
        tiff.write(new byte[]{2, 3, 0, 0});
        // GPSLatitudeRef (ASCII "N\0")
        writeLeShort(tiff, 0x0001); writeLeShort(tiff, 2); writeLeInt(tiff, 2);
        tiff.write(new byte[]{(byte) latRef.charAt(0), 0, 0, 0});
        // GPSLatitude (RATIONAL ×3 → offsets)
        writeLeShort(tiff, 0x0002); writeLeShort(tiff, 5); writeLeInt(tiff, 3);
        writeLeInt(tiff, rationalsOffset);
        // GPSLongitudeRef (ASCII "E\0")
        writeLeShort(tiff, 0x0003); writeLeShort(tiff, 2); writeLeInt(tiff, 2);
        tiff.write(new byte[]{(byte) lonRef.charAt(0), 0, 0, 0});
        // GPSLongitude (RATIONAL ×3)
        writeLeShort(tiff, 0x0004); writeLeShort(tiff, 5); writeLeInt(tiff, 3);
        writeLeInt(tiff, rationalsOffset + 24);
        writeLeInt(tiff, 0);

        writeRational(tiff, lat);
        writeRational(tiff, lon);

        byte[] tiffBytes = tiff.toByteArray();

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(0xFF); jpeg.write(0xD8); // SOI
        // APP1 (Exif)
        jpeg.write(0xFF); jpeg.write(0xE1);
        writeBeShort(jpeg, tiffBytes.length + 2 + 6);
        jpeg.write("Exif".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        jpeg.write(0); jpeg.write(0);
        jpeg.write(tiffBytes);
        // SOF0 (8x8, 3 components): 1+2+2+1+9 = 15 data bytes → length 17
        jpeg.write(0xFF); jpeg.write(0xC0);
        writeBeShort(jpeg, 17);
        jpeg.write(8);                       // precision
        writeBeShort(jpeg, 8);               // height
        writeBeShort(jpeg, 8);               // width
        jpeg.write(3);                       // components
        jpeg.write(1); jpeg.write(0x11); jpeg.write(0);
        jpeg.write(2); jpeg.write(0x11); jpeg.write(0);
        jpeg.write(3); jpeg.write(0x11); jpeg.write(0);
        // SOS: 1+6+3 = 10 data bytes → length 12
        jpeg.write(0xFF); jpeg.write(0xDA);
        writeBeShort(jpeg, 12);
        jpeg.write(3);
        jpeg.write(1); jpeg.write(0); jpeg.write(2); jpeg.write(0); jpeg.write(3); jpeg.write(0);
        jpeg.write(0); jpeg.write(63); jpeg.write(0);
        jpeg.write(0xFF); jpeg.write(0xD9); // EOI
        Files.write(target, jpeg.toByteArray());
    }

    private static void writeRational(ByteArrayOutputStream out, double value) {
        int deg = (int) Math.floor(value);
        double minFloat = (value - deg) * 60.0;
        int min = (int) Math.floor(minFloat);
        int sec = (int) Math.round((minFloat - min) * 60.0);
        // degree
        writeLeInt(out, deg); writeLeInt(out, 1);
        // minutes
        writeLeInt(out, min); writeLeInt(out, 1);
        // seconds
        writeLeInt(out, sec); writeLeInt(out, 1);
    }

    private static void writeLeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
    }

    private static void writeLeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
    }

    private static void writeBeShort(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF); out.write(v & 0xFF);
    }
}
