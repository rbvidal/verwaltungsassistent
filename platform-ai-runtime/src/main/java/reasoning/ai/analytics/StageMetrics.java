package reasoning.ai.analytics;

import java.util.*;

/**
 * Immutable metrics for a single pipeline stage.
 */
public record StageMetrics(
        String stageName,
        long durationMs,
        String status,     // "ok", "warning", "error"
        List<String> warnings,
        List<String> errors,
        Map<String, Object> inputs,
        Map<String, Object> outputs
) {
    public StageMetrics {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public static StageMetrics ok(String name, long durationMs) {
        return new StageMetrics(name, durationMs, "ok", List.of(), List.of(), Map.of(), Map.of());
    }

    public static StageMetrics warn(String name, long durationMs, String warning) {
        return new StageMetrics(name, durationMs, "warning", List.of(warning), List.of(), Map.of(), Map.of());
    }

    public static StageMetrics error(String name, long durationMs, String error) {
        return new StageMetrics(name, durationMs, "error", List.of(), List.of(error), Map.of(), Map.of());
    }
}
