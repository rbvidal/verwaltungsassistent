package verwaltungsassistent.web.geo;

import verwaltungsassistent.web.util.ExifJpegHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Focused tests for the photograph qualification rule: only genuine JPEG/PNG
 * image records (content type AND file signature) can appear in the Geotagged
 * Fotos workflow. A PDF stored with an image label never qualifies.
 */
class GeoPhotoFilteringTest {

    private GeoPhotoRepository repo;
    private GeoPhotoService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        repo = mock(GeoPhotoRepository.class);
        service = new GeoPhotoService(repo, tempDir.toString());
    }

    private GeoPhotoEntity record(String name, String storagePath, String contentType) {
        return new GeoPhotoEntity(UUID.randomUUID(), name, storagePath, contentType,
                "admin@verwaltungsassistent.local", Instant.now());
    }

    @Test
    void realJpegWithExifGpsIsExtractedLocally() throws Exception {
        Path jpeg = tempDir.resolve("aufnahme.jpg");
        ExifJpegHelper.writeGpsJpeg(jpeg, 52.5, 13.4, "N", "E");

        GeoPhotoService.ExtractedGps gps = service.extract("aufnahme.jpg");

        assertTrue(gps.hasGps());
        assertEquals(52.5, gps.latitude(), 1e-6);
        assertEquals(13.4, gps.longitude(), 1e-6);
    }

    @Test
    void jpegRecordQualifiesAsPhoto() throws Exception {
        Path jpeg = tempDir.resolve("foto.jpg");
        ExifJpegHelper.writeGpsJpeg(jpeg, 52.5, 13.4, "N", "E");
        GeoPhotoEntity photo = record("foto.jpg", "foto.jpg", "image/jpeg");

        assertTrue(service.isSupportedPhoto(photo));
    }

    @Test
    void pngRecordQualifiesAsPhoto() throws Exception {
        Path png = tempDir.resolve("foto.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
        GeoPhotoEntity photo = record("foto.png", "foto.png", "image/png");

        assertTrue(service.isSupportedPhoto(photo));
    }

    @Test
    void pdfNeverQualifiesAsPhotoEvenWithImageExtension() throws Exception {
        // A PDF renamed to .jpg: the content type says image/jpeg but the file
        // signature is %PDF — the record must be rejected as a photograph.
        Path pdf = tempDir.resolve("some-document.jpg");
        Files.write(pdf, new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', '%', (byte) 0xE2, (byte) 0xE3});
        GeoPhotoEntity photo = record("some-document.jpg", "some-document.jpg", "image/jpeg");

        assertFalse(service.isSupportedPhoto(photo));
    }

    @Test
    void pdfRecordIsRejectedByContentType() throws Exception {
        GeoPhotoEntity photo = record("some-document.pdf", "geo/x.pdf", "application/pdf");
        assertFalse(service.isSupportedPhoto(photo));
        assertFalse(GeoPhotoService.isSupportedContentType("application/pdf"));
        assertTrue(GeoPhotoService.isSupportedContentType("image/jpeg"));
        assertTrue(GeoPhotoService.isSupportedContentType("image/png"));
    }

    @Test
    void unsupportedContentTypesAreRejected() throws Exception {
        Path tiff = tempDir.resolve("scan.tiff");
        Files.write(tiff, new byte[]{0x49, 0x49, 0x2A, 0x00, 1, 2, 3});
        GeoPhotoEntity photo = record("scan.tiff", "scan.tiff", "image/tiff");
        assertFalse(service.isSupportedPhoto(photo));
        assertNull(photo.getLatitude());
    }
}
