package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.service.DataBackupService;
import verwaltungsassistent.web.service.DataRestoreService;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Administration page "Datenwiederherstellung" (ADMIN only — /admin/** enforced
 * server-side by SecurityConfig). Backup (non-destructive) plus two destructive
 * operations, both destructive ones requiring an explicit confirmation that is
 * re-validated on the backend:
 *
 * <ul>
 *   <li>Datensicherung: PostgreSQL custom-format dump, Neo4j database dump and
 *       Qdrant snapshot, created via the established Docker tooling and listed
 *       in the backup history.</li>
 *   <li>Restore: PostgreSQL custom-format dump + Neo4j database dump, executed
 *       against the running Docker infrastructure; per-system status is reported
 *       and a failure of one system never reports overall success.</li>
 *   <li>Alle Daten löschen: removes all derived knowledge/index data (chunks,
 *       vectors, graph, ingestion jobs). Uploaded source documents and document
 *       metadata remain — the documents can be re-indexed afterwards.</li>
 * </ul>
 *
 * All operations run asynchronously through the existing JobProgressService
 * (single in-flight data operation at a time; the UI polls the progress panel).
 * Uploaded dump files are written to a temporary directory outside the web
 * root and removed after the operation.
 */
@Controller
public class AdminDataRestoreController {

    private static final Logger log = LoggerFactory.getLogger(AdminDataRestoreController.class);

    private static final String ACTIVE_KEY = "data-restore";
    private static final long MAX_DUMP_SIZE = 512L * 1024 * 1024;

    private final JobProgressService progressService;
    private final DataRestoreService dataRestoreService;
    private final DataBackupService dataBackupService;
    private final TemplateEngine templateEngine;
    private final ExecutorService executor;

    public AdminDataRestoreController(JobProgressService progressService,
                                      DataRestoreService dataRestoreService,
                                      DataBackupService dataBackupService,
                                      TemplateEngine templateEngine) {
        this.progressService = progressService;
        this.dataRestoreService = dataRestoreService;
        this.dataBackupService = dataBackupService;
        this.templateEngine = templateEngine;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "data-restore-worker");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/admin/data-restore")
    public String page(Model model) {
        model.addAttribute("pageTitle", "Administration — Datenwiederherstellung");
        model.addAttribute("activeSection", "admin");
        model.addAttribute("adminTitle", "Datenwiederherstellung");
        model.addAttribute("adminTab", "data-restore");
        model.addAttribute("backups", dataBackupService.list());
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Administration", "/admin"),
                new HomeController.Breadcrumb("Datenwiederherstellung", "/admin/data-restore")));
        return "admin/data-restore";
    }

    // ── Datensicherung (backup) ───────────────────────────────────────────────

    /**
     * Starts a backup job for one component ("postgres", "neo4j", "qdrant")
     * or "all". Non-destructive; runs through the same progress mechanism as
     * the restore. ADMIN only (/admin/** enforced by SecurityConfig).
     */
    @PostMapping(value = "/admin/data-restore/backup/{component}", produces = "text/html;charset=UTF-8")
    public String backup(@PathVariable String component, Model model) {
        if (!java.util.Set.of("postgres", "neo4j", "qdrant", "all").contains(component)) {
            return renderPageError(model, "Unbekannte Sicherungs-Komponente.");
        }
        Job job = progressService.activeJob(ACTIVE_KEY);
        if (job == null) {
            job = progressService.create(JobProgressService.Kind.DATA_RESTORE, "Datensicherung");
            progressService.registerActive(ACTIVE_KEY, job.jobId);
            final String jobId = job.jobId;
            final String comp = component;
            executor.submit(() -> runBackup(jobId, comp));
        }
        return progressModel(model, job, "/admin/data-restore/backup/progress/" + job.jobId,
                "Datensicherung wird erstellt",
                "Die Sicherung wird vorbereitet …",
                "Die Sicherung kann einen Moment dauern. Die Sicherungsdatei wird im Sicherungsverzeichnis abgelegt.");
    }

    @GetMapping("/admin/data-restore/backup/progress/{jobId}")
    public ResponseEntity<String> backupProgress(@PathVariable String jobId) {
        Job job = progressService.get(jobId);
        if (job == null) {
            return html(renderResult("admin/data-restore-fragments", "backupResult",
                    Map.of("results", List.of(),
                            "backups", dataBackupService.list(),
                            "error", "Die Sicherung ist nicht mehr verfügbar. Bitte erneut ausführen.")));
        }
        if ("DONE".equals(job.state) && job.outcome != null) {
            return html((String) job.outcome);
        }
        if ("ERROR".equals(job.state)) {
            return html(renderResult("admin/data-restore-fragments", "backupResult",
                    Map.of("results", List.of(),
                            "backups", dataBackupService.list(),
                            "error", "Die Sicherung ist fehlgeschlagen. Bitte im Log nachsehen und erneut versuchen.")));
        }
        return html(progressPanel(job, "/admin/data-restore/backup/progress/" + jobId,
                "Datensicherung wird erstellt", "Die Sicherung wird vorbereitet …",
                "Die Sicherung kann einen Moment dauern. Die Sicherungsdatei wird im Sicherungsverzeichnis abgelegt."));
    }

    private void runBackup(String jobId, String component) {
        try {
            List<String> stages = switch (component) {
                case "postgres" -> List.of("PostgreSQL");
                case "neo4j" -> List.of("Neo4j");
                case "qdrant" -> List.of("Qdrant");
                default -> List.of("PostgreSQL", "Neo4j", "Qdrant");
            };
            List<DataBackupService.BackupResult> results = new java.util.ArrayList<>();
            for (String stage : stages) {
                recordStage(jobId, stage + "-Sicherung wird erstellt …");
                DataBackupService.BackupResult result = switch (stage) {
                    case "PostgreSQL" -> dataBackupService.backupPostgres();
                    case "Neo4j" -> dataBackupService.backupNeo4j();
                    default -> dataBackupService.backupQdrant();
                };
                if (result == null) {
                    result = new DataBackupService.BackupResult(stage, null, 0, false,
                            "Sicherung lieferte kein Ergebnis.");
                }
                results.add(result);
                recordStage(jobId, result.success()
                        ? stage + " gesichert."
                        : stage + ": Sicherung fehlgeschlagen — " + result.message());
            }
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("results", results);
            attrs.put("backups", dataBackupService.list());
            String rendered = renderResult("admin/data-restore-fragments", "backupResult", attrs);
            progressService.complete(jobId, rendered, "Die Datensicherung wurde abgeschlossen.");
        } catch (Exception e) {
            log.error("Data backup failed", e);
            progressService.fail(jobId);
        } finally {
            progressService.unregisterActive(ACTIVE_KEY, jobId);
        }
    }

    /**
     * Downloads a created backup file. ADMIN only (/admin/** enforced by
     * SecurityConfig). The file name is validated against the established
     * backup naming pattern and resolved inside the backup directory, so the
     * endpoint can never serve files outside the backup folder.
     */
    @GetMapping(value = "/admin/data-restore/backup/download", produces = "application/octet-stream")
    public ResponseEntity<byte[]> downloadBackup(@RequestParam("file") String fileName) {
        if (fileName == null
                || !fileName.matches("(postgres|neo4j|qdrant)-\\d{8}-\\d{4}\\.(dump|snapshot)")) {
            return ResponseEntity.badRequest().build();
        }
        try {
            java.nio.file.Path file = dataBackupService.resolveBackup(fileName);
            if (file == null || !Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            byte[] content = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .body(content);
        } catch (IOException e) {
            log.warn("Backup download failed for {}: {}", fileName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    private static final java.util.regex.Pattern BACKUP_SET_FILE = java.util.regex.Pattern.compile(
            "^(postgres|neo4j|qdrant)-(\\d{8}-\\d{4})\\.(dump|snapshot)$");

    /**
     * Prüft, dass die drei Dateien exakt dem etablierten Sicherungsformat
     * entsprechen und aus DEMSELBEN Sicherungssatz stammen. Die Zeitstempel im
     * Dateinamen dürfen um höchstens 5 Minuten abweichen: die "Sicherung aller
     * Komponenten" erzeugt die Qdrant-Sicherung sequenziell NACH PostgreSQL und
     * Neo4j, wodurch der Snapshot-Name regelmäßig in die nächste Minute fällt.
     * Ohne diese Prüfung könnte ein PG/Neo4j-Only-Restore einen inkonsistenten
     * Vektorindex hinterlassen.
     */
    private String backupSetTimestamp(MultipartFile pg, MultipartFile neo4j, MultipartFile qdrant) {
        if (pg == null || neo4j == null || qdrant == null) return null;
        java.time.LocalDateTime[] stamps = new java.time.LocalDateTime[3];
        String[] names = {pg.getOriginalFilename(), neo4j.getOriginalFilename(), qdrant.getOriginalFilename()};
        for (int i = 0; i < names.length; i++) {
            java.util.regex.Matcher m = BACKUP_SET_FILE.matcher(names[i] == null ? "" : names[i]);
            if (!m.matches()) return null;
            try {
                stamps[i] = java.time.LocalDateTime.parse(m.group(2),
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
            } catch (Exception e) {
                return null;
            }
        }
        long maxSpan = java.time.Duration.between(
                java.util.Arrays.stream(stamps).min(java.time.LocalDateTime::compareTo).orElseThrow(),
                java.util.Arrays.stream(stamps).max(java.time.LocalDateTime::compareTo).orElseThrow())
                .toMinutes();
        return maxSpan <= 5 ? stamps[0].format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) : null;
    }

    @PostMapping(value = "/admin/data-restore/restore", produces = "text/html;charset=UTF-8")
    public String restore(@RequestParam(value = "pgDump", required = false) MultipartFile pgDump,
                          @RequestParam(value = "neo4jDump", required = false) MultipartFile neo4jDump,
                          @RequestParam(value = "qdrantSnapshot", required = false) MultipartFile qdrantSnapshot,
                          @RequestParam(value = "confirmed", required = false) String confirmed,
                          Model model) {
        if (!"on".equals(confirmed)) {
            return renderPageError(model, "Bitte bestätigen Sie, dass vorhandene Daten überschrieben werden dürfen.");
        }
        Path pgPath = saveDump(pgDump, "pgdump.dump");
        Path neo4jPath = saveDump(neo4jDump, "neo4j.dump");
        Path qdrantPath = saveDump(qdrantSnapshot, "qdrant.snapshot");
        if (pgPath == null || neo4jPath == null || qdrantPath == null) {
            return renderPageError(model,
                    "Es müssen alle drei Sicherungsdateien ausgewählt werden: PostgreSQL-Dump, Neo4j-Dump "
                            + "und die zugehörige Qdrant-Sicherung. Ohne die Qdrant-Sicherung bliebe der "
                            + "Vektorindex inkonsistent (verwaiste Vektoren).");
        }
        if (!DataRestoreService.isPostgresCustomDump(pgPath)) {
            return renderPageError(model,
                    "Die PostgreSQL-Datei ist kein gültiger pg_dump (custom-format) — " +
                            "sie beginnt nicht mit der Signatur des etablierten Sicherungsformats.");
        }
        String setTimestamp = backupSetTimestamp(pgDump, neo4jDump, qdrantSnapshot);
        if (setTimestamp == null) {
            return renderPageError(model,
                    "Die drei Dateien stammen nicht aus demselben Sicherungssatz. Die Zeitstempel im "
                            + "Dateinamen müssen übereinstimmen (z. B. postgres-20260827-2200.dump, "
                            + "neo4j-20260827-2200.dump und qdrant-20260827-2200.snapshot).");
        }

        Job job = progressService.activeJob(ACTIVE_KEY);
        if (job == null) {
            job = progressService.create(JobProgressService.Kind.DATA_RESTORE, "Daten werden wiederhergestellt");
            progressService.registerActive(ACTIVE_KEY, job.jobId);
            final String jobId = job.jobId;
            executor.submit(() -> runRestore(jobId, pgPath, neo4jPath, qdrantPath));
        }
        return progressModel(model, job, "/admin/data-restore/restore/progress/" + job.jobId,
                "Daten werden wiederhergestellt",
                "Die Sicherungsdaten werden vorbereitet …",
                "Die Wiederherstellung kann einen Moment dauern. PostgreSQL, Neo4j und Qdrant werden nacheinander wiederhergestellt.");
    }

    @GetMapping("/admin/data-restore/restore/progress/{jobId}")
    public ResponseEntity<String> restoreProgress(@PathVariable String jobId) {
        Job job = progressService.get(jobId);
        if (job == null) {
            return html(renderResult("admin/data-restore-fragments", "restoreResult",
                    Map.of("pg", new DataRestoreService.RestoreOutcome("PostgreSQL", false,
                            "Die Wiederherstellung ist nicht mehr verfügbar."),
                            "neo4j", new DataRestoreService.RestoreOutcome("Neo4j", false,
                                    "Die Wiederherstellung ist nicht mehr verfügbar."),
                            "qdrant", new DataRestoreService.RestoreOutcome("Qdrant", false,
                                    "Die Wiederherstellung ist nicht mehr verfügbar."),
                            "overall", false)));
        }
        if ("DONE".equals(job.state) && job.outcome != null) {
            return html((String) job.outcome);
        }
        if ("ERROR".equals(job.state)) {
            return html(renderResult("admin/data-restore-fragments", "restoreResult",
                    Map.of("pg", new DataRestoreService.RestoreOutcome("PostgreSQL", false,
                            "Wiederherstellung fehlgeschlagen"),
                            "neo4j", new DataRestoreService.RestoreOutcome("Neo4j", false,
                                    "Wiederherstellung fehlgeschlagen"),
                            "qdrant", new DataRestoreService.RestoreOutcome("Qdrant", false,
                                    "Wiederherstellung fehlgeschlagen"),
                            "overall", false)));
        }
        return html(progressPanel(job, "/admin/data-restore/restore/progress/" + jobId,
                "Daten werden wiederhergestellt", "Die Sicherungsdaten werden vorbereitet …",
                "Die Wiederherstellung kann einen Moment dauern. PostgreSQL, Neo4j und Qdrant werden nacheinander wiederhergestellt."));
    }

    private void runRestore(String jobId, Path pgPath, Path neo4jPath, Path qdrantPath) {
        try {
            recordStage(jobId, "PostgreSQL-Dump wird wiederhergestellt …");
            var pg = dataRestoreService.restorePostgres(pgPath);
            recordStage(jobId, pg.success() ? "PostgreSQL wiederhergestellt." : "PostgreSQL: Wiederherstellung fehlgeschlagen.");
            recordStage(jobId, "Neo4j-Dump wird wiederhergestellt …");
            var neo4j = dataRestoreService.restoreNeo4j(neo4jPath);
            recordStage(jobId, neo4j.success() ? "Neo4j wiederhergestellt." : "Neo4j: Wiederherstellung fehlgeschlagen.");
            recordStage(jobId, "Qdrant-Snapshot wird wiederhergestellt …");
            var qdrant = dataRestoreService.restoreQdrant(qdrantPath);
            recordStage(jobId, qdrant.success() ? "Qdrant wiederhergestellt." : "Qdrant: Wiederherstellung fehlgeschlagen.");
            String rendered = renderResult("admin/data-restore-fragments", "restoreResult",
                    Map.of("pg", pg, "neo4j", neo4j, "qdrant", qdrant,
                            "overall", pg.success() && neo4j.success() && qdrant.success()));
            progressService.complete(jobId, rendered, "Die Wiederherstellung wurde abgeschlossen.");
        } catch (Exception e) {
            log.error("Data restore failed", e);
            progressService.fail(jobId);
        } finally {
            progressService.unregisterActive(ACTIVE_KEY, jobId);
            deleteQuietly(pgPath);
            deleteQuietly(neo4jPath);
            deleteQuietly(qdrantPath);
        }
    }

    // ── Alle Daten löschen ────────────────────────────────────────────────────

    @PostMapping(value = "/admin/data-restore/delete-all", produces = "text/html;charset=UTF-8")
    public String deleteAll(@RequestParam(value = "confirmed", required = false) String confirmed,
                            Model model) {
        if (!"on".equals(confirmed)) {
            return renderPageError(model,
                    "Bitte bestätigen Sie, dass alle Index- und Datenbankdaten gelöscht werden sollen.");
        }
        Job job = progressService.activeJob(ACTIVE_KEY);
        if (job == null) {
            job = progressService.create(JobProgressService.Kind.DATA_DELETE, "Alle Daten werden gelöscht");
            progressService.registerActive(ACTIVE_KEY, job.jobId);
            final String jobId = job.jobId;
            executor.submit(() -> runDeleteAll(jobId));
        }
        return progressModel(model, job, "/admin/data-restore/delete-all/progress/" + job.jobId,
                "Alle Daten werden gelöscht",
                "Die Index- und Datenbankdaten werden entfernt …",
                "Das Löschen kann einen Moment dauern. Die hochgeladenen Originaldokumente bleiben erhalten.");
    }

    @GetMapping("/admin/data-restore/delete-all/progress/{jobId}")
    public ResponseEntity<String> deleteAllProgress(@PathVariable String jobId) {
        Job job = progressService.get(jobId);
        if (job == null) {
            return html(renderResult("admin/data-restore-fragments", "deleteResult",
                    Map.of("outcome", new DataRestoreService.DeleteAllOutcome(false, false, false, false, 0, 0))));
        }
        if ("DONE".equals(job.state) && job.outcome != null) {
            return html((String) job.outcome);
        }
        if ("ERROR".equals(job.state)) {
            return html(renderResult("admin/data-restore-fragments", "deleteResult",
                    Map.of("outcome", new DataRestoreService.DeleteAllOutcome(false, false, false, false, 0, 0))));
        }
        return html(progressPanel(job, "/admin/data-restore/delete-all/progress/" + jobId,
                "Alle Daten werden gelöscht", "Die Index- und Datenbankdaten werden entfernt …",
                "Das Löschen kann einen Moment dauern. Die hochgeladenen Originaldokumente bleiben erhalten."));
    }

    private void runDeleteAll(String jobId) {
        try {
            recordStage(jobId, "Textabschnitte (PostgreSQL) werden gelöscht …");
            recordStage(jobId, "Vektoren (Qdrant) werden gelöscht …");
            recordStage(jobId, "Wissensgraph (Neo4j) wird gelöscht …");
            recordStage(jobId, "Indexierungsaufträge werden gelöscht …");
            var outcome = dataRestoreService.deleteAllIndexData();
            String rendered = renderResult("admin/data-restore-fragments", "deleteResult",
                    Map.of("outcome", outcome));
            progressService.complete(jobId, rendered, "Alle Index- und Datenbankdaten wurden gelöscht.");
        } catch (Exception e) {
            log.error("Delete-all failed", e);
            progressService.fail(jobId);
        } finally {
            progressService.unregisterActive(ACTIVE_KEY, jobId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String renderPageError(Model model, String message) {
        model.addAttribute("pageTitle", "Administration — Datenwiederherstellung");
        model.addAttribute("activeSection", "admin");
        model.addAttribute("adminTitle", "Datenwiederherstellung");
        model.addAttribute("adminTab", "data-restore");
        model.addAttribute("backups", dataBackupService.list());
        model.addAttribute("dataRestoreError", message);
        return "admin/data-restore";
    }

    /** Copies the uploaded dump into a private temp dir; null when missing/invalid. */
    private Path saveDump(MultipartFile file, String name) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_DUMP_SIZE) return null;
        try {
            Path dir = Files.createTempDirectory("verwaltungsassistent-restore-");
            Path target = dir.resolve(name);
            file.transferTo(target.toFile());
            return target;
        } catch (IOException e) {
            log.warn("Could not store uploaded dump {}: {}", name, e.getMessage());
            return null;
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
            Path dir = path.getParent();
            if (dir != null && dir.getFileName().toString().startsWith("verwaltungsassistent-restore-")) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            log.warn("Could not remove temp restore file {}: {}", path, e.getMessage());
        }
    }

    private void recordStage(String jobId, String message) {
        progressService.recordStage(jobId, message);
    }

    private String progressModel(Model model, Job job, String pollUrl, String title,
                                 String emptyMessage, String hint) {
        model.addAttribute("title", title);
        model.addAttribute("messages", job.messages);
        model.addAttribute("pollUrl", pollUrl);
        model.addAttribute("emptyMessage", emptyMessage);
        model.addAttribute("hint", hint);
        model.addAttribute("error", null);
        // Die gemeinsame Pipeline-Visualisierung erwartet eine NICHT-NULL-
        // Knotenliste (wie in DecisionWorkspaceController/EmailController);
        // ohne sie schlägt das progressPanel-Template beim Rendern fehl.
        model.addAttribute("nodes",
                verwaltungsassistent.web.service.PipelineDiagramSupport.pipelineNodes(job));
        return "fragments/progress :: progressPanel";
    }

    private String progressPanel(Job job, String pollUrl, String title,
                                 String emptyMessage, String hint) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("title", title);
        attrs.put("messages", job.messages);
        attrs.put("pollUrl", pollUrl);
        attrs.put("emptyMessage", emptyMessage);
        attrs.put("hint", hint);
        // error is intentionally omitted; Thymeleaf treats a missing attribute as null.
        return render("fragments/progress", "progressPanel", attrs);
    }

    private String renderResult(String template, String fragment, Map<String, Object> attributes) {
        return render(template, fragment, attributes);
    }

    private String render(String template, String fragment, Map<String, Object> attributes) {
        Context context = new Context(Locale.GERMANY, attributes);
        return templateEngine.process(template, java.util.Set.of(fragment), context);
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(body);
    }
}
