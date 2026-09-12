package verwaltungsassistent.web.evaluation.runner;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.*;
import reasoning.ai.application.PipelineProfiler;
import verwaltungsassistent.web.evaluation.model.EvalModels;
import verwaltungsassistent.web.evaluation.model.EvalModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Executes benchmark questions through the complete AI pipeline and produces evaluation results.
 *
 * <p>Usage from a Spring test:
 * <pre>
 * EvaluationRunner runner = new EvaluationRunner(aiFacade, profiler, mapper);
 * EvalSummary summary = runner.runAll("evaluation/benchmarks");
 * runner.writeReports(summary, "target/evaluation-reports");
 * </pre>
 */
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final AiFacade aiFacade;
    private final PipelineProfiler profiler;
    private final ObjectMapper mapper;

    public EvaluationRunner(AiFacade aiFacade, PipelineProfiler profiler, ObjectMapper mapper) {
        this.aiFacade = aiFacade;
        this.profiler = profiler;
        this.mapper = mapper;
    }

    // ── Run All Benchmarks ──

    public EvalSummary runAll(String benchmarkDir) throws IOException {
        List<BenchmarkCase> cases = BenchmarkCase.loadAll(mapper,
                benchmarkDir + "/procurement.json",
                benchmarkDir + "/travel.json",
                benchmarkDir + "/hr.json");

        return runCases(cases);
    }

    public EvalSummary runCases(List<BenchmarkCase> cases) {
        List<EvalResult> results = new ArrayList<>();
        int passed = 0, failed = 0;
        Map<String, List<EvalResult>> byDomain = new LinkedHashMap<>();

        for (BenchmarkCase bc : cases) {
            log.info("Evaluating {}: {}", bc.id(), bc.question());
            EvalResult result = evaluate(bc);
            results.add(result);
            if (result.passed()) passed++; else failed++;
            byDomain.computeIfAbsent(bc.domain(), k -> new ArrayList<>()).add(result);
        }

        // Domain summaries
        Map<String, DomainSummary> domains = new LinkedHashMap<>();
        for (var entry : byDomain.entrySet()) {
            List<EvalResult> dr = entry.getValue();
            long p = dr.stream().filter(EvalResult::passed).count();
            domains.put(entry.getKey(), new DomainSummary(
                    dr.stream().mapToDouble(EvalResult::overallScore).average().orElse(0),
                    (int) p, dr.size() - (int) p,
                    dr.stream().mapToDouble(r -> r.retrieval().precisionAt5()).average().orElse(0),
                    dr.stream().mapToDouble(r -> r.grounding().groundingScore()).average().orElse(0),
                    dr.stream().mapToDouble(r -> r.performance().totalMs()).average().orElse(0)));
        }

        // Aggregate metrics
        AggregateMetrics agg = new AggregateMetrics(
                avg(results, r -> r.retrieval().precisionAt5()),
                avg(results, r -> r.retrieval().recallAt5()),
                avg(results, r -> r.retrieval().mrr()),
                avg(results, r -> r.grounding().groundingScore()),
                avg(results, r -> r.decision().confidence()),
                avg(results, r -> r.performance().totalMs()),
                results.stream().filter(r -> r.rules().allExpectedTriggered()).count() / (double) Math.max(1, results.size()),
                (double) passed / Math.max(1, cases.size()));

        // Regression detection
        List<RegressionAlert> regressions = detectRegressions(results);

        // Calibration
        CalibrationReport calibration = buildCalibration(results);

        // Hallucination
        HallucinationReport hallucination = detectHallucinations(results);

        // Rule coverage
        RuleCoverageReport ruleCoverage = buildRuleCoverage(results);

        // Corpus coverage
        CorpusCoverageReport corpusCoverage = buildCorpusCoverage(results);

        return new EvalSummary(
                Instant.now().toString(), cases.size(), passed, failed,
                (double) passed / Math.max(1, cases.size()),
                domains, agg, results, regressions,
                calibration, hallucination, ruleCoverage, corpusCoverage);
    }

    // ── Single Case Evaluation ──

    private EvalResult evaluate(BenchmarkCase bc) {
        EvalResultBuilder builder = EvalResult.builder(bc.id())
                .domain(bc.domain()).difficulty(bc.difficulty());

        try {
            // Build AI request
            AiRequest request = new AiRequest(bc.question(), null, null,
                    new AiConversationContext(List.of(), null, null, null,
                            "eval-" + bc.id()),
                    5, RetrievalScope.HYBRID, UUID.randomUUID());

            // Execute pipeline
            long start = System.currentTimeMillis();
            AiResponse response = aiFacade.answer(request);
            long totalMs = System.currentTimeMillis() - start;
            ReasonedAnswer answer = response.answer();
            InferenceMetadata metadata = response.metadata();

            // ── Retrieval Metrics ──
            RetrievalMetrics retrieval = computeRetrieval(answer, bc);

            // ── Rule Metrics ──
            RuleMetrics rules = computeRuleMetrics(metadata, bc);

            // ── Grounding ──
            GroundingMetrics grounding = computeGrounding(answer);

            // ── Decision Quality ──
            DecisionMetrics decision = computeDecisionMetrics(answer, bc);

            // ── Performance ──
            PerformanceMetrics perf = computePerformance(totalMs);

            // ── Pass/Fail ──
            boolean passed = true;
            if (grounding.groundingScore() < bc.minimumGroundingScore()) {
                builder.addFailure("Grounding score " + String.format("%.2f", grounding.groundingScore())
                        + " below minimum " + bc.minimumGroundingScore());
                passed = false;
            }
            if (decision.coverage() < bc.minimumCoverage()) {
                builder.addFailure("Coverage " + String.format("%.2f", decision.coverage())
                        + " below minimum " + bc.minimumCoverage());
                passed = false;
            }
            if (totalMs > bc.maximumLatencyMs()) {
                builder.addWarning("Latency " + totalMs + "ms exceeds maximum " + bc.maximumLatencyMs());
            }
            if (!grounding.grounded()) {
                builder.addWarning("Answer is not grounded");
            }
            if (decision.authorityMatchRate() < 0.3) {
                builder.addWarning("Low authority match rate: " + String.format("%.2f", decision.authorityMatchRate()));
            }

            double score = computeOverallScore(retrieval, rules, grounding, decision);
            builder.passed(passed).overallScore(score)
                    .retrieval(retrieval).rules(rules)
                    .grounding(grounding).decision(decision)
                    .performance(perf).actualAnswer(answer.answer());

        } catch (Exception e) {
            log.error("Evaluation failed for {}: {}", bc.id(), e.getMessage());
            builder.passed(false).overallScore(0)
                    .addFailure("Pipeline exception: " + e.getMessage())
                    .retrieval(RetrievalMetrics.empty())
                    .rules(RuleMetrics.empty())
                    .grounding(GroundingMetrics.empty())
                    .decision(DecisionMetrics.empty())
                    .performance(PerformanceMetrics.empty());
        }

        return builder.build();
    }

    // ── Metric Calculators ──

    private RetrievalMetrics computeRetrieval(ReasonedAnswer answer, BenchmarkCase bc) {
        List<String> retrievedIds = answer.sourceCitations() != null
                ? answer.sourceCitations().stream().map(sc -> sc.documentId().toString()).distinct().toList()
                : List.of();
        List<String> expected = bc.expectedDocuments() != null ? bc.expectedDocuments() : List.of();
        long relevant = retrievedIds.stream().filter(expected::contains).count();

        double p5 = retrievedIds.isEmpty() ? 0 : (double) relevant / Math.min(retrievedIds.size(), 5);
        double r5 = expected.isEmpty() ? 1.0 : (double) relevant / Math.max(1, expected.size());
        double mrr = retrievedIds.isEmpty() ? 0 :
                (retrievedIds.stream().anyMatch(expected::contains) ? 1.0 : 0.0) / 1;
        double ndcg = p5; // simplified

        return new RetrievalMetrics(p5, r5, mrr, ndcg,
                retrievedIds.size(), expected.size(), retrievedIds);
    }

    private RuleMetrics computeRuleMetrics(InferenceMetadata metadata, BenchmarkCase bc) {
        List<String> triggered = List.of(
                metadata.retrievalStrategy() != null ? metadata.retrievalStrategy() : "unknown");
        List<String> expected = bc.expectedKnowledgeRules() != null ? bc.expectedKnowledgeRules() : List.of();

        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(triggered);
        List<String> unexpected = new ArrayList<>(triggered);
        unexpected.removeAll(expected);

        boolean allTriggered = missing.isEmpty();
        return new RuleMetrics(triggered, expected, missing, unexpected, allTriggered);
    }

    private GroundingMetrics computeGrounding(ReasonedAnswer answer) {
        int totalFindings = 0, supported = 0, unsupported = 0;
        FindingHierarchy fh = answer.findingHierarchy();
        if (fh != null) {
            totalFindings = fh.primaryFindings().size() + fh.secondaryFindings().size()
                    + fh.proceduralFindings().size() + fh.supportingFindings().size();
            supported = fh.primaryFindings().size(); // primary = typically supported
            unsupported = totalFindings - supported;
        }
        int orphanCitations = 0;
        if (answer.sourceCitations() != null && totalFindings == 0) {
            orphanCitations = answer.sourceCitations().size();
        }
        double score = totalFindings > 0 ? (double) supported / totalFindings : 0;
        return new GroundingMetrics(score, answer.grounded(), supported, unsupported,
                totalFindings, orphanCitations);
    }

    private DecisionMetrics computeDecisionMetrics(ReasonedAnswer answer, BenchmarkCase bc) {
        List<String> foundAuthorities = answer.authorityReferences() != null
                ? answer.authorityReferences().stream()
                    .map(AuthorityReference::entryTitle)
                    .filter(Objects::nonNull).toList()
                : List.of();
        List<String> expected = bc.expectedAuthorities() != null ? bc.expectedAuthorities() : List.of();

        long matches = foundAuthorities.stream()
                .filter(a -> expected.stream().anyMatch(e -> a.toLowerCase().contains(e.toLowerCase())))
                .count();
        double authRate = expected.isEmpty() ? 1.0 : (double) matches / expected.size();

        double kwRate = 0;
        if (bc.expectedRecommendationKeywords() != null && answer.answer() != null) {
            long kwMatches = bc.expectedRecommendationKeywords().stream()
                    .filter(kw -> answer.answer().toLowerCase().contains(kw.toLowerCase())).count();
            kwRate = (double) kwMatches / bc.expectedRecommendationKeywords().size();
        }

        int findingCount = 0;
        FindingHierarchy fh = answer.findingHierarchy();
        if (fh != null) {
            findingCount = fh.primaryFindings().size() + fh.secondaryFindings().size()
                    + fh.proceduralFindings().size() + fh.supportingFindings().size();
        }
        int evidenceCount = answer.sourceCitations() != null ? answer.sourceCitations().size() : 0;
        double confidence = answer.confidence() != null ? answer.confidence().overallConfidence() : 0;
        double coverage = answer.sourceDossier() != null ? answer.sourceDossier().coverageScore() : 0;

        return new DecisionMetrics(foundAuthorities.size(), authRate, foundAuthorities, expected,
                kwRate, findingCount, evidenceCount, confidence, coverage);
    }

    private PerformanceMetrics computePerformance(long totalMs) {
        if (profiler == null) return new PerformanceMetrics(0, 0, 0, 0, 0, totalMs);
        Map<String, PipelineProfiler.StageTiming> profile = profiler.getCurrentProfile();
        return new PerformanceMetrics(
                ms(profile, "retrieval"), ms(profile, "routing"),
                ms(profile, "prompt"), ms(profile, "llm"),
                ms(profile, "ground"), totalMs);
    }

    private long ms(Map<String, PipelineProfiler.StageTiming> profile, String stage) {
        PipelineProfiler.StageTiming t = profile.get(stage);
        return t != null ? t.ms() : 0;
    }

    private double computeOverallScore(RetrievalMetrics r, RuleMetrics ru, GroundingMetrics g, DecisionMetrics d) {
        return (r.precisionAt5() * 0.15 + r.ndcg() * 0.10
                + (ru.allExpectedTriggered() ? 0.15 : 0.05)
                + g.groundingScore() * 0.20
                + d.keywordMatchRate() * 0.15
                + d.authorityMatchRate() * 0.10
                + (d.confidence() >= 0.5 ? 0.15 : 0.05));
    }

    // ── Regression Detection ──

    private List<RegressionAlert> detectRegressions(List<EvalResult> current) {
        List<RegressionAlert> alerts = new ArrayList<>();
        EvalSummary previous = loadPreviousResults();
        if (previous == null) return alerts; // No baseline

        double prevPass = previous.aggregates().passRate();
        double currPass = (double) current.stream().filter(EvalResult::passed).count() / Math.max(1, current.size());
        if (currPass < prevPass - 0.05) {
            alerts.add(new RegressionAlert("passRate", prevPass, currPass, 0.05, "HIGH"));
        }

        double prevLatency = previous.aggregates().avgLatencyMs();
        double currLatency = current.stream().mapToDouble(r -> r.performance().totalMs()).average().orElse(0);
        if (currLatency > prevLatency * 1.5 && currLatency > 1000) {
            alerts.add(new RegressionAlert("avgLatencyMs", prevLatency, currLatency, prevLatency * 1.5, "MEDIUM"));
        }

        double prevGrounding = previous.aggregates().avgGroundingScore();
        double currGrounding = current.stream().mapToDouble(r -> r.grounding().groundingScore()).average().orElse(0);
        if (currGrounding < prevGrounding - 0.10) {
            alerts.add(new RegressionAlert("avgGroundingScore", prevGrounding, currGrounding, 0.10, "HIGH"));
        }

        return alerts;
    }

    private EvalSummary loadPreviousResults() {
        try {
            File f = new File("target/evaluation-reports/latest.json");
            if (!f.exists()) return null;
            return mapper.readValue(f, EvalSummary.class);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Calibration ──

    private CalibrationReport buildCalibration(List<EvalResult> results) {
        Map<String, List<Boolean>> buckets = new LinkedHashMap<>();
        for (EvalResult r : results) {
            double conf = r.decision().confidence();
            String bucket = conf >= 0.9 ? "90-100%" : conf >= 0.8 ? "80-90%" :
                    conf >= 0.7 ? "70-80%" : conf >= 0.5 ? "50-70%" : "0-50%";
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(r.passed());
        }

        Map<String, CalibrationReport.CalibrationBucket> calBuckets = new LinkedHashMap<>();
        boolean overconfident = false;
        for (var entry : buckets.entrySet()) {
            List<Boolean> vals = entry.getValue();
            long correct = vals.stream().filter(b -> b).count();
            double accuracy = (double) correct / vals.size();
            calBuckets.put(entry.getKey(),
                    new CalibrationReport.CalibrationBucket(entry.getKey(), vals.size(), (int) correct, accuracy));
            if (entry.getKey().startsWith("90") && accuracy < 0.9) overconfident = true;
        }
        return new CalibrationReport(calBuckets, overconfident);
    }

    // ── Hallucination Detection ──

    private HallucinationReport detectHallucinations(List<EvalResult> results) {
        int recsWithoutEvidence = 0, findingsWithoutCitations = 0,
            legalRefsNotInEvidence = 0, authorityRefsWithoutDocs = 0,
            unsupportedNumeric = 0, totalClaims = 0;

        for (EvalResult r : results) {
            if (r.decision().findingCount() > 0 && r.decision().evidenceCount() == 0) {
                findingsWithoutCitations++;
            }
            if (r.decision().authorityCount() > 0 && r.decision().evidenceCount() == 0) {
                authorityRefsWithoutDocs++;
            }
            if (r.decision().evidenceCount() == 0 && r.grounding().totalFindings() > 0) {
                recsWithoutEvidence++;
            }
            totalClaims += Math.max(1, r.decision().findingCount() + r.decision().authorityCount());
        }

        double rate = totalClaims > 0 ? (double) (recsWithoutEvidence + findingsWithoutCitations
                + authorityRefsWithoutDocs) / totalClaims : 0;

        return new HallucinationReport(recsWithoutEvidence, findingsWithoutCitations,
                legalRefsNotInEvidence, authorityRefsWithoutDocs, unsupportedNumeric, rate);
    }

    // ── Coverage Reports ──

    private RuleCoverageReport buildRuleCoverage(List<EvalResult> results) {
        Map<String, RuleCoverageReport.RuleCoverageEntry> map = new LinkedHashMap<>();
        for (EvalResult r : results) {
            for (String rule : r.rules().triggeredRules()) {
                RuleCoverageReport.RuleCoverageEntry e = map.get(rule);
                if (e == null) {
                    map.put(rule, new RuleCoverageReport.RuleCoverageEntry(
                            rule, 1, 1, r.passed() ? 1 : 0, r.passed() ? 0 : 1,
                            new ArrayList<>(List.of(r.domain()))));
                } else {
                    map.put(rule, new RuleCoverageReport.RuleCoverageEntry(
                            rule, e.evaluated() + 1, e.executed() + 1,
                            e.succeeded() + (r.passed() ? 1 : 0),
                            e.failed() + (r.passed() ? 0 : 1),
                            new ArrayList<>(new LinkedHashSet<>() {{
                                addAll(e.domains()); add(r.domain());
                            }})));
                }
            }
        }
        List<String> neverExercised = List.of(
                "approval-chain", "publication-requirement", "vacation-days",
                "working-time", "part-time-salary", "parental-leave",
                "de-minimis-check", "funding-rate", "retention-period",
                "dpia-necessity", "breach-notification");
        return new RuleCoverageReport(List.copyOf(map.values()), neverExercised);
    }

    private CorpusCoverageReport buildCorpusCoverage(List<EvalResult> results) {
        List<CorpusCoverageReport.CorpusCoverageEntry> entries = List.of(
                new CorpusCoverageReport.CorpusCoverageEntry("REGULATION", 0, 0, 0, 0, 0, 0, 0),
                new CorpusCoverageReport.CorpusCoverageEntry("POLICY", 0, 0, 0, 0, 0, 0, 0),
                new CorpusCoverageReport.CorpusCoverageEntry("TEMPLATE", 0, 0, 0, 0, 0, 0, 0)
        );
        return new CorpusCoverageReport(entries, List.of());
    }

    // ── Write Reports ──

    public void writeReports(EvalSummary summary, String outputDir) throws IOException {
        File dir = new File(outputDir);
        dir.mkdirs();

        // Write latest.json
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(dir, "latest.json"), summary);

        // Write history entry
        File historyDir = new File(dir, "history");
        historyDir.mkdirs();
        String ts = Instant.now().toString().replace(":", "-").substring(0, 19);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                new File(historyDir, ts + ".json"), summary);

        // Write HTML dashboard
        String html = HtmlReportGenerator.generate(summary);
        java.nio.file.Files.writeString(new File(dir, "dashboard.html").toPath(), html);

        log.info("Evaluation reports written to {}", dir.getAbsolutePath());
    }

    private double avg(List<EvalResult> results, java.util.function.ToDoubleFunction<EvalResult> fn) {
        return results.stream().mapToDouble(fn).average().orElse(0);
    }
}
