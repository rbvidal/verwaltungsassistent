package verwaltungsassistent.web.prepare;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.common.model.DocumentFileType;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.DocumentIngestionProcessor;
import reasoning.document.infrastructure.persistence.DocumentEntity;
import reasoning.document.infrastructure.persistence.JpaDocumentEntityRepository;
import reasoning.document.infrastructure.persistence.JpaIngestionJobEntityRepository;
import reasoning.document.model.Document;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.VectorSearchProvider;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.mailbox.DemoMailboxServer;
import verwaltungsassistent.web.mailbox.MailboxIngestionService;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository;
import verwaltungsassistent.web.service.DemoDataService;
import reasoning.neo4j.service.GraphEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2D.15 — reproduzierbare Demo-Aufbereitung aus einem Dateisystem-
 * Dataset (RunPod): läuft ALS ApplicationRunner IM bestehenden Demo-Spring-
 * Kontext (kein zweiter Boot, kein zweiter Kontext) und orchestriert
 * AUSSCHLIESSLICH bestehende Dienste:
 *
 * <pre>
 * reset (DemoDataService.reset: Nutzer/Mailboxen/Fotos/Geo)
 *   → vollständige Demo-Reinigung (E-Mails, Analysen, Vorgänge, Dokumente+Index)
 *   → Wissensdokumente aus <root>/documents importieren (bestehende Pipeline)
 *   → E-Mails aus <root>/emails als IncomingMessage → MailboxIngestionService.processOne
 *   → deterministische Vor-Analyse (processOne)
 *   → Intake + Anhänge (processOne)
 *   → vollständige KI-Kommunikationsanalyse (submitAnalysis) + Abwarten (Waiter)
 *   → Verifikation & Bericht
 * </pre>
 *
 * <p>GreenMail wird vollständig umgangen; es gibt KEINEN Outbound, KEINE
 * zweite Analyse-Implementierung. Konfiguration:
 * {@code demo.prepare.enabled} (Default false), {@code demo.dataset.root}
 * (Default /data/demo), {@code demo.prepare.exit} (Default false),
 * {@code demo.prepare.fail-on-review} (Default false, wirkt nur auf den
 * Exit-Code).</p>
 */
