package reasoning.document.application;
import reasoning.common.audit.AuditEventType;

import reasoning.common.audit.AuditEventType;
import reasoning.document.api.AddDocumentVersionCommand;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.DocumentPage;
import reasoning.document.api.DocumentPermissionHook;
import reasoning.document.api.IngestionJobFilter;
import reasoning.document.api.IngestionJobPage;
import reasoning.document.api.UpdateDocumentMetadataCommand;
import reasoning.document.infrastructure.persistence.DocumentEntity;
import reasoning.document.infrastructure.persistence.DocumentMapper;
import reasoning.document.infrastructure.persistence.DocumentSpecifications;
import reasoning.document.infrastructure.persistence.DocumentVersionEntity;
import reasoning.document.infrastructure.persistence.IngestionJobEntity;
import reasoning.document.infrastructure.persistence.IngestionJobSpecifications;
import reasoning.document.infrastructure.persistence.JpaDocumentEntityRepository;
import reasoning.document.infrastructure.persistence.JpaIngestionJobEntityRepository;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.DocumentFileType;
import reasoning.common.model.IngestionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Implementation of {@link DocumentFacade} managing full document lifecycle with audit events and ingestion jobs. */
@Service
public class DocumentService implements DocumentFacade {

    private static final int MAX_PAGE_SIZE = 200;

    private final JpaDocumentEntityRepository documents;
    private final JpaIngestionJobEntityRepository ingestionJobs;
    private final DocumentPermissionHook permissionHook;
    private final DocumentAuditPublisher auditPublisher;

    public DocumentService(
            JpaDocumentEntityRepository documents,
            JpaIngestionJobEntityRepository ingestionJobs,
            DocumentPermissionHook permissionHook,
            DocumentAuditPublisher auditPublisher
    ) {
        this.documents = documents;
        this.ingestionJobs = ingestionJobs;
        this.permissionHook = permissionHook;
        this.auditPublisher = auditPublisher;
    }

    @Override
    @Transactional
    public Document createDocument(CreateDocumentCommand command) {
        requireText(command.actorId(), "Actor is required");
        requireText(command.title(), "Title is required");
        requireText(command.fileName(), "File name is required");
        requireText(command.contentType(), "Content type is required");
        requireText(command.storageProvider(), "Storage provider is required");
        requireText(command.storageKey(), "Storage key is required");
        requirePositive(command.sizeBytes(), "Size must be positive");
        DocumentFileType type = requireType(command.type());

        DocumentEntity document = new DocumentEntity(
                trimToNull(command.tenantId()),
                command.title().trim(),
                type,
                trimToNull(command.category()),
                normalizeTags(command.tags()),
                defaultVisibility(command.visibility()),
                command.actorId());
        document.addVersion(newVersion(1, command), command.actorId());
        document.applyValidity(command.publishedAt(), command.validFrom(), command.validUntil(),
                command.supersededAt(), command.actorId());
        DocumentEntity saved = documents.saveAndFlush(document);
        ingestionJobs.save(new IngestionJobEntity(saved.getId(), type.name(), command.actorId(), saved.getTenantId(), nextSequenceNumber()));

        auditPublisher.emit(command.actorId(), saved.getTenantId(), AuditEventType.DOCUMENT_INGESTED, saved.getId(),
                Map.of("operation", "document_registered", "status", saved.getStatus().name()));
        return DocumentMapper.toModel(saved);
    }

