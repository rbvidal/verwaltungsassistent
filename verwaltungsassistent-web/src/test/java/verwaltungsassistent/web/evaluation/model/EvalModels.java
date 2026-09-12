package verwaltungsassistent.web.evaluation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Evaluation data model — benchmark cases, evaluation results, metrics.
 * Uses simple Jackson-compatible records for JSON serialization.
 */
public final class EvalModels {

    private EvalModels() {}

    // ── Benchmark Case ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BenchmarkCase(
            String id,
            String domain,
            String difficulty,
            String question,
            Map<String, Object> expectedIntent,
            List<String> expectedAuthorities,
            List<String> expectedDocuments,
            List<String> expectedKnowledgeRules,
            Integer expectedFindingCount,
            List<String> expectedRecommendationKeywords,
            Double minimumGroundingScore,
            Double minimumCoverage,
            Long maximumLatencyMs,
            String notes
    ) {
        public static List<BenchmarkCase> loadAll(ObjectMapper mapper, String... resourcePaths) {
            List<BenchmarkCase> all = new ArrayList<>();
            for (String path : resourcePaths) {
                try (InputStream in = BenchmarkCase.class.getClassLoader().getResourceAsStream(path)) {
                    if (in == null) {
                        System.err.println("WARN: Benchmark resource not found: " + path);
                        continue;
                    }
                    List<BenchmarkCase> cases = mapper.readValue(in,
                            mapper.getTypeFactory().constructCollectionType(List.class, BenchmarkCase.class));
                    all.addAll(cases);
                } catch (Exception e) {
                    System.err.println("ERROR loading benchmarks from " + path + ": " + e.getMessage());
                }
            }
            return all;
        }
    }

    // ── Evaluation Result (per benchmark case) ──

    public record EvalResult(
            String benchmarkId,
            String domain,
            String difficulty,
            boolean passed,
            double overallScore,

            // Retrieval
            RetrievalMetrics retrieval,

            // Rule engine
            RuleMetrics rules,

            // Grounding
            GroundingMetrics grounding,

            // Decision quality
            DecisionMetrics decision,

            // Performance
            PerformanceMetrics performance,

            // Details
            List<String> failures,
            List<String> warnings,
            String actualAnswer,
            long timestamp
    ) {
        public static EvalResultBuilder builder(String id) { return new EvalResultBuilder(id); }
    }

    public static class EvalResultBuilder {
        private final String id;
        private String domain, difficulty;
        private boolean passed;
        private double overallScore;
        private RetrievalMetrics retrieval;
        private RuleMetrics rules;
        private GroundingMetrics grounding;
        private DecisionMetrics decision;
        private PerformanceMetrics performance;
        private final List<String> failures = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private String actualAnswer;

        EvalResultBuilder(String id) { this.id = id; }

        public EvalResultBuilder domain(String v) { domain = v; return this; }
        public EvalResultBuilder difficulty(String v) { difficulty = v; return this; }
        public EvalResultBuilder passed(boolean v) { passed = v; return this; }
        public EvalResultBuilder overallScore(double v) { overallScore = v; return this; }
        public EvalResultBuilder retrieval(RetrievalMetrics v) { retrieval = v; return this; }
        public EvalResultBuilder rules(RuleMetrics v) { rules = v; return this; }
        public EvalResultBuilder grounding(GroundingMetrics v) { grounding = v; return this; }
        public EvalResultBuilder decision(DecisionMetrics v) { decision = v; return this; }
        public EvalResultBuilder performance(PerformanceMetrics v) { performance = v; return this; }
        public EvalResultBuilder addFailure(String f) { failures.add(f); return this; }
        public EvalResultBuilder addWarning(String w) { warnings.add(w); return this; }
        public EvalResultBuilder actualAnswer(String a) { actualAnswer = a; return this; }

        public EvalResult build() {
            return new EvalResult(id, domain, difficulty, passed, overallScore,
                    retrieval, rules, grounding, decision, performance,
                    List.copyOf(failures), List.copyOf(warnings), actualAnswer,
                    Instant.now().toEpochMilli());
        }
    }

    // ── Metric Sub-records ──

    public record RetrievalMetrics(
            double precisionAt5, double recallAt5, double mrr, double ndcg,
            int retrievedCount, int relevantCount, List<String> retrievedDocIds
    ) {
        public static RetrievalMetrics empty() {
            return new RetrievalMetrics(0, 0, 0, 0, 0, 0, List.of());
        }
    }

    public record RuleMetrics(
            List<String> triggeredRules, List<String> expectedRules,
            List<String> missingRules, List<String> unexpectedRules,
            boolean allExpectedTriggered
    ) {
        public static RuleMetrics empty() {
            return new RuleMetrics(List.of(), List.of(), List.of(), List.of(), false);
        }
    }

    public record GroundingMetrics(
            double groundingScore, boolean grounded,
            int supportedFindings, int unsupportedFindings,
            int totalFindings, int orphanCitations
    ) {
        public static GroundingMetrics empty() {
            return new GroundingMetrics(0, false, 0, 0, 0, 0);
        }
    }

    public record DecisionMetrics(
            int authorityCount, double authorityMatchRate,
            List<String> foundAuthorities, List<String> expectedAuthorities,
            double keywordMatchRate, int findingCount, int evidenceCount,
            double confidence, double coverage
    ) {
        public static DecisionMetrics empty() {
            return new DecisionMetrics(0, 0, List.of(), List.of(), 0, 0, 0, 0, 0);
        }
    }

    public record PerformanceMetrics(
            long retrievalMs, long routingMs, long promptMs,
            long llmMs, long groundingMs, long totalMs
    ) {
        public static PerformanceMetrics empty() {
            return new PerformanceMetrics(0, 0, 0, 0, 0, 0);
        }
    }

    // ── Summary (across all benchmark cases) ──

    public record EvalSummary(
            String timestamp,
            int totalCases, int passed, int failed,
            double overallScore,
            Map<String, DomainSummary> domains,
            AggregateMetrics aggregates,
            List<EvalResult> results,
            List<RegressionAlert> regressions,
            CalibrationReport calibrationReport,
            HallucinationReport hallucinationReport,
            RuleCoverageReport ruleCoverageReport,
            CorpusCoverageReport corpusCoverageReport
    ) {}

    public record DomainSummary(double score, int passed, int failed,
                                 double avgRetrieval, double avgGrounding, double avgLatencyMs) {}

    public record AggregateMetrics(
            double avgPrecisionAt5, double avgRecallAt5, double avgMrr,
            double avgGroundingScore, double avgConfidence,
            double avgLatencyMs, double ruleTriggerRate, double passRate
    ) {}

    public record RegressionAlert(
            String metric, double previousValue, double currentValue,
            double threshold, String severity
    ) {}

    public record CalibrationReport(
            Map<String, CalibrationBucket> buckets, boolean overconfident
    ) {
        public record CalibrationBucket(String range, int total, int correct, double accuracy) {}
    }

    public record HallucinationReport(
            int recommendationsWithoutEvidence, int findingsWithoutCitations,
            int legalRefsNotInEvidence, int authorityRefsWithoutDocs,
            int unsupportedNumericValues, double hallucinationRate
    ) {}

    public record RuleCoverageReport(
            List<RuleCoverageEntry> entries, List<String> neverExercisedRules
    ) {
        public record RuleCoverageEntry(String rule, int evaluated, int executed,
                                         int succeeded, int failed, List<String> domains) {}
    }

    public record CorpusCoverageReport(
            List<CorpusCoverageEntry> entries, List<String> neverRetrievedDocuments
    ) {
        public record CorpusCoverageEntry(String documentCategory, int documents, int chunks,
                                           int retrieved, int neverRetrieved,
                                           double avgRank, double avgRelevance, double avgCitationFreq) {}
    }
}
