package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import reasoning.common.model.DocumentCategory;
import reasoning.common.model.DocumentFileType;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.model.Document;
import reasoning.workspace.api.AttachDocumentCommand;
import reasoning.workspace.application.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2C.3a: übernimmt E-Mail-Anhänge als normale Fall-Dokumente über die
 * BESTEHENDE Dokument-Pipeline ({@code createDocument} → Ingestion-Job →
 * {@code startIngestion} → {@code ingest} → {@code completeIngestion}), exakt
 * nach dem Upload-Muster des {@code DocumentController}.
 *
 * <p>Provenienz: Die Anhänge erhalten Tags ({@code email:<messageId>},
 * {@code email-attach:<messageId>:<partId>}, {@code sha256:<checksum>}) — das
 * kleinste bestehende Provenienz- und Deduplizierungsmittel der Plattform
 * (Tag-Suche über {@link DocumentFilter}). Idempotent: Ein bereits übernommener
 * Anhang wird beim erneuten Import übersprungen. Fehlerisoliert: Ein
 * nicht unterstützter oder defekter Anhang wird protokolliert und gezählt, ohne
 * den E-Mail-Import oder andere Anhänge zu beeinträchtigen. Die E-Mail wird
 * NIE automatisch einem Fall zugewiesen; die Fall-Verknüpfung der Dokumente
 * erfolgt erst, wenn die Mitarbeiterin aus der E-Mail einen Vorgang anlegt.</p>
 */
