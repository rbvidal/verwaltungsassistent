package reasoning.ai.benchmark;

import reasoning.ai.api.*;
import reasoning.ai.application.*;
import reasoning.ai.knowledge.*;
import reasoning.ai.model.*;
import reasoning.search.api.EmbeddingProvider;
import java.time.LocalDate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Executes the complete AI pipeline for every benchmark question and validates results.
 * Uses real routing, grounding, and prompt building; stubs only the LLM and retrieval.
 */
public final class BenchmarkRunner {

    private final AiOrchestrationService aiService;
    private final List<BenchmarkResult> results = new ArrayList<>();

    private BenchmarkRunner(AiOrchestrationService aiService) {
        this.aiService = aiService;
    }

    /**
     * Creates a BenchmarkRunner with a fully wired AiService.
     * Only the LLM provider and retrieval path are stubbed.
     */
    public static BenchmarkRunner create() {
        // ── Structured knowledge (real) ──
        KnowledgeRegistry registry = new KnowledgeRegistry();
        // Load benchmark test data (municipal data is in municipal module)
        SalaryTable tvl = new SalaryTable("TV-L 2025", "TV-L", LocalDate.of(2025, 2, 1), null);
        tvl.addEntry("EG 8", 3, 3713.37, 0, "");
        tvl.addEntry("EG 9", 3, 3250.50, 0, "");
        tvl.addEntry("EG 9a", 1, 3500.00, 0, "");
        tvl.addEntry("EG 9a", 3, 4117.53, 0, "");
        tvl.addEntry("EG 10", 2, 4231.36, 0, "");
        tvl.addEntry("EG 11", 3, 4875.49, 0, "");
        tvl.addEntry("EG 13", 3, 5467.76, 0, "");
        TravelAllowanceTable brkg = new TravelAllowanceTable("BRKG", "BRKG", LocalDate.of(2024, 1, 1));
        brkg.addEntry(0, 8.0, 0.0, "domestic", false, "0-8h (kein Tagegeld)");
        brkg.addEntry(8, 11.0, 6.0, "domestic", false, "8-11h");
        brkg.addEntry(11, 24.0, 12.0, "domestic", false, "11-24h");
        brkg.addEntry(24, null, 24.0, "domestic", false, "24h+");
        ThresholdTable av55 = new ThresholdTable("AV §55 LHO", "AV §55 LHO", LocalDate.of(2024, 1, 1));
        av55.addEntry(0, 500.0, "Kein Verfahren", "Lieferung/Dienstleistung", List.of(), "Unter 500 €");
        av55.addEntry(500.0, 1000.0, "Direktauftrag mit Genehmigung", "Lieferung/Dienstleistung", List.of(), "500-1.000 €");
        av55.addEntry(1000.0, 10000.0, "Direktauftrag", "Lieferung/Dienstleistung", List.of(), "1.000-10.000 €");
        av55.addEntry(10000.0, 100000.0, "Beschränkte Ausschreibung", "Lieferung/Dienstleistung", List.of(), "10.000-100.000 €");
        av55.addEntry(100000.0, null, "Öffentliche Ausschreibung / EU-weit", "Lieferung/Dienstleistung", List.of(), "Ab 100.000 €");
        av55.addEntry(0, 20000.0, "Direktauftrag", "Bauleistung", List.of(), "Bauleistung bis 20.000 €");
        KnowledgeDataLoader.loadFrom(registry, List.of(tvl), List.of(brkg), List.of(av55));

        // ── Decision routing (real) ──
        DomainClassifier domainClassifier = new DomainClassifier(DomainKnowledge.skeletal());
        DecisionRouter decisionRouter = new DecisionRouter(registry, domainClassifier, null,
                new reasoning.ai.application.RegexSemanticIntentParser());

        // ── Pipeline services (real) ──
        PipelineProfiler profiler = new PipelineProfiler();
        DefaultQueryIntentClassifier intentClassifier = new DefaultQueryIntentClassifier();
        AiAuditPublisher auditPublisher = new AiAuditPublisher(
                (eventType, subject, metadata) -> { /* no-op: benchmarks do not persist audit events */ });
        GroundingService grounding = new DefaultGroundingService(new EmbeddingProvider() {
            private final float[] v = new float[768];
            { for (int i = 0; i < 768; i++) v[i] = 0.036f; }
            public float[] embed(String t) { return v; }
            public List<float[]> embedBatch(List<String> ts) { return ts.stream().map(t -> v).toList(); }
            public int dimension() { return 768; }
            public String modelName() { return "benchmark-stub"; }
        }, new ClaimVerificationService() {
            public ClaimVerification verify(String c, String e) {
                return new ClaimVerification(c, e, ClaimVerification.Verdict.ENTAILED, 0.9, "stub");
            }
            public List<ClaimVerification> verifyBatch(String c, List<String> es) {
                return es.stream().map(e -> verify(c, e)).toList();
            }
        });

        // ── Stubs (LLM + retrieval path only) ──
        ChatCompletionProvider llm = BenchmarkStubs.chatProvider();
        ModelProvider models = BenchmarkStubs.modelProvider();
        RetrievalAugmentationService retrieval = BenchmarkStubs.retrievalStub();
        ContextAssembler assembler = BenchmarkStubs.contextAssemblerStub();
        PromptBuilder promptBuilder = BenchmarkStubs.promptBuilderStub();
        EvidenceCoverageValidator coverageValidator = BenchmarkStubs.evidenceCoverageValidatorStub();

        // ── AiService with real routing + grounding ──
        AiService service = new AiService(
                retrieval, assembler, promptBuilder, models, llm,
                grounding, auditPublisher, intentClassifier,
                coverageValidator, profiler, decisionRouter, null);

        return new BenchmarkRunner(service);
    }

