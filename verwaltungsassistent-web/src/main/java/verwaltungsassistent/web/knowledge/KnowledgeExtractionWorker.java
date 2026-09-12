package verwaltungsassistent.web.knowledge;

import reasoning.common.model.IngestionStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.infrastructure.persistence.IngestionJobEntity;
import reasoning.document.infrastructure.persistence.JpaIngestionJobEntityRepository;
import reasoning.workspace.api.KnowledgeExtractionJobEntity;
import reasoning.workspace.infrastructure.persistence.JpaKnowledgeExtractionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Durable, asynchronous structured-knowledge extraction worker.
 *
 * <p>Follows the ingestion-worker pattern: a scheduled poll claims durable
 * jobs. Reconciliation enqueues a job for every successfully ingested
 * document version that has no extraction job yet — no coupling to the
 * ingestion pipeline itself. Extraction is fully independent: ingestion
 * completion never waits on the LLM, and failures here never affect
 * ingestion or RAG.
 *
 * <p>Disabled unless {@code knowledge.extraction.enabled=true}.
 */
@Component
public class KnowledgeExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractionWorker.class);

    private static final int MAX_ATTEMPTS = 3;

    private final JpaIngestionJobEntityRepository ingestionJobs;
    private final JpaKnowledgeExtractionJobRepository extractionJobs;
    private final DocumentFacade documents;
    private final StructuredKnowledgeExtractionService extractionService;
    private final boolean extractionEnabled;

    public KnowledgeExtractionWorker(
            JpaIngestionJobEntityRepository ingestionJobs,
            JpaKnowledgeExtractionJobRepository extractionJobs,
            DocumentFacade documents,
            StructuredKnowledgeExtractionService extractionService,
            @Value("${knowledge.extraction.enabled:false}") boolean extractionEnabled) {
        this.ingestionJobs = ingestionJobs;
        this.extractionJobs = extractionJobs;
        this.documents = documents;
        this.extractionService = extractionService;
        this.extractionEnabled = extractionEnabled;
    }

    @Scheduled(fixedDelayString = "${knowledge.extraction.poll-delay-ms:30000}")
    public void poll() {
        if (!extractionEnabled) {
            return;
        }
        enqueueReconciliation();
        processPending();
    }

    /** Completed ingestion jobs without an extraction job → PENDING extraction job. */
    void enqueueReconciliation() {
        List<IngestionJobEntity> completed = ingestionJobs.findTop50ByStatusOrderByCreatedAtAsc(
                IngestionStatus.COMPLETED);
        int enqueued = 0;
        for (IngestionJobEntity job : completed) {
            try {
                int version = documents.getDocument(job.getDocumentId(), "system").currentVersion();
                if (extractionJobs.findByDocumentIdAndDocumentVersion(job.getDocumentId(), version).isPresent()) {
                    continue;
                }
                extractionJobs.save(new KnowledgeExtractionJobEntity(
                        UUID.randomUUID(), job.getDocumentId(), version));
                enqueued++;
            } catch (Exception e) {
                log.warn("Reconciliation skipped ingestion job {}: {}", job.getId(), e.getMessage());
            }
        }
        if (enqueued > 0) {
            log.info("Knowledge extraction reconciliation: enqueued {} job(s)", enqueued);
        }
    }

    void processPending() {
        for (KnowledgeExtractionJobEntity job : extractionJobs.findTop5ByStatusOrderByCreatedAtAsc("PENDING")) {
            process(job);
        }
    }

    private void process(KnowledgeExtractionJobEntity job) {
        job.markRunning();
        extractionJobs.save(job);
        try {
            extractionService.extract(job.getDocumentId());
            job.markCompleted();
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Knowledge extraction failed for document {}@v{} (attempt {}): {}",
                    job.getDocumentId(), job.getDocumentVersion(), job.getAttempts(), reason);
            if (job.getAttempts() >= MAX_ATTEMPTS) {
                job.markFailed(reason);
            } else {
                job.markRetryable();
            }
        }
        extractionJobs.save(job);
    }
}
