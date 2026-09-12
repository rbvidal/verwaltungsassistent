package verwaltungsassistent.web.service;

import reasoning.common.model.DocumentFileType;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.model.Document;
import reasoning.search.api.IndexingOrchestrationService;
import verwaltungsassistent.web.geo.GeoPhotoEntity;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import verwaltungsassistent.web.geo.GeoPhotoService.ExtractedGps;
import verwaltungsassistent.web.ingestion.DefaultDocumentIngestionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Admin-Operation „Datensatz neu aufbauen": scannt die lokalen Verzeichnisse
 * und baut den Dokument-/Medien-Datensatz über die bestehende Pipeline neu
 * auf — es entsteht KEINE zweite Indexierungsarchitektur.
 *
 * <ul>
 *   <li>{@code uploads/docs/} – unterstützte Dokumente werden registriert und
 *       indexiert (bestehende Einträge werden neu indexiert);</li>
 *   <li>{@code uploads/photos/} – Bilder durchlaufen die bestehende Foto-
 *       Pipeline (EXIF → strukturierte Metadaten → bereinigte Anzeige-Kopie);</li>
 *   <li>{@code uploads/video/} und {@code uploads/audio/} – werden erkannt,
 *       aber NICHT indexiert (kein Text-/Bild-Inhalt für die bestehende
 *       Pipeline) und klar als solche ausgewiesen;</li>
 *   <li>alle bereits registrierten Dokumente werden neu indexiert
 *       (Index-Neuaufbau über die bestehende Pipeline).</li>
 * </ul>
 *
 * <p>Die Operation löscht niemals Dateien und fasst keine Benutzerdaten an.</p>
 */