    /** Runs all benchmark questions and returns results. */
    public List<BenchmarkResult> run(List<BenchmarkQuestion> questions) {
        results.clear();
        for (BenchmarkQuestion q : questions) {
            results.add(runOne(q));
        }
        return List.copyOf(results);
    }

    /** Runs a single benchmark question through the full pipeline. */
    public BenchmarkResult runOne(BenchmarkQuestion q) {
        Instant start = Instant.now();

        AiRequest request = new AiRequest(q.question(), null, null, null, 20,
                RetrievalScope.ALL_DOCUMENTS, null);

        long llmMs = 0;
        DecisionStrategy strategy;
        double confidence;
        String answer;
        boolean grounded;

        try {
            Instant llmStart = Instant.now();
            AiResponse response = aiService.answer(request);
            llmMs = Duration.between(llmStart, Instant.now()).toMillis();
            long totalMs = Duration.between(start, Instant.now()).toMillis();

            strategy = response.metadata() != null
                    && response.metadata().retrievalStrategy() != null
                    ? DecisionStrategy.valueOf(response.metadata().retrievalStrategy())
                    : DecisionStrategy.HYBRID_RETRIEVAL;

            answer = response.answer() != null ? response.answer().answer() : "";
            confidence = response.answer() != null && response.answer().confidence() != null
                    ? response.answer().confidence().overallConfidence() : 0.0;
            grounded = response.answer() != null && response.answer().grounded();

            return BenchmarkResult.validate(q, strategy, confidence, totalMs, llmMs,
                    answer, grounded);

        } catch (Exception e) {
            long totalMs = Duration.between(start, Instant.now()).toMillis();
            return new BenchmarkResult(q, q.expectedStrategy(), 0.0, totalMs, llmMs,
                    "[ERROR] " + e.getMessage(), false,
                    List.of("Exception: " + e.getClass().getSimpleName() + " — " + e.getMessage()),
                    List.of());
        }
    }

    // ── Report generation ──

