package reasoning.ai.analytics;

public record StageHealth(
        String stageName,
        String status,
        double avgLatencyMs,
        double failureRate,
        String trend,
        int sampleCount,
        long errorCount,
        long warningCount
) {}
