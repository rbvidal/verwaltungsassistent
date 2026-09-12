package verwaltungsassistent.web.service;

import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.SourceCitation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory progress store for long-running AI operations (assistant
 * questions, decision analysis, corpus case evaluation). Each operation gets
 * a job id; the pipeline progress listener appends user-facing messages while
 * the operation runs in the background. Entries expire after a fixed time so
 * abandoned browser sessions cannot grow the store unboundedly.
 */
@Service
public class JobProgressService {

    /** The user-facing operation class; determines which German stage messages apply. */
    public enum Kind { ASSISTANT, ANALYSIS, EVALUATION, DATA_RESTORE, DATA_DELETE, DATASET }

    private static final Logger log = LoggerFactory.getLogger(JobProgressService.class);
    private static final int TTL_MINUTES = 15;

    private final ConcurrentHashMap<String, Job> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeByKey = new ConcurrentHashMap<>();

    /** Creates a new progress entry for the given operation. */
    public Job create(Kind kind, String title) {
        return create(kind, title, Instant.now());
    }

    /** Package-private test hook: creates an entry with a controlled creation time. */
    Job create(Kind kind, String title, Instant createdAt) {
        String jobId = UUID.randomUUID().toString();
        Job job = new Job(jobId, kind, title, createdAt);
        entries.put(jobId, job);
        return job;
    }

    public Job get(String jobId) {
        return jobId != null ? entries.get(jobId) : null;
    }

    /** Number of live entries (used by tests and diagnostics). */
    public int size() {
        return entries.size();
    }

    /** Registers a running job for an operation key (e.g. a case id) to prevent duplicate starts. */
    public void registerActive(String key, String jobId) {
        activeByKey.put(key, jobId);
    }

    /** Returns the still-running job registered for the key, or null. */
    public Job activeJob(String key) {
        String jobId = activeByKey.get(key);
        if (jobId == null) return null;
        Job job = entries.get(jobId);
        if (job != null && "RUNNING".equals(job.state)) return job;
        activeByKey.remove(key, jobId);
        return null;
    }

    public void unregisterActive(String key, String jobId) {
        activeByKey.remove(key, jobId);
    }

    /** Appends a user-facing progress message (order-preserving, duplicate-tolerant). */
    public void recordStage(String jobId, String message) {
        Job job = entries.get(jobId);
        if (job == null || message == null || message.isBlank()) return;
        job.messages.add(message);
    }

    /**
     * Records the raw pipeline stage key (ordered, deduplicated) together with
     * optional runtime data for the interactive pipeline visualization.
     * The data is observability-only and never influences the pipeline.
     */
    public void recordStageData(String jobId, String stage, Map<String, Object> data) {
        Job job = entries.get(jobId);
        if (job == null || stage == null) return;
        if (!job.stages.contains(stage)) {
            job.stages.add(stage);
        }
        if (data != null && !data.isEmpty()) {
            job.stageData.put(stage, data);
        }
    }

    public void complete(String jobId, Object outcome, String terminalMessage) {
        completeWithData(jobId, outcome, null, terminalMessage);
    }

    /** Like {@link #complete} but additionally stores structured outcome data (e.g. the decision model map). */
    public void completeWithData(String jobId, Object outcome, Object outcomeData, String terminalMessage) {
        Job job = entries.get(jobId);
        if (job == null) return;
        job.outcome = outcome;
        job.outcomeData = outcomeData;
        job.terminalMessage = terminalMessage;
        job.state = "DONE";
    }

    public void fail(String jobId) {
        fail(jobId, null);
    }

    /**
     * Marks a job as failed. An optional user-facing message is preserved and
     * shown instead of the generic failure text (used e.g. for capacity
     * rejections of the demo AI guard).
     */
    public void fail(String jobId, String message) {
        Job job = entries.get(jobId);
        if (job == null) return;
        job.state = "ERROR";
        if (message != null && !message.isBlank()) {
            job.errorMessage = message;
        }
    }

    /**
     * Removes only TERMINAL entries (DONE/ERROR) older than the TTL.
     * Running jobs are never expired merely because they are long-running —
     * the retention TTL applies only after the job reached a terminal state.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(TTL_MINUTES, ChronoUnit.MINUTES);
        entries.entrySet().removeIf(e -> {
            Job job = e.getValue();
            return !"RUNNING".equals(job.state) && job.createdAt.isBefore(cutoff);
        });
    }

    /** Live progress state for one operation. */
    public static final class Job {
        public final String jobId;
        public final Kind kind;
        public final String title;
        public final Instant createdAt;
        public final List<String> messages = new CopyOnWriteArrayList<>();
        /** Ordered raw pipeline stage keys (for the interactive visualization). */
        public final List<String> stages = new CopyOnWriteArrayList<>();
        /** Per-stage runtime data exposed to the visualization (observability only). */
        public final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> stageData =
                new java.util.concurrent.ConcurrentHashMap<>();
        public volatile String state = "RUNNING";
        public volatile String terminalMessage;
        /** Optional user-facing error message (e.g. AI capacity rejection); null = generic failure text. */
        public volatile String errorMessage;
        public volatile Object outcome;
        public volatile Object outcomeData;

        Job(String jobId, Kind kind, String title, Instant createdAt) {
            this.jobId = jobId;
            this.kind = kind;
            this.title = title;
            this.createdAt = createdAt;
        }
    }

    /** The final rendered answer data of a completed assistant request. */
    public record AssistantOutcome(
            String answerText,
            boolean grounded,
            String strategy,
            List<SourceCitation> citations,
            List<AuthorityReference> authorities,
            Integer confidencePct,
            boolean failClosed) {
    }
}