@Component
@Profile("demo")
@Order(0)
public class DemoDatasetPreparer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDatasetPreparer.class);
    private static final int DOC_PAGE_SIZE = 200;

    private final boolean enabled;
    private final boolean exitAfterPreparation;
    private final boolean failOnReview;
    private final boolean force;
    private final Path datasetRoot;

    private final DemoDataService demoDataService;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final JpaEmailAnalysisRepository emailAnalysisRepository;
    private final WorkspaceService workspaceService;
    private final JpaCasePlanningRepository casePlanningRepository;
    private final JpaEffortObservationRepository effortObservationRepository;
    private final DocumentFacade documentFacade;
    private final JpaDocumentEntityRepository documentEntityRepository;
    private final JpaIngestionJobEntityRepository ingestionJobEntityRepository;
    private final ChunkManagementService chunkManagementService;
    private final VectorSearchProvider vectorSearchProvider;
    private final ObjectProvider<GraphEnrichmentService> graphServiceProvider;
    private final DocumentIngestionProcessor ingestionProcessor;
    private final MailboxIngestionService mailboxIngestionService;
    private final CommunicationAnalysisWaiter analysisWaiter;
    private final GeoPhotoRepository geoPhotoRepository;
    private final UserAccountRepository userAccountRepository;
    private final Path uploadDir;

    /** Wanduhr-Start der Aufbereitung — für die [DEMO-PREP]-Fortschrittszeilen. */
    private long startedWallMillis;

    private String elapsed() {
        long total = Math.max(0, (System.currentTimeMillis() - startedWallMillis) / 1000);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    public DemoDatasetPreparer(
            @Value("${demo.prepare.enabled:false}") boolean enabled,
            @Value("${demo.prepare.exit:false}") boolean exitAfterPreparation,
            @Value("${demo.prepare.fail-on-review:false}") boolean failOnReview,
            @Value("${demo.prepare.force:false}") boolean force,
            @Value("${demo.dataset.root:/data/demo}") String datasetRoot,
            @Value("${app.upload-dir:uploads}") String uploadDirPath,
            DemoDataService demoDataService,
            JpaIncomingEmailRepository incomingEmailRepository,
            JpaEmailAnalysisRepository emailAnalysisRepository,
            WorkspaceService workspaceService,
            JpaCasePlanningRepository casePlanningRepository,
            JpaEffortObservationRepository effortObservationRepository,
            DocumentFacade documentFacade,
            JpaDocumentEntityRepository documentEntityRepository,
            JpaIngestionJobEntityRepository ingestionJobEntityRepository,
            ChunkManagementService chunkManagementService,
            VectorSearchProvider vectorSearchProvider,
            ObjectProvider<GraphEnrichmentService> graphServiceProvider,
            DocumentIngestionProcessor ingestionProcessor,
            MailboxIngestionService mailboxIngestionService,
            CommunicationAnalysisWaiter analysisWaiter,
            GeoPhotoRepository geoPhotoRepository,
            UserAccountRepository userAccountRepository) {
        this.enabled = enabled;
        this.exitAfterPreparation = exitAfterPreparation;
        this.failOnReview = failOnReview;
        this.force = force;
        this.datasetRoot = Path.of(datasetRoot).toAbsolutePath().normalize();
        this.uploadDir = Path.of(uploadDirPath).toAbsolutePath().normalize();
        this.demoDataService = demoDataService;
        this.incomingEmailRepository = incomingEmailRepository;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.workspaceService = workspaceService;
        this.casePlanningRepository = casePlanningRepository;
        this.effortObservationRepository = effortObservationRepository;
        this.documentFacade = documentFacade;
        this.documentEntityRepository = documentEntityRepository;
        this.ingestionJobEntityRepository = ingestionJobEntityRepository;
        this.chunkManagementService = chunkManagementService;
        this.vectorSearchProvider = vectorSearchProvider;
        this.graphServiceProvider = graphServiceProvider;
        this.ingestionProcessor = ingestionProcessor;
        this.mailboxIngestionService = mailboxIngestionService;
        this.analysisWaiter = analysisWaiter;
        this.geoPhotoRepository = geoPhotoRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            // Diese Zeile erscheint bei JEDEM Demo-Boot — sie ist der erste
            // Hinweis, ob die Dataset-Aufbereitung überhaupt aktiv ist.
            log.info("[DEMO-PREP] deaktiviert (demo.prepare.enabled=false, datasetRoot={}, "
                    + "uploadDir={}) — normaler Demo-Boot (deterministisches Seeding). "
                    + "Aktivierung: DEMO_PREPARE_ENABLED=true (Umgebung) bzw. "
                    + "--demo.prepare.enabled=true (Argument).", datasetRoot, uploadDir);
            return;
        }
        log.info("[DEMO-PREP] aktiv: force={} exitAfterPreparation={} failOnReview={} "
                        + "datasetRoot={} uploadDir={} — destruktive Demo-Neuaufbereitung.",
                force, exitAfterPreparation, failOnReview, datasetRoot, uploadDir);
        // WICHTIG (Dokumente-Demo-Kohärenz): Die Aufbereitung ist DESTRUKTIV —
        // sie leert zuerst Dokumente, Vorgänge und Analysen und baut sie neu
        // auf. Als ApplicationRunner läuft sie NACH dem Start des Webservers,
        // d. h. während bereits Anfragen bedient werden: Ein Login in diesem
        // Fenster sieht eine leere Dokumente-/Fälle-Seite, obwohl der letzte
        // Lauf „COMPLETE" gemeldet hat. Deshalb wird ein bereits erfolgreich
        // aufbereiteter Zustand bei Neustarts NICHT erneut zerstört; eine
        // erneute Aufbereitung ist nur explizit über demo.prepare.force=true
        // möglich (Superadmin-Entscheidung, z. B. nach DB-Verlust).
        if (!force && alreadyPrepared()) {
            long docs = documentFacade.findDocuments(
                    new DocumentFilter(null, null, null, null, null, null, null, 0, 1))
                    .totalElements();
            long emails = incomingEmailRepository.count();
            log.info("[DEMO-PREP] Zustand bereits aufbereitet ({} Dokumente, {} E-Mails, "
                            + "Vorgänge vorhanden) — übersprungen. "
                            + "demo.prepare.force=true erzwingt eine Neu-Aufbereitung.",
                    docs, emails);
            return;
        }
        log.info("============================================================");
        log.info("[DEMO-PREP] DEMO DATASET PREPARATION (Dateisystem-Dataset: {})", datasetRoot);
        log.info("============================================================");
        startedWallMillis = System.currentTimeMillis();
        Summary summary = new Summary();
        boolean failed = false;
        try {
            resetBaseState();
            cleanupDemoState();
            int knowledgeDocs = importKnowledgeDocuments();
            summary.knowledgeDocuments = knowledgeDocs;
            importGeoImages(summary);
            prepareEmails(summary);
            failed = summary.failures > 0;
            verify(summary);
            printReport(summary);
        } catch (Exception e) {
            failed = true;
            log.error("[DEMO-PREP] Demo-Aufbereitung fehlgeschlagen: {}", e.getMessage(), e);
            printReport(summary);
        }
        int exitCode = failed || (failOnReview && summary.reviewRequired > 0) ? 1 : 0;
        log.info("[DEMO-PREP] DEMO DATASET PREPARATION {} nach {} (Exit-Code {})",
                failed ? "FAILED" : "COMPLETE", elapsed(), exitCode);
        if (exitAfterPreparation) {
            System.exit(exitCode);
        }
    }

    // ── Schritt 1: deterministische Basis (Nutzer, Mailboxen, Fotos/Geo) ─────

    /**
     * Erkennt einen bereits erfolgreich aufbereiteten Demo-Zustand: Dokumente
     * und E-Mails vorhanden und mindestens ein CASE-Vorgang angelegt. Greift
     * nur die Zähler (keine Löschung) — ein abgebrochener Lauf (z. B. nach
     * der Reinigung) gilt als nicht aufbereitet und wird wiederholt.
     */
    private boolean alreadyPrepared() {
        try {
            long docs = documentFacade.findDocuments(
                    new DocumentFilter(null, null, null, null, null, null, null, 0, 1))
                    .totalElements();
            if (docs <= 0) {
                return false;
            }
            if (incomingEmailRepository.count() <= 0) {
                return false;
            }
            return workspaceService.findAll().stream()
                    .anyMatch(ws -> "CASE".equalsIgnoreCase(ws.getWorkspaceType()));
        } catch (Exception e) {
            log.warn("Vorbereitungs-Prüfung nicht möglich ({}): {} — Aufbereitung läuft.",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private void resetBaseState() {
        log.info("[DEMO-PREP] 1/7 Demo-Basis zurücksetzen (Nutzer, Mailboxen, Fotos/Geo, Katalog) …");
        demoDataService.reset();
    }

    // ── Schritt 2: vollständige Demo-Reinigung ────────────────────────────────

    private void cleanupDemoState() {
        log.info("[DEMO-PREP] 2/7 Demo-Zustand vollständig reinigen (E-Mails, Analysen, Vorgänge, Geo, Dokumente) …");
        emailAnalysisRepository.deleteAll();
        incomingEmailRepository.deleteAll();
        List<WorkspaceEntity> workspaces = workspaceService.findAll().stream()
                .filter(w -> "CASE".equalsIgnoreCase(w.getWorkspaceType())
                        || "GEO".equalsIgnoreCase(w.getWorkspaceType()))
                .toList();
        for (WorkspaceEntity ws : workspaces) {
            try {
                casePlanningRepository.deleteByCaseId(UUID.fromString(ws.getId()));
                effortObservationRepository.deleteByCaseId(UUID.fromString(ws.getId()));
            } catch (Exception e) {
                log.debug("Planungs-/Aufwandsdaten von {} nicht entfernbar: {}", ws.getId(), e.getMessage());
            }
            try {
                workspaceService.deleteWorkspace(ws.getId().toString());
            } catch (Exception e) {
                log.warn("Vorgang {} nicht löschbar: {}", ws.getId(), e.getMessage());
            }
        }
        // GeoFoto-Datensätze der Basis-Pipeline entfernen — die Geo-Bilder des
        // Datasets werden im Schritt 4 neu durch die bestehende Geo-Pipeline
        // importiert (kein Alt-Zustand, keine Duplikate über Läufe).
        try {
            geoPhotoRepository.deleteAll();
        } catch (Exception e) {
            log.warn("GeoFoto-Datensätze nicht löschbar: {}", e.getMessage());
        }
        purgeAllDocuments();
        // Ingestion-Jobs verwaister Dokumente entfernen — der Hintergrund-
        // Retry-Dienst würde sonst dauerhaft "Document not found" melden.
        try {
            ingestionJobEntityRepository.deleteAll();
        } catch (Exception e) {
            throw new IllegalStateException("Ingestion-Job-Reinigung fehlgeschlagen: " + e.getMessage(), e);
        }
        cleanupRuntimeUploads();
        log.info("[DEMO-PREP] 2/7 Reinigung abgeschlossen nach {} (incoming_emails={}, "
                        + "email_analyses={}, Dokumente entfernt)",
                elapsed(), incomingEmailRepository.count(), emailAnalysisRepository.count());
    }

    /**
     * Entfernt ALLE Dokumente der vorherigen Läufe inkl. Chunks/Vektoren —
     * auch Foto-/GEO-Dokumente (die Geo-Bilder werden anschließend aus dem
     * Dataset neu erzeugt). Löschreihenfolge über bestehende Mechanismen:
     * Chunks je Dokument, Vektoren je Dokument, Graph einmalig, dann die
     * Dokument-Zeilen (JPA löscht Tags/Versions per Kaskade).
     *
     * <p>Fehlschläge der Index-Reinigung sind KEIN Warn-Fall: Wenn Chunks,
     * Vektoren oder Graphknoten nicht entfernbar sind, kann der neue Zustand
     * veraltete Einträge enthalten — die Vorbereitung bricht dann mit einem
     * Fehler ab (kein fälschlich „sauberer" Bericht).</p>
     */
    private void purgeAllDocuments() {
        List<DocumentEntity> toDelete = new ArrayList<>();
        int purgeFailures = 0;
        int purgedChunks = 0;
        for (int page = 0; ; page++) {
            var result = documentFacade.findDocuments(
                    new DocumentFilter(null, null, null, null, null, null, null, page, DOC_PAGE_SIZE));
            if (result.documents().isEmpty()) {
                break;
            }
            for (Document doc : result.documents()) {
                UUID docId = doc.id();
                try {
                    purgedChunks += chunkManagementService.deleteByDocumentId(docId);
                } catch (Exception e) {
                    purgeFailures++;
                    log.error("Chunks von {} nicht entfernbar: {}", docId, e.getMessage());
                }
                try {
                    vectorSearchProvider.deleteByDocument(docId);
                } catch (Exception e) {
                    purgeFailures++;
                    log.error("Vektoren von {} nicht entfernbar: {}", docId, e.getMessage());
                }
                documentEntityRepository.findById(docId)
                        .ifPresent(toDelete::add);
            }
            if (result.documents().size() < DOC_PAGE_SIZE) {
                break;
            }
        }
        GraphEnrichmentService graph = graphServiceProvider.getIfAvailable();
        if (graph != null) {
            try {
                graph.deleteAllDocumentNodes();
            } catch (Exception e) {
                purgeFailures++;
                log.error("Graph-Dokumentknoten nicht entfernbar: {}", e.getMessage());
            }
        }
        for (int i = 0; i < toDelete.size(); i += DOC_PAGE_SIZE) {
            List<DocumentEntity> batch = toDelete.subList(i, Math.min(i + DOC_PAGE_SIZE, toDelete.size()));
            try {
                documentEntityRepository.deleteAll(batch);
            } catch (Exception e) {
                purgeFailures++;
                log.error("Dokument-Zeilen nicht löschbar (Batch {}): {}", i / DOC_PAGE_SIZE, e.getMessage());
            }
        }
        log.info("[DEMO-PREP] Dokument-Reinigung: {} Chunks entfernt, {} Dokument-Zeilen gelöscht",
                purgedChunks, toDelete.size());
        if (purgeFailures > 0) {
            throw new IllegalStateException("Index-/Dokumentbereinigung fehlgeschlagen ("
                    + purgeFailures + " Fehler) — Zustand ist nicht reproduzierbar, Vorbereitung abgebrochen.");
        }
    }

    /**
     * Leert das konfigurierte RUNTIME-Upload-Verzeichnis ({@code app.upload-dir}).
     * Es werden NUR Dateien innerhalb dieses Roots gelöscht — das SOURCE-Dataset
     * bleibt unangetastet. Dadurch bleiben keine Alt-Dateien aus früheren Läufen
     * zurück (Anzeige-Kopien, Dataset-Importe, alte Storage-Keys); die Importe
     * der laufenden Vorbereitung erzeugen den benötigten Bestand neu.
     */
    private void cleanupRuntimeUploads() {
        if (!Files.isDirectory(uploadDir)) {
            return;
        }
        int files = 0;
        int dirs = 0;
        try (var stream = Files.list(uploadDir)) {
            List<Path> children = stream.toList();
            for (Path child : children) {
                try {
                    if (Files.isDirectory(child)) {
                        try (var walk = Files.walk(child)) {
                            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                if (Files.isDirectory(p)) {
                                    Files.deleteIfExists(p);
                                    dirs++;
                                } else {
                                    Files.deleteIfExists(p);
                                    files++;
                                }
                            }
                        }
                    } else {
                        Files.deleteIfExists(child);
                        files++;
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Runtime-Upload-Bereinigung fehlgeschlagen für " + child + ": " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Runtime-Upload-Bereinigung fehlgeschlagen: " + e.getMessage(), e);
        }
        log.info("[DEMO-PREP] Runtime-Upload-Verzeichnis geleert ({} Dateien, {} Ordner in {})",
                files, dirs, uploadDir);
    }

    // ── Schritt 3: Wissensdokumente aus <root>/documents ──────────────────────

    private int importKnowledgeDocuments() throws IOException {
        Path docsDir = datasetRoot.resolve("documents");
        if (!Files.isDirectory(docsDir)) {
            log.info("[DEMO-PREP] 3/7 Kein Dokumente-Verzeichnis ({}) — übersprungen.", docsDir);
            return 0;
        }
        log.info("[DEMO-PREP] 3/7 Wissensdokumente importieren aus {} …", docsDir);
        List<Path> files = listFiles(docsDir);
        int imported = 0;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                importKnowledgeDocument(docsDir, file);
                imported++;
            } catch (Exception e) {
                log.error("[DEMO-PREP] Wissensdokument '{}' nicht importierbar: {}", file, e.getMessage());
            }
            if ((i + 1) % 5 == 0 || i + 1 == files.size()) {
                log.info("[DEMO-PREP] 3/7 Wissensdokumente {}/{} verarbeitet nach {} (fehlerfrei: {})",
                        i + 1, files.size(), elapsed(),
                        imported == i + 1 ? "ja" : "nein — siehe Fehlerzeilen");
            }
        }
        log.info("[DEMO-PREP] 3/7 Wissensdokumente importiert: {} (nach {})", imported, elapsed());
        return imported;
    }

    // ── Schritt 4: Geo-Bilder aus <root>/images/geo über die bestehende Geo-Pipeline ──

    private void importGeoImages(Summary s) throws IOException {
        Path geoDir = datasetRoot.resolve("images/geo");
        if (!Files.isDirectory(geoDir)) {
            throw new IOException("Geo-Bildverzeichnis fehlt im Dataset: " + geoDir);
        }
        List<Path> images;
        try (var stream = Files.list(geoDir)) {
            images = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String lower = p.getFileName().toString().toLowerCase();
                        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                                || lower.endsWith(".png");
                    })
                    .sorted()
                    .toList();
        }
        if (images.isEmpty()) {
            throw new IOException("Keine Geo-Bilder unter " + geoDir);
        }
        log.info("[DEMO-PREP] 4/7 Geo-Bilder importieren ({} Datei(en) aus {}) …", images.size(), geoDir);
        int created = demoDataService.importDemoGeoPhotosFrom(geoDir);
        s.geoImagesOnDisk = images.size();
        log.info("[DEMO-PREP] 4/7 Geo-Import abgeschlossen nach {} (erzeugte Datensätze/Vorgänge "
                + "laut Pipeline: {}).", elapsed(), created);
    }

    private void importKnowledgeDocument(Path root, Path file) throws Exception {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String fileName = file.getFileName().toString();
        byte[] bytes = Files.readAllBytes(file);
        Path storage = uploadDir.resolve("dataset/" + rel).normalize();
        Files.createDirectories(storage.getParent());
        Files.write(storage, bytes);
        String checksum = sha256(bytes);
        String actor = DemoMailboxServer.MAILBOX_USER;
        Document doc = documentFacade.createDocument(new CreateDocumentCommand(
                fileName, fileTypeOf(fileName), fileName, contentTypeOf(fileName),
                bytes.length, "local", "dataset/" + rel, checksum,
                "OTHER", Set.of("demo-dataset", "sha256:" + checksum),
                "INTERNAL", actor, "default"));
        var job = documentFacade.createIngestionJob(doc.id(), actor);
        documentFacade.startIngestion(job.id(), actor);
        ingestionProcessor.ingest(doc.id());
        documentFacade.completeIngestion(job.id(), actor);
        log.debug("Wissensdokument importiert: {}", rel);
    }

    // ── Schritt 4/5: E-Mails → processOne → PRE → Intake → FULL AI ───────────

    private void prepareEmails(Summary summary) throws IOException {
        Path emailsDir = datasetRoot.resolve("emails");
        Path attachmentsRoot = datasetRoot.resolve("attachments");
        var parsed = DemoEmailFileParser.parseDirectory(emailsDir);
        List<IncomingMessage> messages =
                FilesystemDemoDatasetLoader.toIncomingMessages(parsed, attachmentsRoot);
        summary.datasetEmails = messages.size();
        log.info("[DEMO-PREP] 5/7 E-Mails importieren ({} Nachrichten, sequenziell mit "
                + "vollständiger KI-Analyse — je E-Mail 1–3 min auf der Ziel-GPU) …",
                messages.size());
        long emailStartedMillis = System.currentTimeMillis();
        for (int i = 0; i < messages.size(); i++) {
            IncomingMessage message = messages.get(i);
            summary.attachmentExpected += message.attachments() == null
                    ? 0 : message.attachments().size();
            log.info("[DEMO-PREP] 5/7 E-Mail {}/{} nach {} — verarbeite '{}' (Vorgang, "
                            + "Anhänge, vollständige KI-Analyse) …",
                    i + 1, messages.size(), elapsed(), shortSubject(message.subject()));
            try {
                MailboxIngestionService.MessageProcessResult result =
                        mailboxIngestionService.processOne(message);
                if (result.imported() == 0) {
                    summary.skipped++;
                    log.info("[DEMO-PREP] 5/7 E-Mail {}/{} übersprungen (Duplikat) — '{}'",
                            i + 1, messages.size(), shortSubject(message.subject()));
                    continue;
                }
                summary.imported++;
                if (result.emailId() == null) {
                    summary.failures++;
                    log.error("[DEMO-PREP] Import ohne E-Mail-Id: {}", shortSubject(message.subject()));
                    continue;
                }
                UUID emailId = result.emailId();
                summary.attachmentFound += attachmentDocumentsOf(message.messageId());
                boolean reviewRequired = isReviewRequired(emailId);
                if (result.workspaceId() != null) {
                    boolean full = analysisWaiter.awaitFullAnalysis(emailId);
                    if (full) {
                        summary.fullAiCompleted++;
                    } else if (reviewRequired) {
                        summary.reviewRequired++;
                    } else {
                        summary.failures++;
                        log.error("[DEMO-PREP] Vollständige KI-Analyse fehlgeschlagen: {}",
                                shortSubject(message.subject()));
                    }
                } else {
                    // Kein Vorgang: erwartet nur bei Prüfung erforderlich (ungültige
                    // Vorgangsnummer); alles andere ist ein Importfehler.
                    if (reviewRequired) {
                        summary.reviewRequired++;
                    } else {
                        summary.failures++;
                        log.error("[DEMO-PREP] Import ohne Vorgang und ohne Prüfvermerk: {}",
                                shortSubject(message.subject()));
                    }
                }
            } catch (Exception e) {
                summary.failures++;
                log.error("[DEMO-PREP] Importfehler für E-Mail '{}': {}",
                        shortSubject(message.subject()), e.getMessage());
            }
            long emailWallS = (System.currentTimeMillis() - emailStartedMillis) / 1000;
            emailStartedMillis = System.currentTimeMillis();
            log.info("[DEMO-PREP] 5/7 E-Mail {}/{} abgeschlossen in {} s (Stand: {} importiert, "
                            + "{} vollständige KI-Analysen, {} Prüfung erforderlich, {} Fehler, "
                            + "{} übersprungen)",
                    i + 1, messages.size(), emailWallS, summary.imported,
                    summary.fullAiCompleted, summary.reviewRequired, summary.failures,
                    summary.skipped);
        }
        log.info("[DEMO-PREP] 6/7 E-Mail-Verarbeitung abgeschlossen nach {}: {} importiert, "
                        + "{} KI-Analysen vollständig, {} Prüfung erforderlich, {} Fehler",
                elapsed(), summary.imported, summary.fullAiCompleted, summary.reviewRequired,
                summary.failures);
    }

    private boolean isReviewRequired(UUID emailId) {
        return incomingEmailRepository.findById(emailId)
                .map(e -> e.isReviewRequired())
                .orElse(false);
    }

    /** Anhang-Dokumente einer E-Mail über den Provenienz-Tag (bestehender Mechanismus). */
    private long attachmentDocumentsOf(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return 0;
        }
        var result = documentFacade.findDocuments(new DocumentFilter(
                null, null, null, "email:" + messageId, null, null, null, 0, 100));
        return result.documents().size();
    }

    // ── Verifikation & Bericht ────────────────────────────────────────────────

    private void verify(Summary s) {
        long emails = incomingEmailRepository.count();
        long analyses = emailAnalysisRepository.count();
        long aiAnswers = emailAnalysisRepository.findAll().stream()
                .filter(a -> hasNonEmptyAiAnswer(a.getResultJson()))
                .count();
        long documents = documentFacade.findDocuments(
                        new DocumentFilter(null, null, null, null, null, null, null, 0, 1))
                .totalElements();
        s.workspaces = workspaceService.findAll().stream()
                .filter(w -> "CASE".equalsIgnoreCase(w.getWorkspaceType())).count();
        s.geoWorkspaces = workspaceService.findAll().stream()
                .filter(w -> "GEO".equalsIgnoreCase(w.getWorkspaceType())).count();
        s.geoPhotos = geoPhotoRepository.count();
        long employees = userAccountRepository.findAll().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().startsWith("demo")
                        && u.getEmail().endsWith("@verwaltungsassistent.local"))
                .count();
        s.employees = employees;
        s.adminPresent = userAccountRepository.findByEmail("admin@verwaltungsassistent.local").isPresent();
        s.superadminPresent = userAccountRepository.findByEmail("superadmin@verwaltungsassistent.local").isPresent();
        s.baseUserPresent = userAccountRepository.findByEmail("user@verwaltungsassistent.local").isPresent();
        s.incomingEmails = emails;
        s.emailAnalyses = analyses;
        s.aiAnswers = aiAnswers;
        s.documents = documents;
        log.info("[DEMO-PREP] 7/7 Verifikation nach {}: incoming_emails={} (Dataset {}), "
                        + "Geo-Vorgänge={}, GeoFotos={}",
                elapsed(), emails, s.datasetEmails, s.geoWorkspaces, s.geoPhotos);
        if (s.datasetEmails > 0 && emails != s.datasetEmails) {
            log.error("[DEMO-PREP] INVARIANTE VERLETZT: incoming_emails ({}) != Dataset ({})",
                    emails, s.datasetEmails);
        }
        if (analyses != s.imported) {
            log.error("[DEMO-PREP] INVARIANTE VERLETZT: email_analyses ({}) != importiert ({})",
                    analyses, s.imported);
        }
        if (s.attachmentExpected != s.attachmentFound) {
            log.error("[DEMO-PREP] INVARIANTE VERLETZT: Anhang-Dokumente erwartet {} != gefunden {}",
                    s.attachmentExpected, s.attachmentFound);
        }
        if (s.failures > 0) {
            log.error("[DEMO-PREP] Verifikation: {} technische Fehler — Ergebnis NICHT vollständig.",
                    s.failures);
        }
    }

    private static boolean hasNonEmptyAiAnswer(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return false;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resultJson).path("aiAnswer");
            return node.isTextual() && !node.asText().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private void printReport(Summary s) {
        log.info("");
        log.info("[DEMO-PREP] === DEMO-DATASET-PREPARATION — ERGEBNIS ===");
        log.info("USERS");
        log.info("  Employees (demo..):  {}", s.employees);
        log.info("  admin@verwaltungsassistent.local:    {}", s.adminPresent ? "vorhanden" : "FEHLT");
        log.info("  superadmin@verwaltungsassistent.local: {}", s.superadminPresent ? "vorhanden" : "FEHLT (wird vom DevDataInitializer nach dem Runner angelegt)");
        log.info("  user@verwaltungsassistent.local:     {}", s.baseUserPresent ? "vorhanden" : "FEHLT (wird vom DevDataInitializer nach dem Runner angelegt)");
        log.info("GEO");
        log.info("  Geo images on disk:  {}", s.geoImagesOnDisk);
        log.info("  GeoVorgänge:         {}", s.geoWorkspaces);
        log.info("  GeoPhotoEntity:      {}", s.geoPhotos);
        log.info("KNOWLEDGE");
        log.info("  Knowledge docs:      {}", s.knowledgeDocuments);
        log.info("EMAIL");
        log.info("  Dataset emails:      {}", s.datasetEmails);
        log.info("  Imported:            {}", s.imported);
        log.info("  FULL AI completed:   {}", s.fullAiCompleted);
        log.info("  REVIEW_REQUIRED:     {}", s.reviewRequired);
        log.info("  Failures:            {}", s.failures);
        log.info("  Skipped (Duplikate): {}", s.skipped);
        log.info("  Incoming emails:     {}", s.incomingEmails);
        log.info("  Email analyses:      {}", s.emailAnalyses);
        log.info("  AI answers:          {}", s.aiAnswers);
        log.info("  Email attachment docs (gefunden/erwartet): {} / {}", s.attachmentFound, s.attachmentExpected);
        log.info("STATE");
        log.info("  Workspaces (CASE):   {}", s.workspaces);
        log.info("  Documents:           {}", s.documents);
        log.info("============================================");
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    private static List<Path> listFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        return files.stream()
                .sorted(java.util.Comparator.comparing(p -> dir.relativize(p).toString()))
                .toList();
    }

    private static DocumentFileType fileTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return DocumentFileType.PDF;
        }
        if (lower.endsWith(".docx")) {
            return DocumentFileType.DOCX;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return DocumentFileType.HTML;
        }
        return DocumentFileType.TXT;
    }

    private static String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html;charset=UTF-8";
        }
        return "text/plain;charset=UTF-8";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String safe(String value) {
        return value != null ? value : "—";
    }

    /** Betreff nur stark gekürzt in Fortschrittszeilen (kein Body, kein voller Betreff). */
    private static String shortSubject(String value) {
        if (value == null) {
            return "—";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** Lauf-Zähler für den Abschlussbericht. */
    private static final class Summary {
        int datasetEmails;
        int imported;
        int fullAiCompleted;
        int reviewRequired;
        int failures;
        int skipped;
        int knowledgeDocuments;
        int geoImagesOnDisk;
        long geoWorkspaces;
        long geoPhotos;
        long employees;
        boolean adminPresent;
        boolean superadminPresent;
        boolean baseUserPresent;
        long workspaces;
        long incomingEmails;
        long emailAnalyses;
        long aiAnswers;
        long documents;
        int attachmentExpected;
        long attachmentFound;
    }
}
