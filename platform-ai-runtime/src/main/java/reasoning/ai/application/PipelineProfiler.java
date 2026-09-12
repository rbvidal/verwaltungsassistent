package reasoning.ai.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Instruments every stage of the AI pipeline with precise timing.
 *
 * <p>Produces structured timing logs and exposes the current request's
 * full profile for developer dashboards and performance monitoring.
 */
@Component
public class PipelineProfiler {

    private static final Logger log = LoggerFactory.getLogger(PipelineProfiler.class);

    private final Map<String, StageTiming> currentProfile = new LinkedHashMap<>();
    private Instant pipelineStart;
    private String requestId;

    /** Starts profiling a new request. */
    public void start(String requestId) {
        this.requestId = requestId;
        this.pipelineStart = Instant.now();
        currentProfile.clear();
        record("pipeline-start", pipelineStart);
    }

    /** Records the end of a named stage. The start is the end of the previous stage. */
    public void record(String stageName) {
        record(stageName, Instant.now());
    }

    private void record(String stageName, Instant at) {
        Instant started = currentProfile.isEmpty() ? pipelineStart
                : currentProfile.values().stream()
                        .map(StageTiming::end)
                        .max(Instant::compareTo).orElse(pipelineStart);
        long ms = Duration.between(started, at).toMillis();
        currentProfile.put(stageName, new StageTiming(stageName, started, at, ms));
    }

    /** Finishes profiling and logs the full trace. */
    public void finish() {
        Instant end = Instant.now();
        record("total", end);

        long totalMs = Duration.between(pipelineStart, end).toMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════╗\n");
        sb.append("║  PIPELINE PROFILE  —  Request ").append(requestId).append("\n");
        sb.append("╠══════════════════════════════════════════════╣\n");

        for (StageTiming stage : currentProfile.values()) {
            if ("pipeline-start".equals(stage.name)) continue;
            String bar = bar(stage.ms, totalMs);
            sb.append(String.format("║  %-32s %5d ms  %s%n",
                    stage.name, stage.ms, bar));
        }

        sb.append("╠══════════════════════════════════════════════╣\n");
        sb.append(String.format("║  %-32s %5d ms%n", "TOTAL", totalMs));
        sb.append("╚══════════════════════════════════════════════╝");
        log.info(sb.toString());
    }

    /** Returns the current profile snapshot for dashboards. */
    public Map<String, StageTiming> getCurrentProfile() {
        return Map.copyOf(currentProfile);
    }

    public long totalMs() {
        return currentProfile.isEmpty() ? 0
                : Duration.between(pipelineStart, Instant.now()).toMillis();
    }

    private String bar(long ms, long total) {
        if (total == 0) return "";
        int width = Math.max(1, (int) (ms * 30 / total));
        return "█".repeat(Math.min(width, 30));
    }

    public record StageTiming(String name, Instant start, Instant end, long ms) {}
}
