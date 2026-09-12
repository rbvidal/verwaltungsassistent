package verwaltungsassistent.web.knowledge;

import reasoning.common.model.DocumentStatus;
import reasoning.common.model.IngestionStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.infrastructure.persistence.IngestionJobEntity;
import reasoning.document.infrastructure.persistence.JpaIngestionJobEntityRepository;
import reasoning.document.model.Document;
import reasoning.workspace.api.KnowledgeExtractionJobEntity;
import reasoning.workspace.infrastructure.persistence.JpaKnowledgeExtractionJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Durable worker behavior: reconciliation, no duplication, retry policy,
 * terminal failure, empty-safe operation, flag-off no-op.
 */
class KnowledgeExtractionWorkerTest {

    private JpaIngestionJobEntityRepository ingestionJobs;
    private JpaKnowledgeExtractionJobRepository extractionJobs;
    private DocumentFacade documents;
    private StructuredKnowledgeExtractionService service;
    private KnowledgeExtractionWorker worker;

    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ingestionJobs = mock(JpaIngestionJobEntityRepository.class);
        extractionJobs = mock(JpaKnowledgeExtractionJobRepository.class);
        documents = mock(DocumentFacade.class);
        service = mock(StructuredKnowledgeExtractionService.class);
        worker = new KnowledgeExtractionWorker(ingestionJobs, extractionJobs, documents, service, true);

