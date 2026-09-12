package verwaltungsassistent.web.service;

import reasoning.common.model.DocumentStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.model.Document;
import reasoning.neo4j.service.GraphEnrichmentService;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.VectorSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Administrative restore / delete operations for the "Datenwiederherstellung"
 * page. Uses the project's established dump formats (verwaltungsassistent-backup.sh):
 * PostgreSQL custom-format dump (pg_dump -Fc) restored via pg_restore, and
 * Neo4j database dump (neo4j-admin database dump) loaded via
 * neo4j-admin database load. Both run through Docker, matching the existing
 * appliance/demo infrastructure; container names are configurable.
 *
 * <p>"Alle Daten löschen" removes only the derived knowledge/index data
 * (PostgreSQL chunks + ingestion jobs, Qdrant vectors, Neo4j graph). The
 * uploaded source documents and the document metadata remain — the documents
 * can be re-indexed afterwards.
 */
@Service
public class DataRestoreService {

    private static final Logger log = LoggerFactory.getLogger(DataRestoreService.class);

    /** Magic bytes of a pg_dump custom-format dump ("PGDMP"). */
    private static final byte[] PGDMP_MAGIC = {'P', 'G', 'D', 'M', 'P'};
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    private final DocumentFacade documentFacade;
    private final ChunkManagementService chunkManagementService;
    private final VectorSearchProvider vectorSearchProvider;
    private final ObjectProvider<GraphEnrichmentService> graphServiceProvider;
    private final JdbcTemplate jdbcTemplate;
    private final String postgresContainer;
    private final String neo4jContainer;
    private final String neo4jImage;
    private final String postgresUser;
    private final String postgresDb;
    private final String qdrantContainer;
    private final String qdrantBaseUrl;

    public DataRestoreService(DocumentFacade documentFacade,
                              ChunkManagementService chunkManagementService,
                              VectorSearchProvider vectorSearchProvider,
                              ObjectProvider<GraphEnrichmentService> graphServiceProvider,
                              JdbcTemplate jdbcTemplate,
                              @Value("${app.data-restore.postgres-container:va-postgres}") String postgresContainer,
                              @Value("${app.data-restore.neo4j-container:mda-neo4j}") String neo4jContainer,
                              @Value("${app.data-restore.neo4j-image:neo4j:5-community}") String neo4jImage,
                              @Value("${app.data-restore.postgres-user:verwaltungsassistent}") String postgresUser,
                              @Value("${app.data-restore.postgres-db:verwaltungsassistent}") String postgresDb,
                              @Value("${app.data-restore.qdrant-container:mda-qdrant}") String qdrantContainer,
                              @Value("${app.data-restore.qdrant-base-url:http://localhost:6333}") String qdrantBaseUrl) {
        this.documentFacade = documentFacade;
        this.chunkManagementService = chunkManagementService;
        this.vectorSearchProvider = vectorSearchProvider;
        this.graphServiceProvider = graphServiceProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresContainer = postgresContainer;
        this.neo4jContainer = neo4jContainer;
        this.neo4jImage = neo4jImage;
        this.postgresUser = postgresUser;
        this.postgresDb = postgresDb;
        this.qdrantContainer = qdrantContainer;
        this.qdrantBaseUrl = qdrantBaseUrl;
    }

    /** Per-system outcome of a restore. */
    public record RestoreOutcome(String system, boolean success, String message) {}

    /** Per-store outcome of the delete-all operation. */
    public record DeleteAllOutcome(boolean chunksDeleted, boolean vectorsDeleted,
                                   boolean graphDeleted, boolean jobsDeleted,
                                   int documents, int chunks) {}

