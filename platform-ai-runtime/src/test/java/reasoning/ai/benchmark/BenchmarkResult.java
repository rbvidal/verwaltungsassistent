package reasoning.ai.benchmark;

import reasoning.ai.model.DecisionStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of running a single benchmark question through the full pipeline.
 * Includes quality scoring across multiple dimensions.
 */
public final class BenchmarkResult {
    private final BenchmarkQuestion question;
    private final DecisionStrategy actualStrategy;
    private final double actualConfidence;
    private final long totalLatencyMs;
    private final long llmLatencyMs;
    private final String actualAnswer;
    private final boolean grounded;
    private final List<String> failures;
    private final List<String> warnings;
    private final QualityScores scores;

    public BenchmarkResult(BenchmarkQuestion question, DecisionStrategy actualStrategy,
                           double actualConfidence, long totalLatencyMs, long llmLatencyMs,
                           String actualAnswer, boolean grounded,
                           List<String> failures, List<String> warnings) {
        this.question = question;
        this.actualStrategy = actualStrategy;
        this.actualConfidence = actualConfidence;
        this.totalLatencyMs = totalLatencyMs;
        this.llmLatencyMs = llmLatencyMs;
        this.actualAnswer = actualAnswer;
        this.grounded = grounded;
        this.failures = List.copyOf(failures);
        this.warnings = List.copyOf(warnings);
        this.scores = computeScores(question, actualStrategy, actualConfidence,
                actualAnswer, grounded, failures);
    }

    public boolean passed() { return failures.isEmpty(); }

    public BenchmarkQuestion question() { return question; }
    public DecisionStrategy actualStrategy() { return actualStrategy; }
    public double actualConfidence() { return actualConfidence; }
    public long totalLatencyMs() { return totalLatencyMs; }
    public long llmLatencyMs() { return llmLatencyMs; }
    public String actualAnswer() { return actualAnswer; }
    public boolean grounded() { return grounded; }
    public List<String> failures() { return failures; }
    public List<String> warnings() { return warnings; }
    public QualityScores scores() { return scores; }

    static BenchmarkResult validate(BenchmarkQuestion q, DecisionStrategy strategy,
                                    double confidence, long totalMs, long llmMs,
                                    String answer, boolean grounded) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // HARD: routing
        if (strategy != q.expectedStrategy()) {
            failures.add("Strategy: expected " + q.expectedStrategy() + " but was " + strategy);
        }
        // HARD: confidence
        if (confidence < q.minConfidence() || confidence > q.maxConfidence()) {
            failures.add("Confidence: expected " + q.minConfidence() + "-" + q.maxConfidence()
                    + " but was " + String.format("%.3f", confidence));
        }
        // HARD: grounded
        if (grounded != q.expectedGrounded()) {
            failures.add("Grounded: expected " + q.expectedGrounded() + " but was " + grounded);
        }
        // HARD: regulation
        if (q.expectedRegulation() != null && !q.expectedRegulation().isBlank()) {
            if (!answer.contains(q.expectedRegulation())) {
                failures.add("Regulation: expected '" + q.expectedRegulation()
                        + "' not found in answer");
            }
        }
        // HARD: semantic concepts — required
        String lower = answer.toLowerCase();
        for (String concept : q.mustContainConcepts()) {
            if (!lower.contains(concept.toLowerCase())) {
                failures.add("Semantic: required concept '" + concept + "' not found");
            }
        }
        // HARD: semantic concepts — forbidden
        for (String concept : q.mustNotContainConcepts()) {
            if (lower.contains(concept.toLowerCase())) {
                failures.add("Semantic: forbidden concept '" + concept + "' found");
            }
        }
        // SOFT: keyword checks (kept for backward compatibility, reported as warnings)
        for (String kw : q.expectedKeywords()) {
            if (!lower.contains(kw.toLowerCase())) {
                warnings.add("Keyword: '" + kw + "' not found");
            }
        }
        for (String kw : q.forbiddenKeywords()) {
            if (lower.contains(kw.toLowerCase())) {
                warnings.add("Forbidden: '" + kw + "' found");
            }
        }

        return new BenchmarkResult(q, strategy, confidence, totalMs, llmMs, answer,
                grounded, failures, warnings);
    }

    private static QualityScores computeScores(BenchmarkQuestion q,
            DecisionStrategy strategy, double confidence, String answer,
            boolean grounded, List<String> failures) {
        boolean routingOk = strategy == q.expectedStrategy();
        boolean confOk = confidence >= q.minConfidence() && confidence <= q.maxConfidence();
        boolean groundedOk = grounded == q.expectedGrounded();
        boolean regulationOk = true;
        if (q.expectedRegulation() != null && !q.expectedRegulation().isBlank()) {
            regulationOk = answer.contains(q.expectedRegulation());
        }
        String lower = answer.toLowerCase();
        int required = q.mustContainConcepts().size();
        int requiredOk = 0;
        for (String c : q.mustContainConcepts()) {
            if (lower.contains(c.toLowerCase())) requiredOk++;
        }
        int forbidden = q.mustNotContainConcepts().size();
        int forbiddenOk = 0;
        for (String c : q.mustNotContainConcepts()) {
            if (!lower.contains(c.toLowerCase())) forbiddenOk++;
        }
        double semScore = (required + forbidden) == 0 ? 1.0
                : (double) (requiredOk + forbiddenOk) / (required + forbidden);

        return new QualityScores(
                routingOk ? 1.0 : 0.0,
                regulationOk ? 1.0 : 0.0,
                semScore,
                groundedOk ? 1.0 : 0.0,
                1.0); // overall computed below
    }

    /** Quality scores for a benchmark result. */
    public record QualityScores(
            double routing,
            double evidence,
            double semantics,
            double grounding,
            double overall
    ) {
        public QualityScores {
            overall = (routing * 0.25 + evidence * 0.25 + semantics * 0.30 + grounding * 0.20);
        }
        public int pct(double v) { return (int) Math.round(v * 100); }
    }

    /** Aggregate quality across a whole run. */
    public static QualityScores aggregate(List<BenchmarkResult> results) {
        double r = avg(results, s -> s.scores().routing());
        double e = avg(results, s -> s.scores().evidence());
        double sem = avg(results, s -> s.scores().semantics());
        double g = avg(results, s -> s.scores().grounding());
        return new QualityScores(r, e, sem, g, 1.0);
    }

    private static double avg(List<BenchmarkResult> results,
            java.util.function.ToDoubleFunction<BenchmarkResult> fn) {
        return results.stream().mapToDouble(fn).average().orElse(0);
    }

    // ── Weak answer detection ──

    public boolean isWeak() {
        String a = actualAnswer != null ? actualAnswer : "";
        return a.length() < 200 || a.toLowerCase().contains("keine information")
                || a.toLowerCase().contains("kann ich nicht")
                || a.equals(question.question());
    }

    public boolean isShort() {
        return actualAnswer != null && actualAnswer.length() < 300;
    }

    public static List<BenchmarkResult> sortedByQuality(List<BenchmarkResult> results) {
        List<BenchmarkResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> Double.compare(
                b.scores().overall() * 100 - (b.isWeak() ? 20 : 0),
                a.scores().overall() * 100 - (a.isWeak() ? 20 : 0)));
        return sorted;
    }
}