@Service
public class DatasetAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(DatasetAdministrationService.class);

    private static final Set<String> DOC_EXTENSIONS = Set.of("pdf", "docx", "txt", "html", "htm");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final Path uploadDir;
    private final DocumentFacade documentFacade;
    private final IndexingOrchestrationService indexingOrchestrationService;
    private final DefaultDocumentIngestionProcessor ingestionProcessor;
    private final GeoPhotoService photoService;
    private final GeoPhotoRepository photoRepository;

    public DatasetAdministrationService(
            @Value("${app.upload-dir:uploads}") String uploadDirPath,
            DocumentFacade documentFacade,
            IndexingOrchestrationService indexingOrchestrationService,
            DefaultDocumentIngestionProcessor ingestionProcessor,
            GeoPhotoService photoService,
            GeoPhotoRepository photoRepository) {
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        this.documentFacade = documentFacade;
        this.indexingOrchestrationService = indexingOrchestrationService;
        this.ingestionProcessor = ingestionProcessor;
        this.photoService = photoService;
        this.photoRepository = photoRepository;
    }

    /** Deterministischer Ergebnisbericht der Datensatz-Operation. */
    public record DatasetReport(
            int docsRegistered,
            int docsReindexed,
            int photosImported,
            int videosSkipped,
            int audioSkipped,
            int unsupportedSkipped,
            int corpusReindexed,
            List<String> unsupportedNames,
            List<String> notes) {}

    public DatasetReport rebuild(Consumer<String> stage) {
        int docsRegistered = 0;
        int docsReindexed = 0;
        int photosImported = 0;
        int videosSkipped = 0;
        int audioSkipped = 0;
        int unsupportedSkipped = 0;
        int corpusReindexed = 0;
        List<String> unsupportedNames = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // 2D.16: Dokumente, die in DIESEM Lauf bereits registriert/neu
        // indexiert wurden (Schritte 1–3), werden im Schritt-4-Korpuslauf
        // übersprungen — sonst erzeugt ein einziger Datensatz-Neuaufbau zwei
        // Indexierungs-Jobs je Dokument (verdoppelte Verarbeitung und
        // unverständliche doppelte Verlaufszeilen im Dokument).
        java.util.Set<java.util.UUID> indexedThisRun = new java.util.HashSet<>();

        // ── 1) Dokumente aus uploads/docs/ ─────────────────────────────────
        stage.accept("Dokumentverzeichnis wird geprüft …");
        Path docsDir = uploadDir.resolve("docs");
        List<Path> docFiles = listFiles(docsDir);
        for (Path file : docFiles) {
            String name = file.getFileName().toString();
            String ext = extension(name);
            try {
                if (!DOC_EXTENSIONS.contains(ext)) {
                    unsupportedSkipped++;
                    unsupportedNames.add("docs/" + name + " (nicht unterstützter Dokumenttyp)");
                    continue;
                }
                Document existing = findDocumentByTitle(name);
                if (existing != null) {
                    try {
                        indexingOrchestrationService.reindexDocument(existing.id());
                        indexedThisRun.add(existing.id());
                        docsReindexed++;
                    } catch (Exception e) {
                        notes.add("Dokument '" + name + "' konnte nicht neu indexiert werden.");
                    }
                } else {
                    java.util.UUID created = registerAndIngest(file, name, ext);
                    if (created != null) {
                        indexedThisRun.add(created);
                    }
                    docsRegistered++;
                }
            } catch (Exception e) {
                log.warn("Datensatz: Dokument '{}' übersprungen: {}", name, e.getMessage());
                unsupportedSkipped++;
                unsupportedNames.add("docs/" + name + " (Fehler bei der Verarbeitung)");
            }
        }

        // ── 2) Fotos aus uploads/photos/ ───────────────────────────────────
        stage.accept("Foto-Verzeichnis wird geprüft …");
        Path photosDir = uploadDir.resolve("photos");
        List<Path> photoFiles = listFiles(photosDir);
        for (Path file : photoFiles) {
            String name = file.getFileName().toString();
            String ext = extension(name);
            if (!IMAGE_EXTENSIONS.contains(ext)) {
                unsupportedSkipped++;
                unsupportedNames.add("photos/" + name + " (kein JPG/PNG)");
                continue;
            }
            // EXIF-bereinigte Anzeige-/Download-Kopien sind Artefakte der
            // Foto-Pipeline, keine Quell-Fotos — sie werden nicht importiert.
            if (name.contains("_display.")) {
                unsupportedSkipped++;
                unsupportedNames.add("photos/" + name + " (bereinigte Anzeige-Kopie — kein Quell-Foto)");
                continue;
            }
            try {
                if (importPhoto(file, name)) {
                    photosImported++;
                } else {
                    notes.add("Foto '" + name + "' bereits vorhanden — übersprungen.");
                }
            } catch (Exception e) {
                log.warn("Datensatz: Foto '{}' übersprungen: {}", name, e.getMessage());
                unsupportedSkipped++;
                unsupportedNames.add("photos/" + name + " (Fehler bei der Verarbeitung)");
            }
        }

        // ── 3) Video / Audio: erkannt, aber nicht indexierbar ─────────────
        stage.accept("Medienverzeichnisse werden geprüft …");
        Path videoDir = uploadDir.resolve("video");
        List<Path> videoFiles = listFiles(videoDir);
        videosSkipped = videoFiles.size();
        videoFiles.forEach(f -> unsupportedNames.add("video/" + f.getFileName() + " (Video — nicht indexierbar)"));
        Path audioDir = uploadDir.resolve("audio");
        List<Path> audioFiles = listFiles(audioDir);
        audioSkipped = audioFiles.size();
        audioFiles.forEach(f -> unsupportedNames.add("audio/" + f.getFileName() + " (Audio — nicht indexierbar)"));

        // ── 4) Registrierten Korpus neu indexieren (Index-Neuaufbau) ──────
        stage.accept("Bestehende Dokumente werden neu indexiert …");
        for (int page = 0; ; page++) {
            var pageDocs = documentFacade.findDocuments(
                    new DocumentFilter(null, null, null, null, null, null, null, page, 100));
            if (pageDocs.documents().isEmpty()) break;
            for (Document doc : pageDocs.documents()) {
                if (doc.status() == reasoning.common.model.DocumentStatus.DELETED) continue;
                // In diesem Lauf bereits indexiert → kein zweiter Job (2D.16).
                if (indexedThisRun.contains(doc.id())) continue;
                try {
                    indexingOrchestrationService.reindexDocument(doc.id());
                    corpusReindexed++;
                } catch (Exception e) {
                    notes.add("Dokument '" + doc.id() + "' konnte nicht neu indexiert werden.");
                }
            }
            if (pageDocs.documents().size() < 100) break;
        }

        stage.accept("Abschluss …");
        return new DatasetReport(docsRegistered, docsReindexed, photosImported,
                videosSkipped, audioSkipped, unsupportedSkipped, corpusReindexed,
                unsupportedNames, notes);
    }

    private java.util.UUID registerAndIngest(Path file, String name, String ext) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String checksum = sha256(bytes);
        DocumentFileType type = switch (ext) {
            case "pdf" -> DocumentFileType.PDF;
            case "docx" -> DocumentFileType.DOCX;
            case "html", "htm" -> DocumentFileType.HTML;
            default -> DocumentFileType.TXT;
        };
        Document doc = documentFacade.createDocument(new CreateDocumentCommand(
                name, type, name, contentTypeFor(ext), bytes.length,
                "local", "docs/" + name, checksum, "OTHER", Set.of(),
                "INTERNAL", "admin@verwaltungsassistent.local", "default"));
        try {
            var job = documentFacade.createIngestionJob(doc.id(), "admin@verwaltungsassistent.local");
            documentFacade.startIngestion(job.id(), "admin@verwaltungsassistent.local");
            ingestionProcessor.ingest(doc.id());
            documentFacade.completeIngestion(job.id(), "admin@verwaltungsassistent.local");
            return doc.id();
        } catch (Exception e) {
            log.warn("Datensatz: Indexierung von '{}' fehlgeschlagen: {}", name, e.getMessage());
            return null;
        }
    }

    private boolean importPhoto(Path file, String name) throws IOException {
        String storagePath = "photos/" + name;
        // Existenzprüfung über das Quell-Foto (original_path): storage_path
        // zeigt auf die bereinigte Anzeige-Kopie und darf nicht verglichen
        // werden — sonst würden Demo-Fotos bei jedem Lauf erneut importiert.
        boolean exists = photoRepository.findAll().stream()
                .anyMatch(p -> storagePath.equals(p.getOriginalPath()));
        if (exists) {
            return false;
        }
        ExtractedGps gps = photoService.extract(storagePath);
        GeoPhotoEntity photo = new GeoPhotoEntity(UUID.randomUUID(), name, storagePath,
                name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg",
                "admin@verwaltungsassistent.local", Instant.now());
        photo.setGpsSource(gps.hasGps() ? "EXIF" : null);
        photoService.applyMetadata(photo, gps);
        photo.setOriginalPath(storagePath);
        try {
            photo.setStoragePath(photoService.createSanitizedCopy(
                    photoService.resolve(storagePath), photo.getContentType()));
        } catch (Exception e) {
            log.warn("Datensatz: bereinigte Kopie für '{}' fehlgeschlagen: {}", name, e.getMessage());
        }
        photoRepository.save(photo);
        return true;
    }

    private Document findDocumentByTitle(String title) {
        for (int page = 0; ; page++) {
            var pageDocs = documentFacade.findDocuments(
                    new DocumentFilter(null, null, null, null, null, null, null, page, 100));
            if (pageDocs.documents().isEmpty()) break;
            for (Document doc : pageDocs.documents()) {
                if (doc.metadata() != null && title.equals(doc.metadata().title())) {
                    return doc;
                }
            }
            if (pageDocs.documents().size() < 100) break;
        }
        return null;
    }

    private static List<Path> listFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            log.warn("Datensatz: Verzeichnis '{}' nicht lesbar: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private static String contentTypeFor(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "html", "htm" -> "text/html";
            default -> "text/plain";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes).toString();
        } catch (Exception e) {
            return String.valueOf(bytes.length);
        }
    }
}