@Service
@Profile({"demo", "playwright"})
public class MailboxAttachmentDocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MailboxAttachmentDocumentIngestionService.class);

    static final String TAG_EMAIL_PREFIX = "email:";
    static final String TAG_ATTACH_PREFIX = "email-attach:";
    static final String TAG_SHA_PREFIX = "sha256:";

    private final DocumentFacade documentFacade;
    private final reasoning.document.api.DocumentIngestionProcessor ingestionProcessor;
    private final WorkspaceService workspaceService;
    private final Path uploadDir;

    public MailboxAttachmentDocumentIngestionService(DocumentFacade documentFacade,
                                                     reasoning.document.api.DocumentIngestionProcessor ingestionProcessor,
                                                     WorkspaceService workspaceService,
                                                     @Value("${app.upload-dir:uploads}") String uploadDirPath) {
        this.documentFacade = documentFacade;
        this.ingestionProcessor = ingestionProcessor;
        this.workspaceService = workspaceService;
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create upload directory: " + this.uploadDir, e);
        }
    }

    /** Ergebnis der Anhang-Übernahme: übernommene und fehlgeschlagene Anhänge. */
    public record AttachmentIngestionResult(int imported, int failed) {
        public boolean hasImports() {
            return imported > 0;
        }
    }

    /** Ein übernommener E-Mail-Anhang (Dokument-Referenz für die Provenienz-
     *  Sicht; Titel = Dateiname bzw. Dokumenttitel). */
    public record EmailAttachmentRef(String documentId, String title) {}

    /** Alle aus einer E-Mail übernommenen Anhang-Dokumente (Provenienz-Tag
     *  {@code email:<messageId>}, idempotente Anzeige — keine Neuanlage). */
    public List<EmailAttachmentRef> attachmentsOf(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return List.of();
        }
        try {
            var result = documentFacade.findDocuments(new DocumentFilter(
                    null, null, null, TAG_EMAIL_PREFIX + messageId, null, null, null, 0, 100));
            if (result == null || result.documents() == null) {
                return List.of();
            }
            return result.documents().stream()
                    .map(d -> new EmailAttachmentRef(
                            d.id().toString(),
                            d.metadata() != null && d.metadata().title() != null
                                    && !d.metadata().title().isBlank()
                                            ? d.metadata().title() : d.id().toString()))
                    .toList();
        } catch (Exception e) {
            log.debug("Anhänge von E-Mail {} nicht lesbar: {}", messageId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Übernimmt die Anhänge einer Nachricht als Dokumente. {@code workspaceId}
     * ist {@code null}, solange die E-Mail keinem Fall zugeordnet ist — die
     * Fall-Verknüpfung erfolgt über {@link #attachEmailDocumentsToWorkspace}.
     */
    public AttachmentIngestionResult ingestAttachments(IncomingMessage message, String workspaceId) {
        if (message.attachments() == null || message.attachments().isEmpty()) {
            return new AttachmentIngestionResult(0, 0);
        }
        int imported = 0;
        int failed = 0;
        String messageId = message.messageId() != null ? message.messageId() : "";
        for (IncomingMessage.Attachment attachment : message.attachments()) {
            try {
                if (alreadyImported(messageId, attachment)) {
                    continue;
                }
                Document doc = importAttachment(message, attachment, messageId);
                if (workspaceId != null && !workspaceId.isBlank()) {
                    attachToWorkspaceIfNeeded(doc, workspaceId);
                }
                imported++;
            } catch (Exception e) {
                failed++;
                log.error("Anhang '{}' aus E-Mail '{}' konnte nicht als Dokument übernommen werden: {}",
                        attachment.filename(), message.subject(), e.getMessage(), e);
            }
        }
        return new AttachmentIngestionResult(imported, failed);
    }

    /**
     * Hängt alle Anhang-Dokumente einer E-Mail an einen Fall (nach "Vorgang aus
     * E-Mail anlegen"): Provenienz-Tag {@code email:<messageId>} → Dokumente →
     * Verknüpfung, sofern nicht bereits verknüpft (idempotent).
     */
    public void attachEmailDocumentsToWorkspace(String messageId, String workspaceId) {
        if (messageId == null || messageId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        var result = documentFacade.findDocuments(new DocumentFilter(
                null, null, null, TAG_EMAIL_PREFIX + messageId, null, null, null, 0, 100));
        for (Document doc : result.documents()) {
            try {
                attachToWorkspaceIfNeeded(doc, workspaceId);
            } catch (Exception e) {
                log.warn("Anhang-Dokument {} konnte nicht an Fall {} gehängt werden: {}",
                        doc.id(), workspaceId, e.getMessage());
            }
        }
    }

    /** Deduplizierung: Teil-Identität der E-Mail (messageId + partId) bereits übernommen? */
    private boolean alreadyImported(String messageId, IncomingMessage.Attachment attachment) {
        var result = documentFacade.findDocuments(new DocumentFilter(null, null, null,
                TAG_ATTACH_PREFIX + messageId + ":" + attachment.partId(), null, null, null, 0, 1));
        return !result.documents().isEmpty();
    }

    private Document importAttachment(IncomingMessage message, IncomingMessage.Attachment attachment,
                                      String messageId) throws IOException {
        String fileName = sanitizeFileName(attachment.filename());
        String lower = fileName.toLowerCase();
        if (!(lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt")
                || lower.endsWith(".html") || lower.endsWith(".htm"))) {
            throw new IOException("nicht unterstütztes Dateiformat: " + fileName);
        }
        String storageKey = UUID.randomUUID() + "_" + fileName;
        Files.write(uploadDir.resolve(storageKey), attachment.bytes());
        String checksum = sha256(attachment.bytes());
        String actor = DemoMailboxServer.MAILBOX_USER;
        Document doc = documentFacade.createDocument(new CreateDocumentCommand(
                fileName, detectFileType(fileName), fileName,
                attachment.contentType() != null && !attachment.contentType().isBlank()
                        ? attachment.contentType() : "application/octet-stream",
                attachment.bytes().length, "local", storageKey, checksum,
                "OTHER", provenanceTags(messageId, attachment, checksum), "INTERNAL", actor, "default"));
        try {
            // 2D.16: kein Doppel-Job, wenn für das frisch angelegte Dokument
            // bereits eine Indexierung läuft (z. B. paralleler Import-Pfad).
            if (hasActiveIngestionJob(doc.id())) {
                log.info("Anhang-Dokument '{}' wird bereits indexiert — kein zweiter Job (2D.16)", fileName);
            } else {
                var job = documentFacade.createIngestionJob(doc.id(), actor);
                documentFacade.startIngestion(job.id(), actor);
                ingestionProcessor.ingest(doc.id());
                documentFacade.completeIngestion(job.id(), actor);
            }
        } catch (Exception e) {
            log.warn("Anhang-Dokument '{}' angelegt, Indexierung fehlgeschlagen: {}", fileName, e.getMessage());
        }
        log.info("E-Mail-Anhang als Dokument übernommen: {} (messageId={}, partId={})",
                fileName, messageId, attachment.partId());
        return doc;
    }

    /** Läuft für dieses Dokument bereits ein Indexierungs-Job (PENDING/RUNNING)? */
    private boolean hasActiveIngestionJob(java.util.UUID documentId) {
        try {
            var page = documentFacade.findIngestionJobs(
                    new reasoning.document.api.IngestionJobFilter(
                            documentId, null, null, 0, 10));
            if (page == null || page.jobs() == null) {
                return false;
            }
            for (var job : page.jobs()) {
                String status = String.valueOf(job.status()).toUpperCase();
                if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Job-Prüfung für {} nicht möglich: {}", documentId, e.getMessage());
        }
        return false;
    }

    private Set<String> provenanceTags(String messageId, IncomingMessage.Attachment attachment, String checksum) {
        return Set.of(
                TAG_EMAIL_PREFIX + messageId,
                TAG_ATTACH_PREFIX + messageId + ":" + attachment.partId(),
                TAG_SHA_PREFIX + checksum);
    }

    private void attachToWorkspaceIfNeeded(Document doc, String workspaceId) {
        boolean linked = workspaceService.getWorkspaceDocuments(workspaceId).stream()
                .anyMatch(l -> l.getDocumentId() != null && l.getDocumentId().equals(doc.id().toString()));
        if (!linked) {
            workspaceService.attachDocument(new AttachDocumentCommand(
                    workspaceId, doc.id().toString(), documentCategoryOf(doc),
                    doc.metadata().category() != null ? doc.metadata().category() : "general",
                    "E-Mail-Anhang (automatisch übernommen)"));
            log.info("Anhang-Dokument {} an Fall {} gehängt", doc.id(), workspaceId);
        }
    }

    private static DocumentCategory documentCategoryOf(Document doc) {
        if (doc.metadata() != null && doc.metadata().category() != null) {
            try {
                return DocumentCategory.valueOf(doc.metadata().category());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return DocumentCategory.OTHER;
    }

    /** Reduziert den Dateinamen auf den Basisteil (Pfad-Trenner entfernen, wie beim Upload). */
    static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String normalized = raw.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
    }

    private static DocumentFileType detectFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return DocumentFileType.PDF;
        if (lower.endsWith(".docx")) return DocumentFileType.DOCX;
        if (lower.endsWith(".txt")) return DocumentFileType.TXT;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return DocumentFileType.HTML;
        return DocumentFileType.TXT;
    }

    static String sha256(byte[] data) {
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
}
