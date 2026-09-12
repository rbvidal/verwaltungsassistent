package reasoning.ai.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory ring buffer for pipeline run metrics.
 * Configurable maximum size. Replaceable by PostgreSQL later.
 */
@Repository
public class AnalyticsRepository {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRepository.class);

    private final List<PipelineRunMetrics> runs = new CopyOnWriteArrayList<>();
    private final int maxSize;

    public AnalyticsRepository(@Value("${analytics.max-runs:1000}") int maxSize) {
        this.maxSize = Math.max(10, maxSize);
    }

    public synchronized void store(PipelineRunMetrics metrics) {
        runs.add(metrics);
        while (runs.size() > maxSize) {
            runs.remove(0);
        }
    }

    public List<PipelineRunMetrics> allRuns() {
        return List.copyOf(runs);
    }

    public List<PipelineRunMetrics> lastN(int n) {
        List<PipelineRunMetrics> copy = allRuns();
        int from = Math.max(0, copy.size() - n);
        return copy.subList(from, copy.size());
    }

    public int size() {
        return runs.size();
    }

    public synchronized void clear() {
        runs.clear();
    }

    /** Returns runs since a given timestamp. */
    public List<PipelineRunMetrics> since(java.time.Instant since) {
        return runs.stream()
                .filter(r -> r.timestamp().isAfter(since))
                .toList();
    }
}
