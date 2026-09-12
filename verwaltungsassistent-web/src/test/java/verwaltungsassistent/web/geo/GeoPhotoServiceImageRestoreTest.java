package verwaltungsassistent.web.geo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression: Ein Demo-Foto-Datensatz, dessen lokale Anzeige-Kopie fehlt
 * (z. B. geleertes Laufzeit-Upload-Verzeichnis), muss beim Bildabruf aus dem
 * gebündelten Demo-Asset wiederhergestellt werden — deterministisch, ohne
 * externe Quellen.
 */
class GeoPhotoServiceImageRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void missingDisplayCopy_isRestoredFromBundledDemoAsset() throws Exception {
        GeoPhotoRepository repo = mock(GeoPhotoRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GeoPhotoService service = new GeoPhotoService(repo, tempDir.toString());

        String demoFile = "strassenbeleuchtung_brb_01.jpg";
        GeoPhotoEntity photo = new GeoPhotoEntity(UUID.randomUUID(), demoFile,
                "photos/verlorene-kopie.jpg", "image/jpeg", "demo02@verwaltungsassistent.local", java.time.Instant.now());
        // GPS-Metadaten bleiben im Datensatz (werden vom Bildabruf nicht benötigt).
        photo.setHasGeo(true);
        photo.setLatitude(52.4075);
        photo.setLongitude(13.0600);

        Path restored = service.ensureLocalImage(photo);

        assertNotNull(restored);
        assertTrue(Files.isRegularFile(restored), "Wiederhergestellte Anzeige-Kopie muss existieren: " + restored);
        assertTrue(restored.toString().contains("_display."), "storagePath muss auf die Anzeige-Kopie zeigen");
        assertTrue(photo.getStoragePath().endsWith(restored.getFileName().toString()),
                "Datensatz muss auf die wiederhergestellte Kopie aktualisiert sein");
        byte[] head = Files.readAllBytes(restored);
        assertTrue(head.length > 100 && (head[0] & 0xFF) == 0xFF && head[1] == (byte) 0xD8,
                "Wiederhergestellte Datei muss ein JPEG sein");
    }

    @Test
    void existingFile_isReturnedUnchanged() throws Exception {
        GeoPhotoRepository repo = mock(GeoPhotoRepository.class);
        GeoPhotoService service = new GeoPhotoService(repo, tempDir.toString());
        Path existing = tempDir.resolve("photos").resolve("vorhanden.jpg");
        Files.createDirectories(existing.getParent());
        Files.write(existing, new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});

        GeoPhotoEntity photo = new GeoPhotoEntity(UUID.randomUUID(), "vorhanden.jpg",
                "photos/vorhanden.jpg", "image/jpeg", "demo02@verwaltungsassistent.local", java.time.Instant.now());

        assertTrue(service.ensureLocalImage(photo).equals(existing));
        assertTrue(Optional.of(photo.getStoragePath()).orElse("").endsWith("vorhanden.jpg"),
                "Vorhandene Datei darf den Datensatz nicht verändern");
    }
}