        Document doc = new Document(docId, null, null, DocumentStatus.READY, 1,
                "system", null, Instant.now(), Instant.now(), null, null, null, null, List.of());
        when(documents.getDocument(docId, "system")).thenReturn(doc);
    }

    private IngestionJobEntity completedIngestion() {
        IngestionJobEntity job = new IngestionJobEntity(docId, "PDF", "system", "t1", 1);
        job.start();
        job.complete();
        return job;
    }

    @Test
    void completedIngestionWithoutExtractionJobIsReconciledToPending() {
        when(ingestionJobs.findTop50ByStatusOrderByCreatedAtAsc(IngestionStatus.COMPLETED))
                .thenReturn(List.of(completedIngestion()));
        when(extractionJobs.findByDocumentIdAndDocumentVersion(docId, 1))
                .thenReturn(Optional.empty());

        worker.enqueueReconciliation();

        ArgumentCaptor<KnowledgeExtractionJobEntity> captor =
                ArgumentCaptor.forClass(KnowledgeExtractionJobEntity.class);
        verify(extractionJobs).save(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(docId, captor.getValue().getDocumentId());
        assertEquals(1, captor.getValue().getDocumentVersion());
    }

    @Test
    void existingExtractionJobIsNotDuplicated() {
        when(ingestionJobs.findTop50ByStatusOrderByCreatedAtAsc(IngestionStatus.COMPLETED))
                .thenReturn(List.of(completedIngestion()));
        when(extractionJobs.findByDocumentIdAndDocumentVersion(docId, 1))
                .thenReturn(Optional.of(new KnowledgeExtractionJobEntity(
                        UUID.randomUUID(), docId, 1)));

        worker.enqueueReconciliation();

        verify(extractionJobs, never()).save(any(KnowledgeExtractionJobEntity.class));
    }

    @Test
    void failedJobIsNotReenqueuedByReconciliation() {
        when(ingestionJobs.findTop50ByStatusOrderByCreatedAtAsc(IngestionStatus.COMPLETED))
                .thenReturn(List.of(completedIngestion()));
        KnowledgeExtractionJobEntity failed = new KnowledgeExtractionJobEntity(UUID.randomUUID(), docId, 1);
        failed.markFailed("permanent");
        when(extractionJobs.findByDocumentIdAndDocumentVersion(docId, 1))
                .thenReturn(Optional.of(failed));

        worker.enqueueReconciliation();

        verify(extractionJobs, never()).save(any(KnowledgeExtractionJobEntity.class));
    }

    @Test
    void successfulExtractionMarksJobCompleted() {
        KnowledgeExtractionJobEntity job = new KnowledgeExtractionJobEntity(UUID.randomUUID(), docId, 1);
        when(extractionJobs.findTop5ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(job));

        worker.processPending();

        verify(service).extract(docId);
        ArgumentCaptor<KnowledgeExtractionJobEntity> captor =
                ArgumentCaptor.forClass(KnowledgeExtractionJobEntity.class);
        verify(extractionJobs, times(2)).save(captor.capture());
        assertEquals("COMPLETED", captor.getAllValues().getLast().getStatus());
    }

    @Test
    void failureRetriesUpToLimitThenFails() {
        when(service.extract(docId)).thenThrow(new RuntimeException("ollama down"));
        KnowledgeExtractionJobEntity job = new KnowledgeExtractionJobEntity(UUID.randomUUID(), docId, 1);

        when(extractionJobs.findTop5ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(job));
        worker.processPending(); // attempt 1
        assertEquals("PENDING", lastSavedStatus(), "retryable failure returns to PENDING");
        assertEquals(1, lastSaved().getAttempts());

        worker.processPending(); // attempt 2
        assertEquals("PENDING", lastSavedStatus());

        worker.processPending(); // attempt 3 → terminal FAILED
        assertEquals("FAILED", lastSavedStatus());
        assertEquals(3, lastSaved().getAttempts());
    }

    @Test
    void failedJobDoesNotRetryIndefinitely() {
        when(service.extract(docId)).thenThrow(new RuntimeException("down"));
        KnowledgeExtractionJobEntity job = new KnowledgeExtractionJobEntity(UUID.randomUUID(), docId, 1);
        // The mock repository mirrors the DB filter: only PENDING jobs are claimable.
        when(extractionJobs.findTop5ByStatusOrderByCreatedAtAsc("PENDING"))
                .thenAnswer(inv -> "PENDING".equals(job.getStatus()) ? List.of(job) : List.of());

        for (int i = 0; i < 10; i++) {
            worker.processPending();
        }

        verify(service, times(3)).extract(docId);
        assertEquals("FAILED", lastSavedStatus());
    }

    @Test
    void noEligibleJobsIsSafe() {
        when(ingestionJobs.findTop50ByStatusOrderByCreatedAtAsc(IngestionStatus.COMPLETED))
                .thenReturn(List.of());
        when(extractionJobs.findTop5ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of());

        worker.poll();

        verify(extractionJobs, never()).save(any(KnowledgeExtractionJobEntity.class));
        verify(service, never()).extract(any(UUID.class));
    }

    @Test
    void flagOffMeansWorkerIsInactive() {
        KnowledgeExtractionWorker disabled =
                new KnowledgeExtractionWorker(ingestionJobs, extractionJobs, documents, service, false);

        disabled.poll();

        verify(ingestionJobs, never()).findTop50ByStatusOrderByCreatedAtAsc(any());
        verify(extractionJobs, never()).findTop5ByStatusOrderByCreatedAtAsc(any());
        verify(service, never()).extract(any(UUID.class));
    }

    @Test
    void restartReconciliationConsistentWithDurableDesign() {
        // Durable completion: an extraction job left COMPLETED before a restart is
        // not re-enqueued; a PENDING job (crash mid-run) is not duplicated either —
        // it is simply reprocessed by the next poll (covered by
        // successfulExtractionMarksJobCompleted).
        when(ingestionJobs.findTop50ByStatusOrderByCreatedAtAsc(IngestionStatus.COMPLETED))
                .thenReturn(List.of(completedIngestion()));
        KnowledgeExtractionJobEntity completed = new KnowledgeExtractionJobEntity(UUID.randomUUID(), docId, 1);
        completed.markRunning();
        completed.markCompleted();
        when(extractionJobs.findByDocumentIdAndDocumentVersion(docId, 1))
                .thenReturn(Optional.of(completed));

        worker.enqueueReconciliation();

        verify(extractionJobs, never()).save(any(KnowledgeExtractionJobEntity.class));
    }

    private KnowledgeExtractionJobEntity lastSaved() {
        ArgumentCaptor<KnowledgeExtractionJobEntity> captor =
                ArgumentCaptor.forClass(KnowledgeExtractionJobEntity.class);
        verify(extractionJobs, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().getLast();
    }

    private String lastSavedStatus() {
        return lastSaved().getStatus();
    }
}
