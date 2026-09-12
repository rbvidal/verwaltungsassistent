package reasoning.ai.analytics;

import java.util.List;
import java.util.Map;

public record PipelineHealthReport(
        long totalRuns,
        double avgConfidence,
        double avgLatencyMs,
        double verificationSuccessRate,
        double repairFrequency,
        double repairSuccessRate,
        double judgeOverrideRate,
        double avgEvidenceCoverage,
        String mostCommonRepairReason,
        List<StageHealth> stages,
        StageHealth bottleneck,
        List<Double> confidenceTrend,
        Map<String, Long> routingDistribution,
        Map<String, Long> knowledgeTableUsage,
        Map<String, Long> outcomeDistribution
) {}
