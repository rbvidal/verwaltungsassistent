package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.UpdateDocumentMetadataCommand;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.document.model.DocumentVersion;
import reasoning.common.model.DocumentCategory;
import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.IngestionStatus;
import reasoning.document.api.IngestionJobFilter;
import reasoning.neo4j.service.GraphEnrichmentService;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.VectorSearchProvider;
import verwaltungsassistent.web.form.UpdateDocumentMetadataForm;
import verwaltungsassistent.web.service.DocumentViewerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Demo-Betrieb: Leitungs-Konto vollständig schreibgeschützt (Bean fehlt
    // außerhalb des demo-Profils → kein Effekt).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private verwaltungsassistent.web.demo.DemoReadOnlyPolicy demoReadOnlyPolicy;

    private void denyDemoAdmin(reasoning.auth.api.AuthenticatedUser user) {
        if (demoReadOnlyPolicy != null) {
            demoReadOnlyPolicy.denyAdminMutation(user);
        }
    }

    private final DocumentFacade documentFacade;
    private final ChunkManagementService chunkManagementService;
    private final VectorSearchProvider vectorSearchProvider;
    private final ObjectProvider<GraphEnrichmentService> graphServiceProvider;
    private final DocumentViewerService documentViewerService;
    private final reasoning.document.api.DocumentIngestionProcessor ingestionProcessor;
    private final java.util.concurrent.ExecutorService ingestionExecutor;
    private final Path uploadDir;

    public DocumentController(DocumentFacade documentFacade,
                              ChunkManagementService chunkManagementService,
                              VectorSearchProvider vectorSearchProvider,
                              ObjectProvider<GraphEnrichmentService> graphServiceProvider,
                              DocumentViewerService documentViewerService,
                              reasoning.document.api.DocumentIngestionProcessor ingestionProcessor,
                              @Value("${app.upload-dir:uploads}") String uploadDirPath) {
        this.documentFacade = documentFacade;
        this.chunkManagementService = chunkManagementService;
        this.vectorSearchProvider = vectorSearchProvider;
        this.graphServiceProvider = graphServiceProvider;
        this.documentViewerService = documentViewerService;
        this.ingestionProcessor = ingestionProcessor;
        this.ingestionExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "upload-ingestion-worker");
            t.setDaemon(true);
            return t;
        });
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create upload directory: " + this.uploadDir, e);
        }
    }

    // ── Document List Page ──

    @GetMapping("/documents")
    public String listDocuments(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int page,
                                @AuthenticationPrincipal AuthenticatedUser user,
                                @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                                Model model) {
        addDocumentRows(model, status, q);
        model.addAttribute("pageTitle", "Dokumente");
        model.addAttribute("activeSection", "documents");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Dokumente", "/documents")));

        if (hxRequest != null) {
            return "documents/fragments :: documentTable";
        }
        return "documents/index";
    }

    // ── Upload Form ──

    @GetMapping("/documents/upload")
    public String uploadForm(@RequestParam(value = "error", required = false) String error,
                             Model model) {
        return uploadFormInternal(error, model);
    }

    /** Rendering helper used by the POST handlers (no error query param). */
    private String uploadForm(Model model) {
        return uploadFormInternal(null, model);
    }

    private String uploadFormInternal(String error, Model model) {
        model.addAttribute("categories", DocumentCategory.values());
        Map<String, String> categoryLabels = new LinkedHashMap<>();
        for (DocumentCategory c : DocumentCategory.values()) {
            categoryLabels.put(c.name(), categoryLabel(c.name()));
        }
        model.addAttribute("categoryLabels", categoryLabels);
        if ("maxsize".equals(error)) {
            model.addAttribute("uploadError",
                    "Die Datei ist zu groß. Die maximale Größe beträgt 50 MB pro Datei.");
        }
        model.addAttribute("pageTitle", "Dokument hochladen");
        model.addAttribute("activeSection", "documents");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Dokumente", "/documents"),
                new HomeController.Breadcrumb("Hochladen", "/documents/upload")));
        return "documents/upload";
    }

    @PostMapping("/documents/upload")
    public String handleUpload(@RequestParam("file") MultipartFile[] files,
                               @RequestParam(value = "title", required = false) String title,
                               @RequestParam(value = "category", required = false) String category,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               Model model) {
        denyDemoAdmin(user);
        if (files == null || files.length == 0
                || java.util.Arrays.stream(files).allMatch(MultipartFile::isEmpty)) {
            model.addAttribute("uploadError", "Bitte wählen Sie mindestens eine Datei aus.");
            return uploadForm(model);
        }

        List<String> errors = new ArrayList<>();
        int created = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            try {
                uploadOne(file, title, category, user);
                created++;
            } catch (IOException e) {
                log.error("Upload failed for {}", file.getOriginalFilename(), e);
                errors.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }

        if (created == 0 && !errors.isEmpty()) {
            model.addAttribute("uploadError", "Fehler beim Hochladen: " + String.join("; ", errors));
            return uploadForm(model);
        }
        if (!errors.isEmpty()) {
            log.warn("{} of {} uploads failed: {}", errors.size(), files.length, errors);
        }
        return "redirect:/documents";
    }

    /**
     * Batch upload used by the multi-file / directory picker: the same
     * upload + ingestion path as the regular form, but one file per request
     * with a JSON result so the UI can show per-file status.
     */
    @PostMapping(value = "/documents/upload/batch", produces = "application/json")
    @ResponseBody
    public Map<String, Object> handleBatchUpload(@RequestParam("file") MultipartFile[] files,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        List<Map<String, Object>> results = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("fileName", file.getOriginalFilename());
                if (file == null || file.isEmpty()) {
                    r.put("ok", false);
                    r.put("error", "Leere Datei");
                } else {
                    try {
                        Document doc = uploadOne(file, null, null, user);
                        r.put("ok", true);
                        r.put("documentId", doc.id().toString());
                    } catch (IOException e) {
                        r.put("ok", false);
                        r.put("error", e.getMessage());
                    }
                }
                results.add(r);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        return out;
    }

    /** Shared upload step: stores the file and creates document + ingestion job. */
    private Document uploadOne(MultipartFile file, String title, String category,
                               AuthenticatedUser user) throws IOException {
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String lower = fileName.toLowerCase();
        if (!(lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt")
                || lower.endsWith(".html") || lower.endsWith(".htm"))) {
            throw new IOException("nicht unterstütztes Dateiformat: " + fileName);
        }
        String docTitle = title != null && !title.isBlank() ? title.trim() : fileName;
        DocumentFileType fileType = detectFileType(fileName);
        String docCategoryStr = category != null && !category.isBlank() ? category : "OTHER";

        // Store file
        String storageKey = UUID.randomUUID() + "_" + fileName;
        Path filePath = uploadDir.resolve(storageKey);
        Files.write(filePath, file.getBytes());
        String checksum = sha256(file.getBytes());

        // Create document
        CreateDocumentCommand cmd = new CreateDocumentCommand(
                docTitle, fileType, fileName, file.getContentType(),
                file.getSize(), "local", storageKey, checksum,
                docCategoryStr, Set.of(), "INTERNAL", user.email(), "default");

        Document doc = documentFacade.createDocument(cmd);

        // Create and START the ingestion job immediately, then run the actual
        // indexing in the background. Waiting for the scheduled ingestion
        // poll (up to 10 s) previously left the document in "Ausstehend" —
        // the list only flipped to "In Verarbeitung" after a later page load.
        reasoning.document.model.DocumentIngestionJob job =
                documentFacade.createIngestionJob(doc.id(), user.email());
        documentFacade.startIngestion(job.id(), user.email());
        final UUID docId = doc.id();
        final String actorEmail = user != null ? user.email() : "system";
        ingestionExecutor.submit(() -> runIngestion(job.id(), docId, actorEmail));

        log.info("Document uploaded: id={} title={} size={}", doc.id(), docTitle, file.getSize());
        return doc;
    }

    /** Executes the ingestion job that was started synchronously after upload. */
    private void runIngestion(UUID jobId, UUID docId, String actorEmail) {
        try {
            ingestionProcessor.ingest(docId);
            documentFacade.completeIngestion(jobId, actorEmail);
        } catch (Exception e) {
            log.warn("Immediate ingestion failed for document {}: {}", docId, e.getMessage());
            try {
                documentFacade.failIngestion(jobId, actorEmail, e.getMessage());
            } catch (Exception ignored) {
                log.warn("Could not mark ingestion job {} as failed", jobId);
            }
        }
    }

    /**
     * Per-document ingestion status as JSON — used by the batch upload UI to
     * show per-file status (Hochgeladen / Wird indexiert / Bereit / Fehler).
     */
    @GetMapping("/documents/{id}/status")
    @ResponseBody
    public Map<String, Object> documentStatus(@PathVariable String id,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        UUID docId = CaseController.parseCaseId(id);
        Document doc = getDocumentOr404(docId, user);
        String ingestion = "NONE";
        try {
            var jobs = documentFacade.findIngestionJobs(new IngestionJobFilter(docId, null, null, 0, 1));
            if (!jobs.jobs().isEmpty()) {
                ingestion = jobs.jobs().get(0).status().name();
            }
        } catch (Exception e) {
            log.warn("Ingestion status lookup failed for {}: {}", docId, e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", doc.status().name());
        result.put("statusLabel", statusLabel(doc.status()));
        result.put("ingestionStatus", ingestion);
        result.put("ingestionLabel", ingestionStatusLabel(ingestion));
        return result;
    }

    /**
     * Updates metadata including temporal validity (publishedAt/validFrom/
     * validUntil/supersededAt) for a known source document. Validity is
     * external/admin/source metadata — never LLM output. Null dates remain
     * unknown; nothing is inferred from upload dates.
     */
    @PatchMapping("/documents/{id}/metadata")
    public ResponseEntity<Map<String, Object>> updateMetadata(
            @PathVariable String id,
            @RequestBody UpdateDocumentMetadataForm form,
            @AuthenticationPrincipal AuthenticatedUser user) {
        UUID docId = CaseController.parseCaseId(id);
        Document updated = documentFacade.updateMetadata(new UpdateDocumentMetadataCommand(
                docId,
                form.title(),
                form.type(),
                form.category(),
                form.tags(),
                form.visibility(),
                user.email(),
                form.publishedAt(),
                form.validFrom(),
                form.validUntil(),
                form.supersededAt()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", updated.id().toString());
        result.put("title", updated.metadata().title());
        result.put("publishedAt", updated.publishedAt() != null ? updated.publishedAt().toString() : null);
        result.put("validFrom", updated.validFrom() != null ? updated.validFrom().toString() : null);
        result.put("validUntil", updated.validUntil() != null ? updated.validUntil().toString() : null);
        result.put("supersededAt", updated.supersededAt() != null ? updated.supersededAt().toString() : null);
        return ResponseEntity.ok(result);
    }

    // ── Indexing Actions ──

    @PostMapping("/documents/{id}/index")
    public String startIndexing(@PathVariable String id,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        UUID docId = CaseController.parseCaseId(id);
        Document doc = getDocumentOr404(docId, user);
        try {
            documentFacade.createIngestionJob(doc.id(), user.email());
        } catch (Exception e) {
            log.warn("Ingestion job creation failed for {}: {}", docId, e.getMessage());
        }
        return "redirect:/documents";
    }

    @PostMapping("/documents/{id}/reindex")
    public String reindexDocument(@PathVariable String id,
                                   @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        UUID docId = CaseController.parseCaseId(id);
        getDocumentOr404(docId, user);
        try {
            documentFacade.createIngestionJob(docId, user.email());
        } catch (Exception e) {
            log.warn("Reindex failed for {}: {}", docId, e.getMessage());
        }
        return "redirect:/documents";
    }

    /**
     * Marks a document obsolete (ADMIN only): the status is persisted with
     * actor + timestamp, all derived representations (chunks, vectors, graph
     * nodes) are removed so the document no longer participates in retrieval,
     * and the source file + record stay intact for the audit trail.
     */
    @PostMapping("/documents/{id}/obsolete")
    public String markObsolete(@PathVariable String id,
                               @RequestParam(value = "reason", required = false) String reason,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        if (user == null || user.roles() == null || !user.roles().contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur Administratoren dürfen Dokumente als veraltet markieren.");
        }
        UUID docId = CaseController.parseCaseId(id);
        Document doc = getDocumentOr404(docId, user);
        documentFacade.markObsoleteDocument(docId, user.email());
        // Remove derived representations so the document stops being an
        // active knowledge source — the source file stays in storage.
        try {
            chunkManagementService.deleteByDocumentId(docId);
        } catch (Exception e) {
            log.warn("Chunk removal for obsolete document {} failed: {}", docId, e.getMessage());
        }
        try {
            vectorSearchProvider.deleteByDocument(docId);
        } catch (Exception e) {
            log.warn("Vector removal for obsolete document {} failed: {}", docId, e.getMessage());
        }
        GraphEnrichmentService graphService = graphServiceProvider.getIfAvailable();
        if (graphService != null) {
            try {
                graphService.deleteDocumentNodes(docId.toString());
            } catch (Exception e) {
                log.warn("Graph removal for obsolete document {} failed: {}", docId, e.getMessage());
            }
        }
        log.info("Document {} marked obsolete by {} (reason: {})", docId, user.email(), reason);
        return "redirect:/documents";
    }

    /**
     * Deletes one document together with all its derived representations
     * (PostgreSQL chunks, Qdrant vectors, Neo4j graph nodes). The uploaded
     * PDF file stays in the upload directory — file deletion is not part of
     * the existing document-delete semantics and there is no storage delete
     * implementation.
     */
    @DeleteMapping("/documents/{id}/delete")
    public String deleteDocument(@PathVariable String id,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 Model model) {
        denyDemoAdmin(user);
        UUID docId = CaseController.parseCaseId(id);
        Document doc = getDocumentOr404(docId, user);
        List<String> errors = new ArrayList<>();

        // Soft-delete the document itself. Already-deleted documents are
        // treated as success (idempotent) — derived data below is still
        // cleaned up.
        if (doc.status() != DocumentStatus.DELETED) {
            try {
                documentFacade.deleteDocument(docId, user.email());
            } catch (Exception e) {
                log.warn("Document soft-delete failed for {}: {}", docId, e.getMessage());
                errors.add("Das Dokument konnte nicht gelöscht werden.");
            }
        }
        try {
            int deletedChunks = chunkManagementService.deleteByDocumentId(docId);
            log.info("Deleted {} chunks for document {}", deletedChunks, docId);
        } catch (Exception e) {
            log.warn("Chunk deletion failed for {}: {}", docId, e.getMessage());
            errors.add("Die indexierten Textabschnitte konnten nicht gelöscht werden.");
        }
        try {
            vectorSearchProvider.deleteByDocument(docId);
        } catch (Exception e) {
            log.warn("Vector deletion failed for {}: {}", docId, e.getMessage());
            errors.add("Die Vektoreinträge konnten nicht gelöscht werden.");
        }
        GraphEnrichmentService graphService = graphServiceProvider.getIfAvailable();
        if (graphService != null) {
            try {
                graphService.deleteDocumentNodes(docId.toString());
            } catch (Exception e) {
                log.warn("Graph deletion failed for {}: {}", docId, e.getMessage());
                errors.add("Die Wissensgraph-Einträge konnten nicht gelöscht werden.");
            }
        }

        if (errors.isEmpty()) {
            model.addAttribute("deleteSuccess", true);
            model.addAttribute("deletedDocTitle",
                    doc.metadata() != null && doc.metadata().title() != null
                            ? doc.metadata().title() : "Dokument");
        } else {
            model.addAttribute("deleteErrors", errors);
        }
        addDocumentRows(model, null, null);
        return "documents/fragments :: documentTableContainer";
    }

    // ── Document Detail ──

    /**
     * Reusable document viewer fragment (metadata + indexed chunks), loaded
     * into the document viewer modal by htmx. Same access semantics as the
     * full document detail page.
     */
    @GetMapping("/documents/{id}/view")
    public String documentView(@PathVariable String id,
                               @RequestParam(value = "q", required = false) String q,
                               @RequestParam(value = "chunks", required = false) String chunks,
                               @AuthenticationPrincipal AuthenticatedUser user,
                               Model model) {
        UUID docId = CaseController.parseCaseId(id);
        try {
            java.util.Set<UUID> evidenceChunkIds = null;
            if (chunks != null && !chunks.isBlank()) {
                evidenceChunkIds = new java.util.LinkedHashSet<>();
                for (String c : chunks.split(",")) {
                    try {
                        evidenceChunkIds.add(UUID.fromString(c.trim()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (evidenceChunkIds.isEmpty()) evidenceChunkIds = null;
            }
            model.addAttribute("view", documentViewerService.view(docId, user.email(), q, evidenceChunkIds));
            // Presentation hint: the marked terms are an orientation aid
            // (Textmarker), not the relevance mechanism itself.
            model.addAttribute("viewHasHighlights", q != null && !q.isBlank());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nicht gefunden");
        }
        return "documents/fragments :: documentViewer";
    }

    /**
     * Serves the original stored file content for a document version. Used by
     * the document viewer modal to embed PDFs directly; for non-PDF documents
     * the viewer falls back to extracted full text.
     */
    @GetMapping("/documents/{id}/content")
    public ResponseEntity<byte[]> documentContent(@PathVariable String id,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        UUID docId = CaseController.parseCaseId(id);
        Document doc = getDocumentOr404(docId, user);
        DocumentVersion version = doc.versions() != null && !doc.versions().isEmpty()
                ? doc.versions().get(doc.versions().size() - 1)
                : null;
        if (version == null || version.storageKey() == null || version.storageKey().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path path = uploadDir.resolve(version.storageKey()).normalize();
        if (!path.startsWith(uploadDir) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] content = Files.readAllBytes(path);
            // The browser-declared content type from the upload is not
            // trustworthy (directory pickers send application/octet-stream);
            // the document type decides the actual media type so PDFs are
            // always served as application/pdf and render in the viewer.
            //
            // Security: only PDFs are delivered inline (rendered by the
            // browser's PDF viewer). Every other document — including
            // attacker-influenceable HTML/HTM attachments or uploads — is
            // forced to download with application/octet-stream so stored HTML
            // cannot execute JavaScript in the application origin.
            boolean isPdf = doc.metadata() != null && doc.metadata().type() == DocumentFileType.PDF;
            String contentType = isPdf ? "application/pdf" : "application/octet-stream";
            String disposition = isPdf ? "inline" : "attachment";
            String fileName = (version.fileName() != null && !version.fileName().isBlank()
                    ? version.fileName() : "dokument-" + docId)
                    .replace("\"", "'")
                    .replaceAll("[\\r\\n]+", " ");
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("Content-Disposition", disposition + "; filename=\"" + fileName + "\"")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(content);
        } catch (IOException e) {
            log.warn("Could not read document content for {}: {}", docId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Erlaubte lokale Rückkehr-Ziele der Dokument-Metadaten-Seite (Phase 2D.12). */
    private static final List<String> BACK_TARGET_PREFIXES = List.of(
            "/cases", "/emails", "/decisions", "/documents", "/knowledge",
            "/assistant", "/audit", "/admin");

    @GetMapping("/documents/{id}")
    public String documentDetail(@PathVariable String id,
                                 @RequestHeader(value = "Referer", required = false) String referer,
                                 jakarta.servlet.http.HttpServletRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 Model model) {
        UUID docId = CaseController.parseCaseId(id);
        Document doc = getDocumentOr404(docId, user);
        // Phase 2D.12 — "Zurück" führt dorthin, woher das Dokument geöffnet
        // wurde (z. B. Entscheidung/Fall), nicht immer auf /documents. Nur
        // SAME-ORIGIN-Referer mit erlaubtem lokalen Pfad-Präfix werden
        // übernommen; alles andere fällt auf die Dokumenten-Übersicht zurück.
        // Es wird NIE zu einer externen URL verlinkt.
        model.addAttribute("backUrl", safeBackTarget(referer, request, id));

        IngestionJobFilter jobFilter = new IngestionJobFilter(docId, null, null, 0, 5);
        List<DocumentIngestionJob> jobs;
        try {
            jobs = documentFacade.findIngestionJobs(jobFilter).jobs();
        } catch (Exception e) {
            jobs = List.of();
        }

        model.addAttribute("doc", doc);
        model.addAttribute("title", doc.metadata().title());
        model.addAttribute("type", doc.metadata().type() != null ? doc.metadata().type().name() : "—");
        model.addAttribute("category", doc.metadata().category() != null
                ? categoryLabel(doc.metadata().category()) : "—");
        model.addAttribute("status", statusLabel(doc.status()));

        DocumentVersion currentVersion = doc.currentVersion() > 0 && !doc.versions().isEmpty()
                ? doc.versions().get(doc.versions().size() - 1)
                : null;
        model.addAttribute("fileName", currentVersion != null && currentVersion.fileName() != null
                ? currentVersion.fileName() : "—");
        model.addAttribute("contentType", currentVersion != null && currentVersion.contentType() != null
                ? currentVersion.contentType() : "—");
        model.addAttribute("fileSize", currentVersion != null
                ? formatFileSize(currentVersion.sizeBytes()) : "—");
        model.addAttribute("checksum", currentVersion != null && currentVersion.checksumSha256() != null
                ? currentVersion.checksumSha256() : "—");
        model.addAttribute("provenance", jobs != null && !jobs.isEmpty() && jobs.get(0).sourceType() != null
                ? jobs.get(0).sourceType() : "upload");
        model.addAttribute("createdAt", doc.createdAt() != null
                ? DATE_FMT.format(doc.createdAt().atZone(ZoneId.systemDefault())) : "—");
        model.addAttribute("jobs", jobs);
        model.addAttribute("pageTitle", doc.metadata().title());
        model.addAttribute("activeSection", "documents");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Dokumente", "/documents"),
                new HomeController.Breadcrumb(doc.metadata().title(), "/documents/" + id)));

        return "documents/detail";
    }

    /**
     * Validiertes lokales Rückkehr-Ziel: Referer-Pfad nur dann übernehmen,
     * wenn (a) kein fremder Host (Same-Origin) und (b) der Pfad mit einem
     * erlaubten lokalen Präfix beginnt. Ergebnis ist immer ein lokaler,
     * relativer Pfad — niemals eine externe URL.
     */
    private String safeBackTarget(String referer, jakarta.servlet.http.HttpServletRequest request,
                                  String docId) {
        if (referer == null || referer.isBlank()) {
            return "/documents";
        }
        try {
            var uri = URI.create(referer.trim());
            String host = request.getHeader("Host");
            if (uri.getHost() != null && host != null
                    && !uri.getHost().equalsIgnoreCase(host)) {
                return "/documents";
            }
            String path = uri.getPath() != null ? uri.getPath() : "";
            // Seiten-Refresh dieser Metadaten-Seite: Referer ist sie selbst —
            // zurück geht es dann in die Übersicht, nicht in eine Schleife.
            if (path.equals("/documents/" + docId)) {
                return "/documents";
            }
            for (String prefix : BACK_TARGET_PREFIXES) {
                if (path.startsWith(prefix)) {
                    return uri.getQuery() != null && !uri.getQuery().isBlank()
                            ? path + "?" + uri.getQuery() : path;
                }
            }
            return "/documents";
        } catch (Exception e) {
            return "/documents";
        }
    }

    // ── Helpers ──

    /** Loads the current document rows (DELETED excluded) into the model. */
    private void addDocumentRows(Model model, String status, String q) {
        var filter = new reasoning.document.api.DocumentFilter(
                status != null && !status.isEmpty() ? DocumentStatus.valueOf(status) : null,
                null, null, null, null, null, null, 0, 200);
        var result = documentFacade.findDocuments(filter);

        List<DocumentRow> rows = new ArrayList<>();
        for (Document doc : result.documents()) {
            if (doc.status() == DocumentStatus.DELETED) continue;
            IngestionJobFilter jobFilter = new IngestionJobFilter(doc.id(), null, null, 0, 1);
            String ingestionStatus;
            try {
                var jobs = documentFacade.findIngestionJobs(jobFilter);
                ingestionStatus = !jobs.jobs().isEmpty()
                        ? jobs.jobs().get(0).status().name() : "NONE";
            } catch (Exception e) {
                ingestionStatus = "NONE";
            }

            rows.add(new DocumentRow(
                    doc.id(), doc.metadata().title(),
                    doc.metadata().type() != null ? doc.metadata().type().name() : "—",
                    doc.metadata().category() != null ? categoryLabel(doc.metadata().category()) : "—",
                    statusLabel(doc.status()), statusVariant(doc.status()),
                    ingestionStatusLabel(ingestionStatus),
                    doc.currentVersion(),
                    formatFileSize(doc.currentVersion() > 0
                            ? doc.versions().get(doc.versions().size() - 1).sizeBytes() : 0),
                    doc.createdAt() != null
                            ? DATE_FMT.format(doc.createdAt().atZone(ZoneId.systemDefault())) : "—"));
        }

        if (q != null && !q.isEmpty()) {
            String lower = q.toLowerCase();
            rows = rows.stream()
                    .filter(r -> r.title().toLowerCase().contains(lower))
                    .toList();
        }

        model.addAttribute("documents", rows);
        model.addAttribute("statusFilter", status != null ? status : "");
        model.addAttribute("searchQuery", q != null ? q : "");
    }

    private Document getDocumentOr404(UUID docId, AuthenticatedUser user) {
        try {
            return documentFacade.getDocument(docId, user.email());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nicht gefunden");
        }
    }

    /**
     * Reduces the multipart filename to its bare file name. Directory uploads
     * (input webkitdirectory) deliver the folder-relative path as the
     * multipart filename ("ordner\\datei.pdf"); a path separator inside the
     * storage key would resolve into a nonexistent subdirectory and fail the
     * write. Normal multi-file uploads are unaffected.
     */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String normalized = raw.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
    }

    private DocumentFileType detectFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return DocumentFileType.PDF;
        if (lower.endsWith(".docx")) return DocumentFileType.DOCX;
        if (lower.endsWith(".txt")) return DocumentFileType.TXT;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return DocumentFileType.HTML;
        return DocumentFileType.TXT;
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    // ── Label Helpers ──

    public static String statusLabel(DocumentStatus s) {
        return switch (s) {
            case DRAFT -> "Entwurf";
            case INGESTION_PENDING -> "Indexierung ausstehend";
            case INGESTING -> "Wird indexiert";
            case READY -> "Bereit";
            case FAILED -> "Fehlgeschlagen";
            case ARCHIVED -> "Archiviert";
            case OBSOLETE -> "Veraltet";
            case DELETED -> "Gelöscht";
        };
    }

    public static String statusVariant(DocumentStatus s) {
        return switch (s) {
            case READY -> "success";
            case INGESTING, INGESTION_PENDING -> "warning";
            case FAILED -> "error";
            case OBSOLETE, ARCHIVED -> "neutral";
            default -> "neutral";
        };
    }

    public static String ingestionStatusLabel(String s) {
        return switch (s) {
            case "PENDING" -> "Ausstehend";
            case "RUNNING" -> "Läuft";
            case "COMPLETED" -> "Abgeschlossen";
            case "FAILED" -> "Fehlgeschlagen";
            case "CANCELLED" -> "Abgebrochen";
            // No ingestion job / no index records — the source file may still
            // exist, but the document is not indexed.
            default -> "Nicht indiziert";
        };
    }

    public static String categoryLabel(String category) {
        if (category == null) return "—";
        return switch (category) {
            case "CONTRACT" -> "Vertrag";
            case "CORRESPONDENCE" -> "Schriftverkehr";
            case "REPORT" -> "Bericht";
            case "INVOICE" -> "Rechnung";
            case "TECHNICAL_SPEC" -> "Technische Spezifikation";
            case "RESEARCH_PAPER" -> "Forschungsarbeit";
            case "MEETING_MINUTES" -> "Sitzungsprotokoll";
            case "POLICY_DOCUMENT" -> "Richtlinie";
            case "MANUAL" -> "Handbuch";
            case "PRESENTATION" -> "Präsentation";
            case "DATASET" -> "Datensatz";
            case "OTHER" -> "Sonstiges";
            default -> category;
        };
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Row DTO for template rendering. */
    public record DocumentRow(UUID id, String title, String type, String category,
                               String statusLabel, String statusVariant,
                               String ingestionStatus, int version,
                               String fileSize, String createdAt) {}
}