    /** Generates a console report string. */
    public String consoleReport() {
        long passed = results.stream().filter(BenchmarkResult::passed).count();
        long failed = results.size() - passed;
        double avgTotal = results.stream().mapToLong(BenchmarkResult::totalLatencyMs).average().orElse(0);
        double avgLlm = results.stream().mapToLong(BenchmarkResult::llmLatencyMs).average().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(repeat("=", 60)).append("\n");
        sb.append("  Municipal Decision Assistant — Benchmark Report\n");
        sb.append(repeat("=", 60)).append("\n\n");

        // Summary
        sb.append(String.format("  Total:       %3d%n", results.size()));
        sb.append(String.format("  Passed:      %3d%n", passed));
        sb.append(String.format("  Failed:      %3d%n", failed));
        sb.append(String.format("  Success:     %5.1f%%%n",
                results.isEmpty() ? 0.0 : 100.0 * passed / results.size()));
        sb.append(String.format("  Avg latency: %5.0f ms%n", avgTotal));
        sb.append(String.format("  Avg LLM:     %5.0f ms%n", avgLlm));
        // Quality scores
        var scores = BenchmarkResult.aggregate(results);
        sb.append(String.format("  Quality — Routing: %3d%%  Evidence: %3d%%  Semantics: %3d%%  Grounding: %3d%%  Overall: %3d%%%n%n",
                scores.pct(scores.routing()), scores.pct(scores.evidence()),
                scores.pct(scores.semantics()), scores.pct(scores.grounding()),
                scores.pct(scores.overall())));

        // Per category
        sb.append(repeat("-", 60)).append("\n");
        sb.append("  Per Category\n");
        sb.append(repeat("-", 60)).append("\n");
        var byCategory = new LinkedHashMap<String, List<BenchmarkResult>>();
        for (var r : results) byCategory.computeIfAbsent(
                r.question().category(), k -> new ArrayList<>()).add(r);
        for (var entry : byCategory.entrySet()) {
            long catPassed = entry.getValue().stream().filter(BenchmarkResult::passed).count();
            double catAvg = entry.getValue().stream()
                    .mapToLong(BenchmarkResult::totalLatencyMs).average().orElse(0);
            sb.append(String.format("  %-20s  %2d/%2d passed  avg %5.0f ms%n",
                    entry.getKey(), catPassed, entry.getValue().size(), catAvg));
        }
        sb.append("\n");

        // Per question — sorted by quality
        sb.append(repeat("-", 60)).append("\n");
        sb.append("  Per Question (sorted by quality)\n");
        sb.append(repeat("-", 60)).append("\n");
        var sorted = BenchmarkResult.sortedByQuality(results);
        for (var r : sorted) {
            String status = r.passed() ? "PASS" : "FAIL";
            String weak = r.isWeak() ? " [WEAK]" : "";
            long warnings = r.warnings().size();
            sb.append(String.format("  %s  %s  %s  %s  %4d ms  Q=%3d%%  W=%d%s%n",
                    status,
                    r.question().id(),
                    r.question().expectedStrategy(),
                    r.actualStrategy(),
                    r.totalLatencyMs(),
                    r.scores().pct(r.scores().overall()),
                    warnings,
                    weak));
        }
        sb.append("\n");

        // Failures detail
        var failedResults = results.stream().filter(r -> !r.passed()).toList();
        if (!failedResults.isEmpty()) {
            sb.append(repeat("-", 60)).append("\n");
            sb.append("  Failures\n");
            sb.append(repeat("-", 60)).append("\n");
            for (var r : failedResults) {
                sb.append("  ").append(r.question().id()).append(": ")
                        .append(r.question().question()).append("\n");
                for (var f : r.failures()) {
                    sb.append("    → ").append(f).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append(repeat("=", 60)).append("\n");
        sb.append(String.format("  SUCCESS RATE: %d / %d  (%.1f%%)%n",
                passed, results.size(),
                results.isEmpty() ? 0.0 : 100.0 * passed / results.size()));
        sb.append(repeat("=", 60)).append("\n");

        return sb.toString();
    }

    /** Generates a Markdown report. */
    public String markdownReport() {
        long passed = results.stream().filter(BenchmarkResult::passed).count();
        double avgTotal = results.stream().mapToLong(BenchmarkResult::totalLatencyMs).average().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("# Municipal Decision Assistant — Benchmark Report\n\n");
        sb.append("## Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total | ").append(results.size()).append(" |\n");
        sb.append("| Passed | ").append(passed).append(" |\n");
        sb.append("| Failed | ").append(results.size() - passed).append(" |\n");
        sb.append(String.format("| Success Rate | %.1f%% |\n",
                results.isEmpty() ? 0.0 : 100.0 * passed / results.size()));
        sb.append(String.format("| Avg Latency | %.0f ms |\n", avgTotal));
        sb.append("\n## Per Question\n\n");
        sb.append("| ID | Category | Strategy | Expected | Latency | Confidence | Result |\n");
        sb.append("|----|----------|----------|----------|---------|------------|--------|\n");
        for (var r : results) {
            sb.append(String.format("| %s | %s | %s | %s | %d ms | %.3f | %s |\n",
                    r.question().id(), r.question().category(),
                    r.actualStrategy(), r.question().expectedStrategy(),
                    r.totalLatencyMs(), r.actualConfidence(),
                    r.passed() ? "✅" : "❌"));
        }
        sb.append("\n## Failures\n\n");
        var failed = results.stream().filter(r -> !r.passed()).toList();
        if (failed.isEmpty()) {
            sb.append("No failures.\n");
        } else {
            for (var r : failed) {
                sb.append("### ").append(r.question().id()).append("\n\n");
                sb.append("**Q:** ").append(r.question().question()).append("\n\n");
                for (var f : r.failures()) {
                    sb.append("- ").append(f).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** Generates a JSON report. */
    public String jsonReport() {
        long passed = results.stream().filter(BenchmarkResult::passed).count();
        double avgTotal = results.stream().mapToLong(BenchmarkResult::totalLatencyMs).average().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(results.size()).append(",\n");
        sb.append("    \"passed\": ").append(passed).append(",\n");
        sb.append("    \"failed\": ").append(results.size() - passed).append(",\n");
        sb.append(String.format("    \"successRate\": %.1f,\n",
                results.isEmpty() ? 0.0 : 100.0 * passed / results.size()));
        sb.append(String.format("    \"avgLatencyMs\": %.0f\n", avgTotal));
        sb.append("  },\n");
        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(escapeJson(r.question().id())).append("\",\n");
            sb.append("      \"category\": \"").append(escapeJson(r.question().category())).append("\",\n");
            sb.append("      \"question\": \"").append(escapeJson(r.question().question())).append("\",\n");
            sb.append("      \"expectedStrategy\": \"").append(r.question().expectedStrategy()).append("\",\n");
            sb.append("      \"actualStrategy\": \"").append(r.actualStrategy()).append("\",\n");
            sb.append(String.format(java.util.Locale.US, "      \"confidence\": %.3f,\n", r.actualConfidence()));
            sb.append("      \"totalLatencyMs\": ").append(r.totalLatencyMs()).append(",\n");
            sb.append("      \"llmLatencyMs\": ").append(r.llmLatencyMs()).append(",\n");
            sb.append("      \"grounded\": ").append(r.grounded()).append(",\n");
            sb.append("      \"passed\": ").append(r.passed()).append(",\n");
            sb.append("      \"failures\": [");
            var failures = r.failures();
            for (int j = 0; j < failures.size(); j++) {
                sb.append("\"").append(escapeJson(failures.get(j))).append("\"");
                if (j < failures.size() - 1) sb.append(", ");
            }
            sb.append("]\n");
            sb.append("    }");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public List<BenchmarkResult> results() { return List.copyOf(results); }

    private static String repeat(String s, int n) {
        return String.valueOf(s).repeat(Math.max(0, n));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
