package reasoning.document.application;

import reasoning.document.api.DocumentIngestionProcessor;
import reasoning.document.infrastructure.persistence.IngestionJobEntity;
import reasoning.document.infrastructure.persistence.JpaIngestionJobEntityRepository;
import reasoning.common.model.IngestionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Scheduled worker that polls for pending ingestion jobs and delegates to a {@link DocumentIngestionProcessor}. */
@Component
public class DocumentIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionWorker.class);

    private static final String NO_PROCESSOR_CONFIGURED =
            "Document ingestion processor is not configured. The semantic-indexing module currently indexes source code only and is not wired to uploaded documents.";

    private final JpaIngestionJobEntityRepository ingestionJobs;
    private final DocumentService documentService;
    private final ObjectProvider<DocumentIngestionProcessor> processorProvider;
    private final MeterRegistry meterRegistry;

    public DocumentIngestionWorker(
            JpaIngestionJobEntityRepository ingestionJobs,
            DocumentService documentService,
            ObjectProvider<DocumentIngestionProcessor> processorProvider,
            MeterRegistry meterRegistry
    ) {
        this.ingestionJobs = ingestionJobs;
        this.documentService = documentService;
        this.processorProvider = processorProvider;
        this.meterRegistry = meterRegistry;
    }

    /** Polls the top 10 pending ingestion jobs and processes each one. */
    @Scheduled(fixedDelayString = "${platform.document.ingestion.poll-delay-ms:10000}")
    public void processPendingJobs() {
        ingestionJobs.findTop10ByStatusOrderByCreatedAtAsc(IngestionStatus.PENDING)
                .forEach(this::processJob);
    }

    private void processJob(IngestionJobEntity job) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.debug("Starting ingestion job {}", job.getId());
        try {
            documentService.startIngestion(job.getId(), job.getRequestedBy());
            DocumentIngestionProcessor processor = Optional.ofNullable(processorProvider.getIfAvailable())
                    .orElseThrow(() -> new IllegalStateException(NO_PROCESSOR_CONFIGURED));
            processor.ingest(job.getDocumentId());
            documentService.completeIngestion(job.getId(), job.getRequestedBy());
            sample.stop(Timer.builder("ingestion.job.duration")
                    .description("Total ingestion job processing duration")
                    .tag("outcome", "success")
                    .register(meterRegistry));
            Counter.builder("ingestion.jobs.total")
                    .description("Total number of ingestion jobs processed")
                    .tag("outcome", "success")
                    .register(meterRegistry)
                    .increment();
            log.debug("Ingestion job {} completed successfully", job.getId());
        } catch (Exception ex) {
            sample.stop(Timer.builder("ingestion.job.duration")
                    .description("Total ingestion job processing duration")
                    .tag("outcome", "failure")
                    .register(meterRegistry));
            Counter.builder("ingestion.jobs.total")
                    .description("Total number of ingestion jobs processed")
                    .tag("outcome", "failure")
                    .register(meterRegistry)
                    .increment();
            Counter.builder("ingestion.errors.total")
                    .description("Total number of ingestion errors")
                    .tag("error_type", ex.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();
            log.debug("Ingestion job {} failed: {}", job.getId(), failureReason(ex));
            documentService.failIngestion(job.getId(), job.getRequestedBy(), failureReason(ex));
        }
    }

    private String failureReason(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
