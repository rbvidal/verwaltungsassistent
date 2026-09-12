package reasoning.document.application;

import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.document.api.DocumentPermissionHook;
import reasoning.document.infrastructure.persistence.DocumentEntity;
import reasoning.document.infrastructure.persistence.IngestionJobEntity;
import reasoning.document.infrastructure.persistence.JpaDocumentEntityRepository;
import reasoning.document.infrastructure.persistence.JpaIngestionJobEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Delete/ingestion race regression tests: an ingestion job that completes or
 * fails AFTER the document was deleted must not resurrect the document
 * status — deletion is final.
 */
class DocumentDeleteRaceTest {

    private JpaDocumentEntityRepository documents;
    private DocumentService service;
    private DocumentEntity deleted;

    private final UUID jobId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documents = mock(JpaDocumentEntityRepository.class);
        JpaIngestionJobEntityRepository ingestionJobs = mock(JpaIngestionJobEntityRepository.class);
        DocumentPermissionHook permissionHook = mock(DocumentPermissionHook.class);
        DocumentAuditPublisher auditPublisher = mock(DocumentAuditPublisher.class);
        service = new DocumentService(documents, ingestionJobs, permissionHook, auditPublisher);

        deleted = new DocumentEntity("tenant", "Test", DocumentFileType.PDF, "OTHER",
                Set.of(), "INTERNAL", "actor");
        deleted.markStatus(DocumentStatus.DELETED, "actor");
        when(documents.findById(any())).thenReturn(Optional.of(deleted));

        IngestionJobEntity job = new IngestionJobEntity(docId, "upload", "actor", "tenant", 1);
        when(ingestionJobs.findById(any())).thenReturn(Optional.of(job));
    }

    @Test
    void completeIngestion_mustNotResurrectDeletedDocument() {
        service.startIngestion(jobId, "actor");
        service.completeIngestion(jobId, "actor");

        assertEquals(DocumentStatus.DELETED, deleted.getStatus(),
                "a deleted document must stay deleted after its job completes");
    }

    @Test
    void failIngestion_mustNotResurrectDeletedDocument() {
        service.startIngestion(jobId, "actor");
        service.failIngestion(jobId, "actor", "extraction failed");

        assertEquals(DocumentStatus.DELETED, deleted.getStatus(),
                "a deleted document must stay deleted after its job fails");
    }

    @Test
    void startIngestion_mustNotResurrectDeletedDocument() {
        service.startIngestion(jobId, "actor");

        assertEquals(DocumentStatus.DELETED, deleted.getStatus(),
                "a deleted document must stay deleted when a pending job starts");
    }
}
