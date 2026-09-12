package reasoning.ai.analytics;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides aggregated statistics over pipeline runs.
 * Read-only — never modifies pipeline execution.
 */
@Service
public class AnalyticsService {

    private final AnalyticsRepository repository;

    public AnalyticsService(AnalyticsRepository repository) {
        this.repository = repository;
    }

    // ── Overall ──

    public double avgConfidence() {
        return avg(PipelineRunMetrics::confidence);
    }

    public double avgLatencyMs() {
        return avg(m -> (double) m.totalLatencyMs());
    }

    public long totalRuns() {
        return repository.size();
    }

    // ── Retrieval ──

    public double avgRetrievalLatencyMs() {
        return avg(m -> (double) m.retrievalLatencyMs());
    }

    public double avgKeywordHits() {
        return avg(m -> (double) m.keywordHits());
    }

    public double avgVectorHits() {
        return avg(m -> (double) m.vectorHits());
    }

    public double avgMergedCandidates() {
        return avg(m -> (double) m.mergedCandidates());
    }

    public double retrievalDiversity() {
        // Average ratio of merged to total raw hits
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return 0;
        return runs.stream()
                .mapToDouble(m -> {
                    int total = m.keywordHits() + m.vectorHits() + m.graphHits();
                    return total > 0 ? (double) m.mergedCandidates() / total : 0;
                }).average().orElse(0);
    }

    // ── Evidence ──

    public double avgEvidenceCount() {
        return avg(m -> (double) m.evidenceCount());
    }

    public double avgEvidenceCoverage() {
        return avg(PipelineRunMetrics::evidenceCoverage);
    }

    public double avgCitations() {
        return avg(m -> (double) m.totalCitations());
    }

    // ── Prompt ──

    public double avgPromptSize() {
        return avg(m -> (double) m.promptTokens());
    }

    public double avgPromptLatencyMs() {
        return avg(m -> (double) m.promptLatencyMs());
    }

    // ── Verification ──

    public double verificationSuccessRate() {
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return 0;
        return (double) runs.stream().filter(PipelineRunMetrics::verificationPassed).count() / runs.size();
    }

    public String mostCommonVerificationFailure() {
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return "none";
        long unsupported = runs.stream().filter(m -> m.unsupportedFindings() > 0).count();
        long uncovered = runs.stream().filter(m -> m.evidenceCoverage() < 0.3).count();
        return unsupported > uncovered ? "unbelegte Feststellungen" : "geringe Abdeckung";
    }

    // ── Repair ──

    public double repairFrequency() {
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return 0;
        return (double) runs.stream().filter(PipelineRunMetrics::repairActivated).count() / runs.size();
    }

    public double repairSuccessRate() {
        List<PipelineRunMetrics> repaired = repository.allRuns().stream()
                .filter(PipelineRunMetrics::repairActivated).toList();
        if (repaired.isEmpty()) return 1.0;
        return (double) repaired.stream().filter(PipelineRunMetrics::repairSucceeded).count() / repaired.size();
    }

    public String mostCommonRepairReason() {
        Map<String, Long> counts = repository.allRuns().stream()
                .filter(PipelineRunMetrics::repairActivated)
                .map(PipelineRunMetrics::repairReason)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("none");
    }

    public double avgConfidenceGain() {
        return avg(m -> m.repairActivated() ? m.confidenceGain() : 0);
    }

    // ── Governance ──

    public double judgeOverrideRate() {
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return 0;
        return (double) runs.stream().filter(PipelineRunMetrics::repairedSelected).count() / runs.size();
    }

    // ── Routing ──

    public Map<String, Long> routingStrategyDistribution() {
        return repository.allRuns().stream()
                .collect(Collectors.groupingBy(PipelineRunMetrics::routeStrategy, Collectors.counting()));
    }

    public String mostCommonRoutingDecision() {
        return routingStrategyDistribution().entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");
    }

    // ── Knowledge ──

    public Map<String, Long> knowledgeTableUsage() {
        Map<String, Long> usage = new LinkedHashMap<>();
        for (PipelineRunMetrics m : repository.allRuns()) {
            for (String t : m.knowledgeTables()) {
                String key = t.length() > 40 ? t.substring(0, 40) : t;
                usage.merge(key, 1L, Long::sum);
            }
        }
        return usage;
    }

    // ── Domain ──

    public Map<String, Long> domainDistribution() {
        return repository.allRuns().stream()
                .collect(Collectors.groupingBy(PipelineRunMetrics::domain, Collectors.counting()));
    }

    // ── Outcome ──

    public Map<String, Long> outcomeDistribution() {
        return repository.allRuns().stream()
                .collect(Collectors.groupingBy(PipelineRunMetrics::outcome, Collectors.counting()));
    }

    // ── Stage Health ──

    public List<StageHealth> stageHealth() {
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return List.of();

        Map<String, List<StageMetrics>> byStage = new LinkedHashMap<>();
        for (PipelineRunMetrics m : runs) {
            for (StageMetrics s : m.stages()) {
                byStage.computeIfAbsent(s.stageName(), k -> new ArrayList<>()).add(s);
            }
        }

        List<StageHealth> health = new ArrayList<>();
        for (var entry : byStage.entrySet()) {
            List<StageMetrics> stages = entry.getValue();
            double avgMs = stages.stream().mapToLong(StageMetrics::durationMs).average().orElse(0);
            long errors = stages.stream().filter(s -> "error".equals(s.status())).count();
            long warnings = stages.stream().filter(s -> "warning".equals(s.status())).count();
            double failRate = (double) (errors + warnings) / stages.size();

            // Trend: compare first half vs second half
            int mid = stages.size() / 2;
            double firstHalf = stages.subList(0, mid).stream().mapToLong(StageMetrics::durationMs).average().orElse(0);
            double secondHalf = stages.subList(mid, stages.size()).stream().mapToLong(StageMetrics::durationMs).average().orElse(0);
            String trend = secondHalf < firstHalf * 0.9 ? "improving" :
                    secondHalf > firstHalf * 1.1 ? "degrading" : "stable";

            String status = failRate < 0.02 ? "GOOD" : failRate < 0.10 ? "WARNING" : "CRITICAL";

            health.add(new StageHealth(entry.getKey(), status, avgMs, failRate, trend,
                    stages.size(), errors, warnings));
        }
        return health;
    }

    // ── Full Report ──

    public PipelineHealthReport buildReport() {
        List<PipelineRunMetrics> runs = repository.allRuns();
        List<StageHealth> stages = stageHealth();

        // Bottleneck detection
        StageHealth bottleneck = stages.stream()
                .max(Comparator.comparingDouble(StageHealth::avgLatencyMs)).orElse(null);

        // Confidence trend
        List<Double> confidenceTrend = runs.stream().map(PipelineRunMetrics::confidence).toList();

        return new PipelineHealthReport(
                totalRuns(), avgConfidence(), avgLatencyMs(),
                verificationSuccessRate(), repairFrequency(), repairSuccessRate(),
                judgeOverrideRate(), avgEvidenceCoverage(), mostCommonRepairReason(),
                stages, bottleneck, confidenceTrend,
                routingStrategyDistribution(), knowledgeTableUsage(),
                outcomeDistribution());
    }

    private double avg(java.util.function.ToDoubleFunction<PipelineRunMetrics> fn) {
        List<PipelineRunMetrics> runs = repository.allRuns();
        if (runs.isEmpty()) return 0;
        return runs.stream().mapToDouble(fn).average().orElse(0);
    }
}
