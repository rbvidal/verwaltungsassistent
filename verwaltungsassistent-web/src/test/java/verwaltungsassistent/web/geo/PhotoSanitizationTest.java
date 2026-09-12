package verwaltungsassistent.web.geo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import verwaltungsassistent.web.util.ExifJpegHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the demo-photo privacy hardening: the display copy served
 * by the viewer/download carries no EXIF (no camera make/model, no firmware,
 * no GPS, no capture time), while the source file keeps its metadata and the
 * pixel data survives byte-for-byte.
 */
class PhotoSanitizationTest {

    @TempDir
    Path tempDir;

    @Test
    void jpegDisplayCopy_hasNoMetadata_butIdenticalPixels() throws Exception {
        Path original = tempDir.resolve("foto.jpg");
        ExifJpegHelper.writeGpsJpeg(original, 52.5, 13.4, "N", "E");
        byte[] source = Files.readAllBytes(original);

        byte[] sanitized = GeoPhotoService.stripJpegMetadata(source);

        // metadata gone: no EXIF/GPS directories anymore
        Metadata m = ImageMetadataReader.readMetadata(new java.io.ByteArrayInputStream(sanitized));
        assertNull(m.getFirstDirectoryOfType(GpsDirectory.class), "no GPS in the display copy");
        long exifDirs = 0;
        for (var d : m.getDirectories()) {
            if (d.getName().contains("Exif")) exifDirs++;
        }
        assertEquals(0, exifDirs, "no Exif directories in the display copy");

        // pixel/scan data preserved: SOI..SOS prefix differs only by dropped segments;
        // the scan data (from SOS on) must be identical
        int sosSource = indexOfSos(source);
        int sosClean = indexOfSos(sanitized);
        assertTrue(sosSource > 0 && sosClean > 0, "both files contain an SOS segment");
        byte[] scanSource = java.util.Arrays.copyOfRange(source, sosSource, source.length);
        byte[] scanClean = java.util.Arrays.copyOfRange(sanitized, sosClean, sanitized.length);
        assertTrue(java.util.Arrays.equals(scanSource, scanClean), "scan/pixel data identical");
    }

    private static int indexOfSos(byte[] b) {
        for (int i = 2; i + 1 < b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xDA) return i;
        }
        return -1;
    }

    @Test
    void pngDisplayCopy_keepsColorChunks_dropsTextChunks() throws Exception {
        byte[] png = buildPng(
                chunk("IHDR", new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0}),
                chunk("tEXt", "Comment".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                chunk("IDAT", new byte[]{1}),
                chunk("IEND", new byte[0]));

        byte[] sanitized = GeoPhotoService.stripPngMetadata(png);

        String chunks = new String(sanitized, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(chunks.contains("IHDR") && chunks.contains("IDAT") && chunks.contains("IEND"));
        assertTrue(!chunks.contains("tEXt"), "text chunks removed");
        // output still starts with the PNG signature
        assertTrue((sanitized[0] & 0xFF) == 0x89 && sanitized[1] == 'P' && sanitized[2] == 'N' && sanitized[3] == 'G');
    }

    private static byte[] buildPng(byte[]... chunks) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        for (byte[] c : chunks) out.write(c, 0, c.length);
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int len = data.length;
        out.write((len >> 24) & 0xFF); out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
        out.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 4);
        out.write(data, 0, data.length);
        out.write(new byte[]{0, 0, 0, 0}); // CRC (not validated by the stripper)
        return out.toByteArray();
    }

    @Test
    void originalSourceFile_keepsItsMetadata() throws Exception {
        Path original = tempDir.resolve("foto.jpg");
        ExifJpegHelper.writeGpsJpeg(original, 52.5, 13.4, "N", "E");
        Metadata m = ImageMetadataReader.readMetadata(original.toFile());
        assertNotNull(m.getFirstDirectoryOfType(GpsDirectory.class),
                "the source file keeps its EXIF/GPS metadata");
    }
}
