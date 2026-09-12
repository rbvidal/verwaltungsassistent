package verwaltungsassistent.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates backups for the "Datensicherung" section of the admin page. Uses the
 * project's established dump formats and Docker deployment (same conventions
 * as {@link DataRestoreService}):
 *
 * <ul>
 *   <li>PostgreSQL: {@code pg_dump -Fc} (custom format) — the exact format the
 *       restore path accepts.</li>
 *   <li>Neo4j: {@code neo4j-admin database dump} (offline, helper container
 *       over the data volume, database stopped during the dump).</li>
 *   <li>Qdrant: full snapshot via the Qdrant REST snapshot API (supported by
 *       the deployed Qdrant 1.x); the snapshot file is copied out of the
 *       container and removed from the container afterwards.</li>
 * </ul>
 *
 * <p>Backups are stored as files in the configured backup directory
 * ({@code app.data-restore.backup-dir}, default {@code ../backups}). Only
 * file metadata is exposed to the UI — never raw filesystem paths.</p>
 */
@Service
public class DataBackupService {

    private static final Logger log = LoggerFactory.getLogger(DataBackupService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter TS_DISPLAY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern BACKUP_FILE = Pattern.compile(
            "^(postgres|neo4j|qdrant)-(\\d{8}-\\d{4})\\.(dump|snapshot)$");

    private final DataRestoreService restoreService;
    private final Path backupDir;
    private final String postgresContainer;
    private final String postgresUser;
    private final String postgresDb;
    private final String neo4jContainer;
    private final String neo4jImage;
    private final String qdrantContainer;
    private final String qdrantBaseUrl;

    public DataBackupService(DataRestoreService restoreService,
                             @Value("${app.data-restore.postgres-container:va-postgres}") String postgresContainer,
                             @Value("${app.data-restore.postgres-user:verwaltungsassistent}") String postgresUser,
                             @Value("${app.data-restore.postgres-db:verwaltungsassistent}") String postgresDb,
                             @Value("${app.data-restore.neo4j-container:mda-neo4j}") String neo4jContainer,
                             @Value("${app.data-restore.neo4j-image:neo4j:5-community}") String neo4jImage,
                             @Value("${app.data-restore.qdrant-container:mda-qdrant}") String qdrantContainer,
                             @Value("${app.data-restore.qdrant-base-url:http://localhost:6333}") String qdrantBaseUrl,
                             @Value("${app.data-restore.backup-dir:../backups}") String backupDir) {
        this.restoreService = restoreService;
        this.postgresContainer = postgresContainer;
        this.postgresUser = postgresUser;
        this.postgresDb = postgresDb;
        this.neo4jContainer = neo4jContainer;
        this.neo4jImage = neo4jImage;
        this.qdrantContainer = qdrantContainer;
        this.qdrantBaseUrl = qdrantBaseUrl;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.backupDir);
        } catch (IOException e) {
            throw new IllegalStateException("Sicherungsverzeichnis konnte nicht erstellt werden: " + this.backupDir, e);
        }
    }

    /** Outcome of one component backup. */
    public record BackupResult(String component, String fileName, long sizeBytes,
                               boolean success, String message) {}

    /** One entry of the backup history table. */
    public record BackupEntry(String component, String fileName, String timestamp,
                              long sizeBytes, String status, String sortKey, String path) {}

    // ── Backups ───────────────────────────────────────────────────────────────

    /** pg_dump -Fc (custom format) — the format the restore path accepts. */
    public BackupResult backupPostgres() {
        String fileName = "postgres-" + TS.format(LocalDateTime.now()) + ".dump";
        try {
            var result = restoreService.runRaw("docker", "exec", postgresContainer,
                    "pg_dump", "-U", postgresUser, "-d", postgresDb, "-Fc");
            if (result.exitCode() != 0 || result.stdout().length < 5) {
                return new BackupResult("PostgreSQL", null, 0, false,
                        "pg_dump fehlgeschlagen: " + result.lastError());
            }
            Path target = backupDir.resolve(fileName);
            Files.write(target, result.stdout());
            return new BackupResult("PostgreSQL", fileName, Files.size(target), true, "Erfolgreich");
        } catch (Exception e) {
            log.error("PostgreSQL backup failed", e);
            return new BackupResult("PostgreSQL", null, 0, false,
                    "Sicherung fehlgeschlagen: " + e.getMessage());
        }
    }

    /** neo4j-admin database dump (offline; container stopped during the dump). */
    public BackupResult backupNeo4j() {
        String fileName = "neo4j-" + TS.format(LocalDateTime.now()) + ".dump";
        String volume;
        try {
            volume = restoreService.neo4jDataVolume();
        } catch (Exception e) {
            return new BackupResult("Neo4j", null, 0, false, e.getMessage());
        }
        // Helper container + docker cp instead of bind-mounting the backup
        // directory: the Docker daemon resolves -v paths on the HOST, so a
        // backup directory that only exists inside this application container
        // (the containerized deployment) would silently point nowhere.
        String helper = "va-neo4j-dump";
        try {
            restoreService.run("docker", "rm", "-f", helper);
            restoreService.run("docker", "stop", neo4jContainer);
            try {
                var result = restoreService.run("docker", "run", "--name", helper,
                        "-v", volume + ":/data",
                        neo4jImage,
                        "neo4j-admin", "database", "dump", "neo4j",
                        "--to-path=/tmp", "--overwrite-destination=true");
                if (result.exitCode() != 0) {
                    return new BackupResult("Neo4j", null, 0, false,
                            "neo4j-admin dump fehlgeschlagen: " + result.lastError());
                }
                Path target = backupDir.resolve(fileName);
                var cp = restoreService.run("docker", "cp",
                        helper + ":/tmp/neo4j.dump", target.toString());
                if (cp.exitCode() != 0) {
                    return new BackupResult("Neo4j", null, 0, false,
                            "Dump konnte nicht kopiert werden: " + cp.lastError());
                }
                return new BackupResult("Neo4j", fileName, Files.size(target), true, "Erfolgreich");
            } finally {
                restoreService.run("docker", "rm", "-f", helper);
                restoreService.run("docker", "start", neo4jContainer);
                restoreService.waitForNeo4j();
            }
        } catch (Exception e) {
            log.error("Neo4j backup failed", e);
            return new BackupResult("Neo4j", null, 0, false,
                    "Sicherung fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Qdrant full snapshot via the REST snapshot API (supported by the
     * deployed Qdrant 1.x). The snapshot is created inside the container,
     * copied to the backup directory, then removed from the container.
     */
    public BackupResult backupQdrant() {
        String fileName = "qdrant-" + TS.format(LocalDateTime.now()) + ".snapshot";
        try {
            String name = createQdrantSnapshot();
            String containerPath = findQdrantSnapshot(name);
            if (containerPath == null) {
                return new BackupResult("Qdrant", null, 0, false,
                        "Snapshot '" + name + "' nicht im Container gefunden.");
            }
            Path target = backupDir.resolve(fileName);
            var cp = restoreService.run("docker", "cp", qdrantContainer + ":" + containerPath,
                    target.toString());
            restoreService.run("docker", "exec", qdrantContainer, "rm", "-f", containerPath);
            if (cp.exitCode() != 0) {
                return new BackupResult("Qdrant", null, 0, false,
                        "Snapshot konnte nicht kopiert werden: " + cp.lastError());
            }
            return new BackupResult("Qdrant", fileName, Files.size(target), true, "Erfolgreich");
        } catch (Exception e) {
            log.error("Qdrant backup failed", e);
            return new BackupResult("Qdrant", null, 0, false,
                    "Sicherung fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Creates a full Qdrant snapshot and returns its name. */
    private String createQdrantSnapshot() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(qdrantBaseUrl + "/snapshots"))
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Qdrant-Snapshot fehlgeschlagen (HTTP " + response.statusCode() + "): "
                    + response.body());
        }
        String name = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.body()).path("result").path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Qdrant-Snapshot ohne Namen zurückgegeben: " + response.body());
        }
        return name;
    }

    /** Locates the snapshot file inside the Qdrant container (path may vary by config). */
    private String findQdrantSnapshot(String name) {
        var find = restoreService.run("docker", "exec", qdrantContainer, "sh", "-c",
                "find /qdrant -name '" + name + "'");
        if (find.exitCode() != 0 || find.stdout() == null || find.stdout().isBlank()) {
            return null;
        }
        String first = find.stdout().strip().split("\\R")[0].trim();
        return first.isEmpty() ? null : first;
    }

    /** Resolves a validated backup file name inside the backup directory; null when outside. */
    public Path resolveBackup(String fileName) {
        if (fileName == null) return null;
        Path resolved = backupDir.resolve(fileName).normalize();
        return resolved.startsWith(backupDir) ? resolved : null;
    }

    // ── History ───────────────────────────────────────────────────────────────

    /** Lists the available backups from the backup directory (newest first). */
    public List<BackupEntry> list() {
        List<BackupEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(backupDir)) return entries;
        try (var stream = Files.list(backupDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        Matcher m = BACKUP_FILE.matcher(path.getFileName().toString());
                        if (!m.matches()) return;
                        String component = switch (m.group(1)) {
                            case "postgres" -> "PostgreSQL";
                            case "neo4j" -> "Neo4j";
                            default -> "Qdrant";
                        };
                        String ts = m.group(2);
                        String display = ts;
                        try {
                            display = TS_DISPLAY.format(LocalDateTime.parse(ts, TS));
                        } catch (Exception ignored) {
                            // keep the raw timestamp when parsing fails
                        }
                        long size = path.toFile().length();
                        entries.add(new BackupEntry(component, path.getFileName().toString(),
                                display, size, "Erfolgreich", ts,
                                path.toAbsolutePath().normalize().toString()));
                    });
        } catch (IOException e) {
            log.warn("Could not list backups: {}", e.getMessage());
        }
        entries.sort(Comparator.comparing(BackupEntry::sortKey).reversed());
        return entries;
    }
}
