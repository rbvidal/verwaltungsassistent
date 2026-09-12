package verwaltungsassistent.web.geo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

/**
 * Stores uploaded field photos locally and extracts GPS/date/orientation from
 * the image EXIF metadata (metadata-extractor, Apache-2.0). No external
 * service is contacted; images without usable GPS metadata are reported
 * honestly.
 */
@Service
public class GeoPhotoService {

    private static final Logger log = LoggerFactory.getLogger(GeoPhotoService.class);

    private final GeoPhotoRepository photoRepository;
    private final Path uploadDir;

    public GeoPhotoService(GeoPhotoRepository photoRepository,
                           @Value("${app.upload-dir:uploads}") String uploadDirPath) {
        this.photoRepository = photoRepository;
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir.resolve("photos"));
        } catch (IOException e) {
            throw new RuntimeException("Cannot create photo upload directory", e);
        }
    }

    /** Extracted image metadata (all values from the image itself, never invented). */
    public record ExtractedGps(Double latitude, Double longitude, Instant capturedAt,
                               String orientation, boolean hasGps) {}

    /** Stores the uploaded photo locally (uploads/photos/) and persists the record (no EXIF read yet). */
    public GeoPhotoEntity store(MultipartFile file, String actorEmail) {
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "foto";
        String fileName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storageKey = UUID.randomUUID() + "_" + fileName;
        Path target = uploadDir.resolve("photos").resolve(storageKey);
        try {
            Files.write(target, file.getBytes());
        } catch (IOException e) {
            throw new RuntimeException("Foto konnte nicht gespeichert werden: " + e.getMessage());
        }
        GeoPhotoEntity entity = new GeoPhotoEntity(
                UUID.randomUUID(), original, "photos/" + storageKey,
                file.getContentType() != null ? file.getContentType() : "image/jpeg",
                actorEmail, Instant.now());
        return photoRepository.save(entity);
    }

    /** Reads the EXIF metadata from the stored image file. */
    public ExtractedGps extract(String storagePath) {
        Path file = uploadDir.resolve(storagePath);
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            Double lat = null;
            Double lon = null;
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null && gps.getGeoLocation() != null) {
                lat = gps.getGeoLocation().getLatitude();
                lon = gps.getGeoLocation().getLongitude();
            }
            Instant capturedAt = null;
            ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exif != null) {
                Date date = exif.getDateOriginal();
                if (date != null) {
                    capturedAt = date.toInstant();
                }
            }
            String orientation = null;
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = ifd0.getDescription(ExifIFD0Directory.TAG_ORIENTATION);
            }
            return new ExtractedGps(lat, lon, capturedAt, orientation, lat != null && lon != null);
        } catch (Exception e) {
            log.debug("EXIF extraction failed for {}: {}", storagePath, e.getMessage());
            return new ExtractedGps(null, null, null, null, false);
        }
    }

    /** Applies the extracted metadata to a photo record. */
    public GeoPhotoEntity applyMetadata(GeoPhotoEntity photo, ExtractedGps gps) {
        photo.setLatitude(gps.latitude());
        photo.setLongitude(gps.longitude());
        photo.setCapturedAt(gps.capturedAt());
        photo.setOrientation(gps.orientation());
        photo.setHasGeo(gps.hasGps());
        return photoRepository.save(photo);
    }

    public Path resolve(String storagePath) {
        return uploadDir.resolve(storagePath).normalize();
    }

    /**
     * Stellt sicher, dass die lokale Anzeige-/Download-Kopie eines Fotos
     * existiert. Fehlt die Datei (z. B. geleertes Laufzeit-Upload-Verzeichnis
     * nach einem früheren Lauf), wird sie deterministisch aus dem gebündelten
     * Demo-Asset ({@code demo/geo-photos/<originalName>}) wiederhergestellt:
     * Original-Asset in {@code photos/} schreiben, EXIF-bereinigte
     * Anzeige-Kopie erzeugen und den Datensatz aktualisieren. EXIF/GPS bleibt
     * unverändert im Datensatz; keine externen Quellen.
     *
     * @return der Pfad zur vorhandenen (bzw. wiederhergestellten) Datei, oder
     *         der unveränderte Zielpfad, wenn nichts wiederherstellbar ist.
     */
    public Path ensureLocalImage(GeoPhotoEntity photo) {
        Path file = resolve(photo.getStoragePath());
        if (Files.isRegularFile(file)) {
            return file;
        }
        String original = photo.getOriginalName();
        if (original == null || original.isBlank()) {
            return file;
        }
        try {
            ClassPathResource asset = new ClassPathResource("demo/geo-photos/" + original);
            if (!asset.exists()) {
                log.warn("Demo-Foto-Asset fehlt für '{}' — keine Wiederherstellung möglich.", original);
                return file;
            }
            Files.createDirectories(uploadDir.resolve("photos"));
            Path raw = uploadDir.resolve("photos").resolve(original);
            try (var in = asset.getInputStream()) {
                Files.write(raw, in.readAllBytes());
            }
            String contentType = photo.getContentType() != null ? photo.getContentType() : "image/jpeg";
            String copy = createSanitizedCopy(raw, contentType);
            photo.setStoragePath(copy);
            photoRepository.save(photo);
            log.info("Demo-Foto '{}' aus gebündeltem Asset wiederhergestellt (Anzeige-Kopie {}).",
                    original, copy);
            return resolve(copy);
        } catch (IOException e) {
            log.warn("Demo-Foto '{}' konnte nicht wiederhergestellt werden: {}", original, e.getMessage());
            return file;
        }
    }

    /**
     * Creates an EXIF-stripped display copy of the given source file and
     * returns its relative storage path. Pixel data is preserved byte-for-byte
     * (JPEG: all APPn/COM metadata segments removed, scan data untouched;
     * PNG: only color-critical chunks kept). The source file is never
     * modified. The Geo workflow metadata (GPS, Aufnahmezeitpunkt, Ausrichtung)
     * is extracted from the source BEFORE sanitization and lives in the
     * database record, not in the display copy.
     */
    public String createSanitizedCopy(Path source, String contentType) throws IOException {
        Files.createDirectories(uploadDir.resolve("photos"));
        String ext = contentType != null && contentType.toLowerCase().contains("png") ? "png" : "jpg";
        String target = "photos/" + UUID.randomUUID() + "_display." + ext;
        Path out = uploadDir.resolve(target);
        byte[] bytes = Files.readAllBytes(source);
        Files.write(out, isPng(bytes) ? stripPngMetadata(bytes) : stripJpegMetadata(bytes));
        return target;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /**
     * JPEG metadata stripping: keeps SOI, all quantization/Huffman/SOF/scan
     * data verbatim; drops APPn (EXIF, XMP, ICC-Profile) and COM segments.
     * From the first SOS marker everything is copied verbatim to the end
     * (entropy-coded data may only contain stuffed 0xFF00 or the final EOI),
     * so the pixel data is preserved exactly.
     */
    public static byte[] stripJpegMetadata(byte[] in) {
        if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != 0xD8) {
            return in;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length);
        out.write(in[0]);
        out.write(in[1]);
        int i = 2;
        while (i + 4 <= in.length) {
            if ((in[i] & 0xFF) != 0xFF) {
                // padding bytes before a marker — keep
                out.write(in[i]);
                i++;
                continue;
            }
            int marker = in[i + 1] & 0xFF;
            if (marker == 0xD9) { // EOI
                out.write(in[i]);
                out.write(in[i + 1]);
                i += 2;
                continue;
            }
            if (marker == 0xD8) { // repeated SOI — keep
                out.write(in[i]);
                out.write(in[i + 1]);
                i += 2;
                continue;
            }
            if (marker == 0xDA) { // SOS — scan data follows verbatim
                int len = ((in[i + 2] & 0xFF) << 8) | (in[i + 3] & 0xFF);
                if (len < 2) return in;
                int segEnd = Math.min(in.length, i + 2 + len);
                while (i < segEnd) {
                    out.write(in[i]);
                    i++;
                }
                while (i < in.length) {
                    out.write(in[i]);
                    i++;
                }
                return out.toByteArray();
            }
            int len = ((in[i + 2] & 0xFF) << 8) | (in[i + 3] & 0xFF);
            if (len < 2) return in;
            boolean app = marker >= 0xE0 && marker <= 0xEF;
            boolean comment = marker == 0xFE;
            if (app || comment) {
                i += 2 + len; // drop the segment
            } else {
                int segEnd = Math.min(in.length, i + 2 + len);
                while (i < segEnd) {
                    out.write(in[i]);
                    i++;
                }
            }
        }
        return out.toByteArray();
    }

    /** PNG metadata stripping: keeps only color-critical chunks (IHDR, PLTE, tRNS, gAMA, cHRM, sRGB, iCCP, IDAT, IEND). */
    static byte[] stripPngMetadata(byte[] in) {
        if (!isPng(in)) return in;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length);
        for (int i = 0; i < 8; i++) out.write(in[i]);
        int i = 8;
        java.util.Set<String> keep = java.util.Set.of("IHDR", "PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "IDAT", "IEND");
        while (i + 12 <= in.length) {
            int len = ((in[i] & 0xFF) << 24) | ((in[i + 1] & 0xFF) << 16)
                    | ((in[i + 2] & 0xFF) << 8) | (in[i + 3] & 0xFF);
            String type = new String(in, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkEnd = Math.min(in.length, i + 12 + len);
            if (keep.contains(type) && chunkEnd <= in.length) {
                for (int j = i; j < chunkEnd; j++) out.write(in[j]);
            }
            if ("IEND".equals(type)) break;
            i = chunkEnd;
        }
        return out.toByteArray();
    }

    /** Supported photograph content types (upload + listing are consistent). */
    public static boolean isSupportedContentType(String contentType) {
        return contentType != null && ("image/jpeg".equalsIgnoreCase(contentType)
                || "image/png".equalsIgnoreCase(contentType));
    }

    /**
     * True when the record is a genuine photograph: supported content type
     * AND a matching file signature (JPEG/PNG magic bytes). A PDF that was
     * stored with a wrong label never qualifies as a photo.
     */
    public boolean isSupportedPhoto(GeoPhotoEntity photo) {
        if (photo == null || !isSupportedContentType(photo.getContentType())) {
            return false;
        }
        try {
            Path file = resolve(photo.getStoragePath());
            byte[] header = new byte[4];
            int read = Files.newInputStream(file).read(header);
            if (read < 4) {
                return false;
            }
            boolean jpeg = (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
            boolean png = (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
            return jpeg || png;
        } catch (Exception e) {
            return false;
        }
    }

    public static String formatCapturedAt(Instant instant) {
        if (instant == null) return "—";
        return DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
                .format(instant.atZone(ZoneId.systemDefault()));
    }
}
