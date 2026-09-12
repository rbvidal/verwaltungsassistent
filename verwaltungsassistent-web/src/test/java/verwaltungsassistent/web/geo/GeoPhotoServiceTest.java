package verwaltungsassistent.web.geo;

import verwaltungsassistent.web.geo.GeoPhotoService.ExtractedGps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the EXIF extraction: a hand-crafted TIFF with GPS EXIF
 * metadata (52.5420/13.4100) and a TIFF without GPS.
 * The extraction reads only the image itself — nothing is invented.
 */
class GeoPhotoServiceTest {

    @TempDir
    Path tempDir;

    private final GeoPhotoRepository repo = mock(GeoPhotoRepository.class);

    private GeoPhotoService newService() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new GeoPhotoService(repo, tempDir.toString());
    }

    @Test
    void store_thenExtract_geotaggedImage_returnsGpsFromExif() throws Exception {
        GeoPhotoService service = newService();
        byte[] tiff = gpsTiffWithExif();
        MockMultipartFile file = new MockMultipartFile(
                "file", "kastanienallee.tiff", "image/tiff", tiff);

        GeoPhotoEntity stored = service.store(file, "user@example.com");
        assertTrue(Files.exists(tempDir.resolve(stored.getStoragePath())));
        assertEquals("user@example.com", stored.getUploadedBy());

        ExtractedGps gps = service.extract(stored.getStoragePath());
        assertTrue(gps.hasGps());
        assertEquals(52.5420, gps.latitude(), 0.0001);
        assertEquals(13.4100, gps.longitude(), 0.0001);
        assertNotNull(gps.capturedAt());
        assertTrue(gps.capturedAt().toString().startsWith("2026-08-24"));
        assertNotNull(gps.orientation());

        GeoPhotoEntity applied = service.applyMetadata(stored, gps);
        assertTrue(applied.isHasGeo());
        assertEquals(52.5420, applied.getLatitude(), 0.0001);
        assertEquals(13.4100, applied.getLongitude(), 0.0001);
    }

    @Test
    void store_thenExtract_imageWithoutGps_reportsHonestly() throws Exception {
        GeoPhotoService service = newService();
        MockMultipartFile file = new MockMultipartFile(
                "file", "ohne-gps.tiff", "image/tiff", tiffWithoutGps());

        GeoPhotoEntity stored = service.store(file, "user@example.com");
        ExtractedGps gps = service.extract(stored.getStoragePath());

        assertFalse(gps.hasGps());
        assertNull(gps.latitude());
        assertNull(gps.longitude());
        assertNull(gps.capturedAt());

        GeoPhotoEntity applied = service.applyMetadata(stored, gps);
        assertFalse(applied.isHasGeo());
        assertNull(applied.getLatitude());
        assertNull(applied.getLongitude());
    }

    // ── TIFF fixtures (little-endian, hand-crafted) ────────────────────────

    /**
     * TIFF with GPS IFD (Latitude 52°32′31.2″N, Longitude 13°24′36″E →
     * 52.5420/13.4100), DateTimeOriginal 2026:08:24 14:37:00 and Orientation 1.
     */
    private byte[] gpsTiffWithExif() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write("II".getBytes(StandardCharsets.US_ASCII));
        writeLeShort(d, 42);
        writeLeInt(d, 8);                      // IFD0 offset

        // IFD0: 3 entries
        writeLeShort(d, 3);
        writeIfdEntry(d, 0x8769, 4, 1, 50);    // ExifIFD pointer
        writeIfdEntry(d, 0x8825, 4, 1, 68);    // GPSInfo pointer
        writeIfdEntry(d, 0x0112, 3, 1, 1);     // Orientation = 1 (inline)
        writeLeInt(d, 0);                      // next IFD

        // Exif IFD: 1 entry — DateTimeOriginal at offset 170
        writeLeShort(d, 1);
        writeIfdEntry(d, 0x9003, 2, 20, 170);
        writeLeInt(d, 0);

        // GPS IFD: 4 entries
        writeLeShort(d, 4);
        writeIfdEntry(d, 0x0001, 2, 2, 0x4E);  // GPSLatitudeRef "N" (inline)
        writeIfdEntry(d, 0x0002, 5, 3, 122);   // GPSLatitude → rationals at 122
        writeIfdEntry(d, 0x0003, 2, 2, 0x45);  // GPSLongitudeRef "E" (inline)
        writeIfdEntry(d, 0x0004, 5, 3, 146);   // GPSLongitude → rationals at 146
        writeLeInt(d, 0);

        // Latitude rationals: 52/1, 32/1, 312/10
        writeRational(d, 52, 1);
        writeRational(d, 32, 1);
        writeRational(d, 312, 10);
        // Longitude rationals: 13/1, 24/1, 36/1
        writeRational(d, 13, 1);
        writeRational(d, 24, 1);
        writeRational(d, 36, 1);
        // DateTimeOriginal string
        d.write("2026:08:24 14:37:00".getBytes(StandardCharsets.US_ASCII));
        d.writeByte(0);
        return out.toByteArray();
    }

    /** Minimal TIFF with an empty IFD0 — no EXIF, no GPS. */
    private byte[] tiffWithoutGps() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write("II".getBytes(StandardCharsets.US_ASCII));
        writeLeShort(d, 42);
        writeLeInt(d, 8);
        writeLeShort(d, 0);                    // 0 entries
        writeLeInt(d, 0);                      // next IFD
        return out.toByteArray();
    }

    private static void writeIfdEntry(DataOutputStream d, int tag, int type,
                                      int count, long value) throws IOException {
        writeLeShort(d, tag);
        writeLeShort(d, type);
        writeLeInt(d, count);
        writeLeInt(d, value);
    }

    private static void writeRational(DataOutputStream d, int numerator,
                                      int denominator) throws IOException {
        writeLeInt(d, numerator);
        writeLeInt(d, denominator);
    }

    private static void writeLeShort(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
    }

    private static void writeLeInt(DataOutputStream d, long v) throws IOException {
        d.writeByte((int) (v & 0xFF));
        d.writeByte((int) ((v >> 8) & 0xFF));
        d.writeByte((int) ((v >> 16) & 0xFF));
        d.writeByte((int) ((v >> 24) & 0xFF));
    }
}