    @Override
    @Transactional
    public Document getDocument(UUID documentId, String actorId) {
        permissionHook.verifyCanView(actorId, documentId);
        DocumentEntity document = findDocument(documentId);
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_VIEWED, document.getId(),
                Map.of("operation", "document_viewed"));
        return DocumentMapper.toModel(document);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentPage findDocuments(DocumentFilter filter) {
        DocumentFilter normalized = normalize(filter);
        Page<DocumentEntity> page = documents.findAll(
                DocumentSpecifications.from(normalized),
                PageRequest.of(normalized.page(), normalized.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        return new DocumentPage(
                page.getContent().stream().map(DocumentMapper::toModel).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @Override
    @Transactional
    public Document updateMetadata(UpdateDocumentMetadataCommand command) {
        requireText(command.actorId(), "Actor is required");
        requireText(command.title(), "Title is required");
        DocumentEntity document = findDocument(command.documentId());
        permissionHook.verifyCanManage(command.actorId(), command.documentId());
        document.updateMetadata(
                command.title().trim(),
                requireType(command.type()),
                trimToNull(command.category()),
                normalizeTags(command.tags()),
                defaultVisibility(command.visibility()),
                command.actorId());
        document.applyValidity(command.publishedAt(), command.validFrom(), command.validUntil(),
                command.supersededAt(), command.actorId());
        auditPublisher.emit(command.actorId(), document.getTenantId(), AuditEventType.DOCUMENT_UPDATED, document.getId(),
                Map.of("operation", "metadata_updated"));
        return DocumentMapper.toModel(document);
    }

    @Override
    @Transactional
    public Document addVersion(AddDocumentVersionCommand command) {
        requireText(command.actorId(), "Actor is required");
        requireText(command.fileName(), "File name is required");
        requireText(command.contentType(), "Content type is required");
        requireText(command.storageProvider(), "Storage provider is required");
        requireText(command.storageKey(), "Storage key is required");
        requirePositive(command.sizeBytes(), "Size must be positive");
        DocumentEntity document = findDocument(command.documentId());
        permissionHook.verifyCanManage(command.actorId(), command.documentId());
        int nextVersion = document.getCurrentVersion() + 1;
        document.addVersion(newVersion(nextVersion, command), command.actorId());
        document.markStatus(DocumentStatus.INGESTION_PENDING, command.actorId());
        ingestionJobs.save(new IngestionJobEntity(document.getId(), document.getType().name(), command.actorId(), document.getTenantId(), nextSequenceNumber()));
        auditPublisher.emit(command.actorId(), document.getTenantId(), AuditEventType.DOCUMENT_INGESTED, document.getId(),
                Map.of("operation", "document_version_added", "version", Integer.toString(nextVersion)));
        documents.flush();
        return DocumentMapper.toModel(document);
    }

    @Override
    @Transactional
    public Document archiveDocument(UUID documentId, String actorId) {
        requireText(actorId, "Actor is required");
        DocumentEntity document = findDocument(documentId);
        permissionHook.verifyCanManage(actorId, documentId);
        document.markStatus(DocumentStatus.ARCHIVED, actorId);
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_UPDATED, document.getId(),
                Map.of("operation", "document_archived"));
        return DocumentMapper.toModel(document);
    }

    @Override
    @Transactional
    public Document markObsoleteDocument(UUID documentId, String actorId) {
        requireText(actorId, "Actor is required");
        DocumentEntity document = findDocument(documentId);
        permissionHook.verifyCanManage(actorId, documentId);
        document.markStatus(DocumentStatus.OBSOLETE, actorId);
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_UPDATED, document.getId(),
                Map.of("operation", "document_obsolete"));
        return DocumentMapper.toModel(document);
    }

    @Override
    @Transactional
    public Document deleteDocument(UUID documentId, String actorId) {
        requireText(actorId, "Actor is required");
        DocumentEntity document = findDocument(documentId);
        permissionHook.verifyCanManage(actorId, documentId);
        document.markStatus(DocumentStatus.DELETED, actorId);
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_DELETED, document.getId(),
                Map.of("operation", "document_soft_deleted"));
        return DocumentMapper.toModel(document);
    }

    @Override
    @Transactional
    public DocumentIngestionJob createIngestionJob(UUID documentId, String actorId) {
        requireText(actorId, "Actor is required");
        DocumentEntity document = findDocument(documentId);
        permissionHook.verifyCanManage(actorId, documentId);
        // createDocument already registers an ingestion job for every new
        // document. Callers that request another job (upload, reindex, seeder)
        // must not create a SECOND open job while one is pending or running —
        // two parallel ingestion runs duplicate chunks and vectors. Finished
        // jobs stay freely creatable (e.g. reindex after completion).
        Optional<IngestionJobEntity> open = ingestionJobs
                .findFirstByDocumentIdAndStatusInOrderByCreatedAtAsc(
                        documentId, List.of(IngestionStatus.PENDING, IngestionStatus.RUNNING));
        if (open.isPresent()) {
            return DocumentMapper.toModel(open.get());
        }
        document.markStatus(DocumentStatus.INGESTION_PENDING, actorId);
        IngestionJobEntity job = ingestionJobs.save(new IngestionJobEntity(document.getId(), document.getType().name(), actorId, document.getTenantId(), nextSequenceNumber()));
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_INGESTED, document.getId(),
                Map.of("operation", "ingestion_job_created", "jobId", job.getId().toString()));
        return DocumentMapper.toModel(job);
    }

