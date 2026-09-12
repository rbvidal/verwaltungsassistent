package verwaltungsassistent.web.geo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import verwaltungsassistent.web.geo.GeoPhotoService.ExtractedGps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the synthetic Brandenburg demo imagery: deterministic
 * generation, embedded EXIF-GPS readable by the app's extraction, and the
 * privacy chain (sanitized display copy without EXIF).
 */
class SyntheticPhotoFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedImage_carriesExifGps_readableByGeoWorkflow() throws Exception {
        Path target = tempDir.resolve("baustelle_potsdam_01.jpg");
        SyntheticPhotoFactory.generate(target, "Baustelle – Einrichtung beschädigt",
                "Potsdam · Baustelle / Bauaufsicht",
                52.3881, 13.0658, LocalDateTime.of(2026, 8, 20, 9, 0));

        // the app's EXIF extraction reads the coordinates and capture time
        GeoPhotoService service = new GeoPhotoService(mock(GeoPhotoRepository.class), tempDir.toString());
        ExtractedGps gps = service.extract("baustelle_potsdam_01.jpg");
        assertTrue(gps.hasGps(), "synthetic image must carry GPS");
        assertEquals(52.3881, gps.latitude(), 1e-4);
        assertEquals(13.0658, gps.longitude(), 1e-4);
        assertNotNull(gps.capturedAt(), "capture time must be embedded");
    }

    @Test
    void sanitizedCopyOfSyntheticImage_hasNoExif() throws Exception {
        Path target = tempDir.resolve("muell_potsdam_03.jpg");
        SyntheticPhotoFactory.generate(target, "Müllablagerung", "Potsdam · Müllablagerung",
                52.3869, 13.0649, LocalDateTime.of(2026, 8, 20, 9, 34));

        GeoPhotoService service = new GeoPhotoService(mock(GeoPhotoRepository.class), tempDir.toString());
        String display = service.createSanitizedCopy(target, "image/jpeg");
        Metadata m = ImageMetadataReader.readMetadata(service.resolve(display).toFile());
        long exifDirs = 0;
        for (var d : m.getDirectories()) {
            if (d.getName().contains("Exif")) exifDirs++;
        }
        assertEquals(0, exifDirs, "display copy must carry no EXIF");
        assertTrue(service.resolve(display).toFile().length() > 0, "display copy exists");
    }

    @Test
    void generationIsDeterministic() throws Exception {
        Path a = tempDir.resolve("a.jpg");
        Path b = tempDir.resolve("b.jpg");
        SyntheticPhotoFactory.generate(a, "Straßenschaden", "Potsdam · Straßenschaden",
                52.3884, 13.0648, LocalDateTime.of(2026, 8, 20, 9, 17));
        SyntheticPhotoFactory.generate(b, "Straßenschaden", "Potsdam · Straßenschaden",
                52.3884, 13.0648, LocalDateTime.of(2026, 8, 20, 9, 17));
        assertTrue(java.util.Arrays.equals(
                java.nio.file.Files.readAllBytes(a), java.nio.file.Files.readAllBytes(b)),
                "same input must produce identical bytes");
    }
}