    /** True when the file starts with the pg_dump custom-format magic bytes. */
    public static boolean isPostgresCustomDump(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(5);
            if (head.length < 5) return false;
            for (int i = 0; i < 5; i++) {
                if (head[i] != PGDMP_MAGIC[i]) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    /**
     * Restores the PostgreSQL dump (custom format) via pg_restore inside the
     * configured container. The database keeps running; transient connection
     * errors during the restore are expected.
     */
    public RestoreOutcome restorePostgres(Path dumpFile) {
        try {
            run("docker", "cp", dumpFile.toString(),
                    postgresContainer + ":/tmp/verwaltungsassistent-restore.dump");
            try {
                var result = run("docker", "exec", postgresContainer, "pg_restore",
                        "-U", postgresUser, "-d", postgresDb,
                        "-Fc", "--clean", "--if-exists", "--no-owner", "--no-privileges",
                        "/tmp/verwaltungsassistent-restore.dump");
                return new RestoreOutcome("PostgreSQL", result.exitCode() == 0,
                        result.exitCode() == 0
                                ? "Wiederhergestellt"
                                : "Wiederherstellung fehlgeschlagen: " + result.lastError());
            } finally {
                run("docker", "exec", postgresContainer, "rm", "-f", "/tmp/verwaltungsassistent-restore.dump");
            }
        } catch (Exception e) {
            log.error("PostgreSQL restore failed", e);
            return new RestoreOutcome("PostgreSQL", false,
                    "Wiederherstellung fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Restores the Neo4j database dump. neo4j-admin database load requires the
     * database to be offline: the container is stopped, the load runs in a
     * helper container over the data volume, then the original container is
     * started again.
     */
    public RestoreOutcome restoreNeo4j(Path dumpFile) {
        String volume = neo4jDataVolume();
        // Helper container + docker cp instead of bind-mounting the dump
        // directory: the Docker daemon resolves -v paths on the HOST, so a
        // path that only exists inside this application container (the
        // containerized deployment) would silently mount an empty directory.
        String helper = "va-neo4j-load";
        try {
            run("docker", "rm", "-f", helper);
            run("docker", "stop", neo4jContainer);
            try {
                var created = run("docker", "create", "--name", helper,
                        "-v", volume + ":/data",
                        neo4jImage,
                        "neo4j-admin", "database", "load",
                        "--from-path=/tmp", "--overwrite-destination=true",
                        "neo4j");
                if (created.exitCode() != 0) {
                    return new RestoreOutcome("Neo4j", false,
                            "Wiederherstellung fehlgeschlagen: " + created.lastError());
                }
                // cp into the image's existing /tmp: a destination directory
                // that does not exist yet is rejected by docker cp. The
                // canonical file name (neo4j.dump) is what --from-path expects.
                var cp = run("docker", "cp", dumpFile.toString(), helper + ":/tmp/neo4j.dump");
                if (cp.exitCode() != 0) {
                    return new RestoreOutcome("Neo4j", false,
                            "Dump konnte nicht in den Lade-Container kopiert werden: " + cp.lastError());
                }
                var result = run("docker", "start", "-a", helper);
                return new RestoreOutcome("Neo4j", result.exitCode() == 0,
                        result.exitCode() == 0
                                ? "Wiederhergestellt"
                                : "Wiederherstellung fehlgeschlagen: " + result.lastError());
            } finally {
                run("docker", "rm", "-f", helper);
                run("docker", "start", neo4jContainer);
                waitForNeo4j();
            }
        } catch (Exception e) {
            log.error("Neo4j restore failed", e);
            return new RestoreOutcome("Neo4j", false,
                    "Wiederherstellung fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Restores a full-storage Qdrant snapshot (created via {@code POST /snapshots},
     * the format of the Datensicherung feature). The snapshot is copied into the
     * container and recovered via {@code POST /snapshots/recover} — the same
     * endpoint family the backup uses.
     */
    public RestoreOutcome restoreQdrant(Path snapshotFile) {
        String containerDir = "/qdrant/snapshots/mda_chunks";
        String containerPath = containerDir + "/" + snapshotFile.getFileName();
        try {
            run("docker", "exec", qdrantContainer, "sh", "-c", "mkdir -p " + containerDir);
            var cp = run("docker", "cp", snapshotFile.toString(),
                    qdrantContainer + ":" + containerPath);
            if (cp.exitCode() != 0) {
                return new RestoreOutcome("Qdrant", false,
                        "Snapshot konnte nicht in den Container kopiert werden: " + cp.lastError());
            }
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(qdrantBaseUrl + "/snapshots/recover"))
                    .timeout(Duration.ofMinutes(10))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"location\": \"" + containerPath + "\"}"))
                    .header("Content-Type", "application/json")
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String body = response.body() == null ? "" : response.body();
                return new RestoreOutcome("Qdrant", false,
                        "Qdrant-Wiederherstellung fehlgeschlagen (HTTP " + response.statusCode() + "): "
                                + body.substring(0, Math.min(200, body.length())));
            }
            return new RestoreOutcome("Qdrant", true, "Wiederhergestellt");
        } catch (Exception e) {
            log.error("Qdrant restore failed", e);
            return new RestoreOutcome("Qdrant", false,
                    "Wiederherstellung fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Name of the Docker volume holding the Neo4j data directory. */
    String neo4jDataVolume() {
        // "{{json .Mounts}}" avoids embedded quotes in the Go template, which
        // break on Windows command-line quoting; the JSON is parsed here.
        var result = run("docker", "inspect", neo4jContainer, "--format", "{{json .Mounts}}");
        if (result.exitCode() != 0 || result.stdout().isBlank()) {
            throw new IllegalStateException("Neo4j data volume nicht gefunden (Container " + neo4jContainer + ")");
        }
        try {
            var mounts = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.stdout());
            if (mounts.isArray()) {
                for (var m : mounts) {
                    if ("/data".equals(m.path("Destination").asText())) {
                        String name = m.path("Name").asText("");
                        if (!name.isBlank()) return name;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Neo4j mount info: {}", e.getMessage());
        }
        throw new IllegalStateException("Neo4j data volume nicht gefunden (Container " + neo4jContainer + ")");
    }

    void waitForNeo4j() {
        for (int i = 0; i < 30; i++) {
            var result = run("docker", "exec", neo4jContainer,
                    "cypher-shell", "-u", "neo4j", "-p", "password", "RETURN 1");
            if (result.exitCode() == 0) return;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Neo4j did not become ready after restore");
    }

    // ── Delete all index data ────────────────────────────────────────────────

    /**
     * Deletes all derived knowledge/index data: PostgreSQL chunks and
     * ingestion job records, Qdrant vectors, Neo4j graph nodes. Uploaded
     * source documents and their metadata remain untouched.
     */
    public DeleteAllOutcome deleteAllIndexData() {
        boolean chunksOk = false;
        boolean vectorsOk = false;
        boolean graphOk = false;
        boolean jobsOk = false;
        int documents = 0;
        int chunks = 0;
        try {
            for (int page = 0; ; page++) {
                DocumentFilter filter = new DocumentFilter(null, null, null, null, null, null, null, page, 100);
                var pageDocs = documentFacade.findDocuments(filter);
                if (pageDocs.documents().isEmpty()) break;
                for (Document doc : pageDocs.documents()) {
                    if (doc.status() == DocumentStatus.DELETED) continue;
                    documents++;
                    chunks += chunkManagementService.deleteByDocumentId(doc.id());
                }
                if (pageDocs.documents().size() < 100) break;
            }
            chunksOk = true;
        } catch (Exception e) {
            log.warn("Chunk deletion failed: {}", e.getMessage());
        }
        try {
            vectorSearchProvider.deleteAll();
            vectorsOk = true;
        } catch (Exception e) {
            log.warn("Vector deletion failed: {}", e.getMessage());
        }
        GraphEnrichmentService graphService = graphServiceProvider.getIfAvailable();
        if (graphService != null) {
            try {
                graphService.deleteAllDocumentNodes();
                graphOk = true;
            } catch (Exception e) {
                log.warn("Graph deletion failed: {}", e.getMessage());
            }
        }
        try {
            jdbcTemplate.update("DELETE FROM document_ingestion_jobs");
            jobsOk = true;
        } catch (Exception e) {
            log.warn("Ingestion job deletion failed: {}", e.getMessage());
        }
        return new DeleteAllOutcome(chunksOk, vectorsOk, graphOk, jobsOk, documents, chunks);
    }

    // ── Command execution ────────────────────────────────────────────────────

    record CommandResult(int exitCode, String stdout, String stderr) {
        String lastError() {
            String combined = (stderr != null && !stderr.isBlank() ? stderr : stdout);
            if (combined == null || combined.isBlank()) return "Unbekannter Fehler";
            String[] lines = combined.strip().split("\\R");
            return lines[lines.length - 1].trim();
        }
    }

    /** Raw (binary-safe) command result — used for pg_dump output. */
    record RawCommandResult(int exitCode, byte[] stdout, String stderr) {
        String lastError() {
            String combined = stderr != null && !stderr.isBlank() ? stderr
                    : (stdout != null ? new String(stdout, java.nio.charset.StandardCharsets.UTF_8) : "");
            if (combined == null || combined.isBlank()) return "Unbekannter Fehler";
            String[] lines = combined.strip().split("\\R");
            return lines[lines.length - 1].trim();
        }
    }

    /** Runs a command and returns its raw stdout bytes (binary-safe). */
    RawCommandResult runRaw(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Command timed out after {}s: {}", COMMAND_TIMEOUT.toSeconds(),
                        String.join(" ", command));
                return new RawCommandResult(1, stdout, stderr + "\nZeitüberschreitung");
            }
            return new RawCommandResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            log.warn("Could not run command '{}': {}", String.join(" ", command), e.getMessage());
            return new RawCommandResult(1, new byte[0], "Docker ist nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RawCommandResult(1, new byte[0], "Unterbrochen");
        }
    }

    CommandResult run(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Command timed out after {}s: {}", COMMAND_TIMEOUT.toSeconds(),
                        String.join(" ", command));
                return new CommandResult(1, stdout, stderr + "\nZeitüberschreitung");
            }
            log.info("Command '{}' exit={}", String.join(" ", command), process.exitValue());
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            log.warn("Could not run command '{}': {}", String.join(" ", command), e.getMessage());
            return new CommandResult(1, "", "Docker ist nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(1, "", "Unterbrochen");
        }
    }
}