    @Override
    @Transactional
    public DocumentIngestionJob startIngestion(UUID jobId, String actorId) {
        requireText(actorId, "Actor is required");
        IngestionJobEntity job = findJob(jobId);
        DocumentEntity document = findDocument(job.getDocumentId());
        permissionHook.verifyCanManage(actorId, document.getId());
        job.start();
        // A document deleted while the job was still pending must not be
        // flipped back to INGESTING.
        if (document.getStatus() != DocumentStatus.DELETED) {
            document.markStatus(DocumentStatus.INGESTING, actorId);
        }
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_INGESTED, document.getId(),
                Map.of("operation", "ingestion_started", "jobId", job.getId().toString()));
        return DocumentMapper.toModel(job);
    }

    @Override
    @Transactional
    public DocumentIngestionJob completeIngestion(UUID jobId, String actorId) {
        requireText(actorId, "Actor is required");
        IngestionJobEntity job = findJob(jobId);
        DocumentEntity document = findDocument(job.getDocumentId());
        permissionHook.verifyCanManage(actorId, document.getId());
        job.complete();
        // A document deleted while its ingestion job was still running must
        // stay deleted — the async completion must not resurrect it.
        if (document.getStatus() != DocumentStatus.DELETED) {
            document.markStatus(DocumentStatus.READY, actorId);
        }
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_INGESTED, document.getId(),
                Map.of("operation", "ingestion_completed", "jobId", job.getId().toString()));
        return DocumentMapper.toModel(job);
    }

    @Override
    @Transactional
    public DocumentIngestionJob failIngestion(UUID jobId, String actorId, String reason) {
        requireText(actorId, "Actor is required");
        requireText(reason, "Failure reason is required");
        IngestionJobEntity job = findJob(jobId);
        DocumentEntity document = findDocument(job.getDocumentId());
        permissionHook.verifyCanManage(actorId, document.getId());
        job.fail(reason.trim());
        // Same rule as completeIngestion: a deleted document is final.
        if (document.getStatus() != DocumentStatus.DELETED) {
            document.markStatus(DocumentStatus.FAILED, actorId);
        }
        auditPublisher.emit(actorId, document.getTenantId(), AuditEventType.DOCUMENT_UPDATED, document.getId(),
                Map.of("operation", "ingestion_failed", "jobId", job.getId().toString()));
        return DocumentMapper.toModel(job);
    }

    @Override
    @Transactional(readOnly = true)
    public IngestionJobPage findIngestionJobs(IngestionJobFilter filter) {
        IngestionJobFilter normalized = normalize(filter);
        Page<IngestionJobEntity> page = ingestionJobs.findAll(
                IngestionJobSpecifications.from(normalized),
                PageRequest.of(normalized.page(), normalized.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        return new IngestionJobPage(
                page.getContent().stream().map(DocumentMapper::toModel).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private DocumentEntity findDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("Document id is required");
        }
        return documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
    }

    private IngestionJobEntity findJob(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Ingestion job id is required");
        }
        return ingestionJobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion job not found"));
    }

    private DocumentVersionEntity newVersion(int versionNumber, CreateDocumentCommand command) {
        return new DocumentVersionEntity(
                versionNumber,
                command.fileName().trim(),
                command.contentType().trim(),
                command.sizeBytes(),
                command.storageProvider().trim(),
                command.storageKey().trim(),
                trimToNull(command.checksumSha256()),
                command.actorId());
    }

    private DocumentVersionEntity newVersion(int versionNumber, AddDocumentVersionCommand command) {
        return new DocumentVersionEntity(
                versionNumber,
                command.fileName().trim(),
                command.contentType().trim(),
                command.sizeBytes(),
                command.storageProvider().trim(),
                command.storageKey().trim(),
                trimToNull(command.checksumSha256()),
                command.actorId());
    }

    private DocumentFilter normalize(DocumentFilter filter) {
        if (filter == null) {
            return new DocumentFilter(null, null, null, null, null, null, null, 0, 50);
        }
        return new DocumentFilter(
                filter.status(),
                filter.type(),
                trimToNull(filter.category()),
                trimToNull(filter.tag()),
                trimToNull(filter.tenantId()),
                filter.createdFrom(),
                filter.createdTo(),
                Math.max(filter.page(), 0),
                normalizeSize(filter.size()));
    }

    private IngestionJobFilter normalize(IngestionJobFilter filter) {
        if (filter == null) {
            return new IngestionJobFilter(null, null, null, 0, 50);
        }
        return new IngestionJobFilter(
                filter.documentId(),
                filter.status(),
                trimToNull(filter.tenantId()),
                Math.max(filter.page(), 0),
                normalizeSize(filter.size()));
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 50;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Set<String> normalizeTags(Set<String> tags) {
        if (tags == null) {
            return Set.of();
        }
        return tags.stream()
                .filter(this::hasText)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String defaultVisibility(String visibility) {
        String normalized = trimToNull(visibility);
        return normalized == null ? "PRIVATE" : normalized;
    }

    private DocumentFileType requireType(DocumentFileType type) {
        if (type == null) {
            throw new IllegalArgumentException("Document type is required");
        }
        return type;
    }

    private void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requirePositive(long value, String message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private long nextSequenceNumber() {
        return ingestionJobs.findMaxSequenceNumber() + 1;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
