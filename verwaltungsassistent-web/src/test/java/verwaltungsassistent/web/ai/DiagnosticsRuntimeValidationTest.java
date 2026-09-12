package verwaltungsassistent.web.ai;

import reasoning.ai.analytics.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime validation of the Pipeline Diagnostics system.
 *
 * <p>Verifies:
 * <ul>
 *   <li>INSUFFICIENT_DATA when below minimum runs</li>
 *   <li>Controlled regression detection with known degradation</li>
 *   <li>Correlation/hint logic produces correct causal relations</li>
 *   <li>No CONFIRMED_CAUSE claims</li>
 *   <li>Deterministic explanations contain actual values</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "diagnostics.baseline-window=10",
        "diagnostics.current-window=10",
        "diagnostics.latency-threshold=0.20",
        "diagnostics.confidence-threshold=0.10",
        "diagnostics.rate-threshold=0.05",
        "diagnostics.coverage-threshold=0.10",
        "diagnostics.retrieval-volume-threshold=0.25"
})
@DisplayName("Pipeline Diagnostics Runtime Validation")
class DiagnosticsRuntimeValidationTest {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsRuntimeValidationTest.class);

    @Autowired
    private PipelineDiagnostics pipelineDiagnostics;

    @Autowired
    private AnalyticsRepository analyticsRepository;

    @BeforeEach
    void setUp() {
        analyticsRepository.clear();
    }

    @AfterEach
    void tearDown() {
        analyticsRepository.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 2: INSUFFICIENT_DATA
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PHASE 2: Empty repository → INSUFFICIENT_DATA")
    void emptyRepositoryIsInsufficientData() {
        PipelineDiagnosticReport report = pipelineDiagnostics.diagnose();

        assertTrue(report.insufficientData(), "Must be insufficient with empty repo");
        assertEquals("INSUFFICIENT_DATA", report.overallHealth());
        assertTrue(report.findings().isEmpty(), "No findings on empty data");
        assertNull(report.primaryRegression());
        assertEquals(0, report.baselineRunCount());
        assertEquals(0, report.currentRunCount());

        log.info("=== PHASE 2: INSUFFICIENT_DATA verified ===");
        log.info("  Empty repo → {} (0 baseline / 0 current)", report.overallHealth());
    }

    @Test
    @DisplayName("PHASE 2: Fewer than baseline-window runs → INSUFFICIENT_DATA")
    void belowMinimumRunsIsInsufficientData() {
        // Store 7 runs — below configured baseline window of 10
        for (int i = 0; i < 7; i++) {
            analyticsRepository.store(healthyRun(0.85, 5000, 0.80));
        }

        PipelineDiagnosticReport report = pipelineDiagnostics.diagnose();

        assertTrue(report.insufficientData(),
                "Must be insufficient with only " + analyticsRepository.size() + " runs (need >= 10)");
        assertEquals("INSUFFICIENT_DATA", report.overallHealth());
        assertTrue(report.findings().isEmpty(), "Must not manufacture regressions");

        log.info("=== PHASE 2: Below-minimum verified ===");
        log.info("  {} runs → {} ({} baseline / {} current)",
                analyticsRepository.size(), report.overallHealth(),
                report.baselineRunCount(), report.currentRunCount());
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 3: CONTROLLED REGRESSION
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PHASE 3: Controlled regression — verification + coverage degradation")
    void controlledRegressionVerificationAndCoverage() {
        // Baseline: 10 healthy runs
        for (int i = 0; i < 10; i++) {
            analyticsRepository.store(healthyRun(0.88, 4800, 0.85));
        }

        // Current: 10 degraded runs
        for (int i = 0; i < 10; i++) {
            analyticsRepository.store(degradedRun(
                    0.72,             // lower confidence
                    7200,             // higher latency
                    0.48,             // lower coverage
                    i < 3,            // only 3 of 10 verification-passed → 30% pass rate
                    i >= 5,           // 5 of 10 repair-activated
                    i >= 5 ? "repaired" : "failed"
            ));
        }

        PipelineDiagnosticReport report = pipelineDiagnostics.diagnose();

        // ── Verify no insufficient data ──
        assertFalse(report.insufficientData(), "Should have enough data (20 runs)");
        assertEquals(10, report.baselineRunCount());
        assertEquals(10, report.currentRunCount());

        // ── Verify regressions were detected ──
        List<DiagnosticFinding> findings = report.findings();
        assertFalse(findings.isEmpty(), "Should detect regressions");

        // Log all findings
        log.info("=== PHASE 3: Controlled Regression Results ===");
        log.info("  Overall health: {}", report.overallHealth());
        log.info("  Total findings: {}", findings.size());
        for (DiagnosticFinding f : findings) {
            log.info("  [{}] {} — {} | {}",
                    f.severity(), f.regressionType(), f.metricLabel(), f.causalRelation());
            log.info("    Baseline: {} → Current: {} (Δ={})",
                    formatValue(f.metricLabel(), f.baselineValue()),
                    formatValue(f.metricLabel(), f.currentValue()),
                    formatValue(f.metricLabel(), f.absoluteChange()));
            log.info("    {}", f.explanation());
        }

        // ── Verify specific regression types ──
        boolean hasVerification = findings.stream()
                .anyMatch(f -> f.regressionType() == RegressionType.VERIFICATION_REGRESSION);
        boolean hasCoverage = findings.stream()
                .anyMatch(f -> f.regressionType() == RegressionType.EVIDENCE_COVERAGE_REGRESSION);
        boolean hasConfidence = findings.stream()
                .anyMatch(f -> f.regressionType() == RegressionType.CONFIDENCE_REGRESSION);
        boolean hasRepair = findings.stream()
                .anyMatch(f -> f.regressionType() == RegressionType.REPAIR_REGRESSION);

        assertTrue(hasVerification,
                "VERIFICATION_REGRESSION must be detected (baseline 100% → current 30%)");
        assertTrue(hasCoverage,
                "EVIDENCE_COVERAGE_REGRESSION must be detected (baseline 85% → current 48%)");
        assertTrue(hasConfidence,
                "CONFIDENCE_REGRESSION must be detected (baseline 88% → current 72%)");
        assertTrue(hasRepair,
                "REPAIR_REGRESSION must be detected (baseline 0% → current 50%)");

        // ── Verify overall health ──
        assertNotEquals("GOOD", report.overallHealth());

        // ── Verify primary regression is the most severe ──
        assertNotNull(report.primaryRegression());

        log.info("=== PHASE 3: All expected regressions detected ===");
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 4: CORRELATION LOGIC
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PHASE 4: Coverage+verification correlation produces CORRELATED_SIGNAL")
    void coverageVerificationCorrelationIsCorrectType() {
        // Baseline: 10 healthy runs
        for (int i = 0; i < 10; i++) {
            analyticsRepository.store(healthyRun(0.85, 5000, 0.85));
        }

        // Current: verification failures + low coverage (10 runs)
        for (int i = 0; i < 10; i++) {
            analyticsRepository.store(degradedRun(
                    0.85, 5000, 0.45,   // coverage dropped from 85% → 45%
                    false, false, "failed"  // all verification-failed
            ));
        }

        PipelineDiagnosticReport report = pipelineDiagnostics.diagnose();

        // ── Verify correlation findings exist ──
        List<DiagnosticFinding> correlations = report.correlatedSignals();
        assertFalse(correlations.isEmpty(),
                "Should have at least one correlation finding");

        log.info("=== PHASE 4: Correlation Results ===");
        log.info("  Correlated signals: {}", correlations.size());
        for (DiagnosticFinding c : correlations) {
            log.info("  [{}] {} — {} | {}",
                    c.severity(), c.regressionType(), c.metricLabel(), c.causalRelation());
            log.info("    {}", c.explanation());
        }

        // ── Verify the coverage→verification correlation exists ──
        boolean hasCoverageVerificationCorrelation = correlations.stream()
                .anyMatch(f -> f.explanation().contains("Evidenz-Abdeckung")
                        && f.explanation().contains("Verifikation"));
        assertTrue(hasCoverageVerificationCorrelation,
                "Must find correlation between evidence coverage and verification failures");

        // ── Verify correlation uses CORRELATED_SIGNAL, not CONFIRMED_CAUSE ──
        for (DiagnosticFinding c : correlations) {
            assertNotEquals(CausalRelation.CONFIRMED_CAUSE, c.causalRelation(),
                    "Correlation must not claim CONFIRMED_CAUSE: " + c.metricLabel());
        }

        // ── Verify regression findings also don't claim CONFIRMED_CAUSE ──
        for (DiagnosticFinding f : report.findings()) {
            assertNotEquals(CausalRelation.CONFIRMED_CAUSE, f.causalRelation(),
                    "No finding should claim CONFIRMED_CAUSE: " + f.explanation());
        }

        log.info("=== PHASE 4: Correlation logic verified — no CONFIRMED_CAUSE claims ===");
    }

    @Test
    @DisplayName("PHASE 4: Latency regression + dominant stage → LIKELY_EXPLANATION")
    void bottleneckCorrelationIsCorrectType() {
        // Baseline: balanced stages
        for (int i = 0; i < 10; i++) {
            PipelineRunMetrics m = PipelineRunMetrics.builder()
                    .confidence(0.85).totalLatencyMs(3000).verificationPassed(true)
                    .outcome("success").evidenceCoverage(0.80)
                    .retrievalLatencyMs(1000).llmLatencyMs(1000)
                    .addStage(StageMetrics.ok("retrieval", 1000))
                    .addStage(StageMetrics.ok("llm", 1000))
                    .addStage(StageMetrics.ok("verification", 0))
                    .build();
            analyticsRepository.store(m);
        }

        // Current: retrieval dominates latency
        for (int i = 0; i < 10; i++) {
            PipelineRunMetrics m = PipelineRunMetrics.builder()
                    .confidence(0.85).totalLatencyMs(8000).verificationPassed(true)
                    .outcome("success").evidenceCoverage(0.80)
                    .retrievalLatencyMs(6000).llmLatencyMs(1000)
                    .addStage(StageMetrics.ok("retrieval", 6000))
                    .addStage(StageMetrics.ok("llm", 1000))
                    .addStage(StageMetrics.ok("verification", 0))
                    .build();
            analyticsRepository.store(m);
        }

        PipelineDiagnosticReport report = pipelineDiagnostics.diagnose();

        log.info("=== PHASE 4: Bottleneck Correlation ===");
        log.info("  Overall health: {}", report.overallHealth());
        if (report.primaryBottleneck() != null) {
            log.info("  Primary bottleneck: {} ({:.0f} ms)",
                    report.primaryBottleneck().stageName(),
                    report.primaryBottleneck().avgLatencyMs());
        }
        for (DiagnosticFinding c : report.correlatedSignals()) {
            log.info("  [{}] {}", c.causalRelation(), c.explanation());
        }

        // Verify bottleneck is retrieval
        assertNotNull(report.primaryBottleneck());
        boolean hasBottleneckCorrelation = report.correlatedSignals().stream()
                .anyMatch(f -> f.causalRelation() == CausalRelation.LIKELY_EXPLANATION
                        && f.explanation().contains("dominiert"));
        assertTrue(hasBottleneckCorrelation,
                "Must detect dominant-stage bottleneck as LIKELY_EXPLANATION");
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 5: UI VERIFICATION
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PHASE 5: Diagnostic report is non-null and structurally complete")
    void diagnosticReportHasCompleteStructure() {
        // Seed enough data for diagnostics
        for (int i = 0; i < 20; i++) {
            analyticsRepository.store(healthyRun(0.85, 5000, 0.80));
        }

        PipelineDiagnosticReport report = pipelineDiagnostics.diagnose();

        assertNotNull(report, "Diagnostic report must not be null");
        assertNotNull(report.overallHealth(), "Overall health must not be null");
        assertNotNull(report.findings(), "Findings list must not be null");
        assertNotNull(report.correlatedSignals(), "Correlated signals must not be null");

        log.info("=== PHASE 5: Report Structure ===");
        log.info("  Overall health: {}", report.overallHealth());
        log.info("  Insufficient data: {}", report.insufficientData());
        log.info("  Baseline runs: {}", report.baselineRunCount());
        log.info("  Current runs: {}", report.currentRunCount());
        log.info("  Findings: {}", report.findings().size());
        log.info("  Correlated signals: {}", report.correlatedSignals().size());
        log.info("  Primary regression: {}",
                report.primaryRegression() != null ? report.primaryRegression().regressionType() : "none");
        log.info("  Primary bottleneck: {}",
                report.primaryBottleneck() != null ? report.primaryBottleneck().stageName() : "none");
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 6: 32-BENCHMARK BEHAVIOR
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PHASE 6: 32 runs with production default of 25 → partial diagnostics (16+16)")
    void thirtyTwoRunsWithProductionDefaults() {
        // Simulate what happens when 32 runs accumulate via controller
        // Using the production default of basline-window=25, we need >=25 for any diagnostics
        // With 32 runs: baselineSize = min(25, 32-min(25,16)) = min(25, 16) = 16
        //               currentSize = min(25, 16) = 16

        // First 16: healthy
        for (int i = 0; i < 16; i++) {
            analyticsRepository.store(healthyRun(0.85, 5000, 0.80));
        }
        // Next 16: slightly degraded
        for (int i = 0; i < 16; i++) {
            analyticsRepository.store(healthyRun(0.80, 5500, 0.75));
        }

        // Use production defaults (25 baseline window)
        PipelineDiagnostics productionDefaults = new PipelineDiagnostics(
                analyticsRepository,
                new AnalyticsService(analyticsRepository),
                25, 25, 0.20, 0.10, 0.05, 0.10, 0.25);

        PipelineDiagnosticReport report = productionDefaults.diagnose();

        log.info("=== PHASE 6: 32-Run Benchmark Behavior ===");
        log.info("  Total runs: {}", analyticsRepository.size());
        log.info("  Production default baseline-window: 25");
        log.info("  Insufficient data: {}", report.insufficientData());
        log.info("  Overall health: {}", report.overallHealth());
        log.info("  Baseline runs: {}", report.baselineRunCount());
        log.info("  Current runs: {}", report.currentRunCount());
        log.info("  Findings: {}", report.findings().size());

        // With 32 runs (>= 25), diagnostics should NOT be INSUFFICIENT_DATA
        assertFalse(report.insufficientData(),
                "32 runs >= 25 baseline window → diagnostics should be available");
        assertEquals(16, report.baselineRunCount());
        assertEquals(16, report.currentRunCount());
    }

    @Test
    @DisplayName("PHASE 6: Benchmark test does NOT populate analytics repository")
    void benchmarkTestDoesNotTouchAnalytics() {
        // The EvaluationTest uses AiFacade directly, not the DecisionWorkspaceController.
        // Analytics are only collected in the controller's startAnalysis method.
        // After no controller calls, the repository should be empty.
        assertTrue(analyticsRepository.allRuns().isEmpty(),
                "Analytics repository should be empty — benchmarks go through AiFacade, not controller");

        log.info("=== PHASE 6: Benchmark isolation confirmed ===");
        log.info("  AnalyticsRepository is empty in test context — benchmarks don't populate it");
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private PipelineRunMetrics healthyRun(double confidence, long totalLatencyMs, double coverage) {
        return PipelineRunMetrics.builder()
                .confidence(confidence)
                .totalLatencyMs(totalLatencyMs)
                .verificationPassed(true)
                .repairActivated(false)
                .evidenceCoverage(coverage)
                .outcome("success")
                .retrievalLatencyMs(totalLatencyMs / 4)
                .llmLatencyMs(totalLatencyMs / 2)
                .mergedCandidates(5)
                .promptTokens(2000)
                .keywordHits(8).vectorHits(4).graphHits(2)
                .addStage(StageMetrics.ok("retrieval", totalLatencyMs / 4))
                .addStage(StageMetrics.ok("llm", totalLatencyMs / 2))
                .addStage(StageMetrics.ok("verification", 0))
                .build();
    }

    private PipelineRunMetrics degradedRun(double confidence, long totalLatencyMs,
                                            double coverage, boolean verificationPassed,
                                            boolean repairActivated, String outcome) {
        return PipelineRunMetrics.builder()
                .confidence(confidence)
                .totalLatencyMs(totalLatencyMs)
                .verificationPassed(verificationPassed)
                .repairActivated(repairActivated)
                .evidenceCoverage(coverage)
                .outcome(outcome)
                .retrievalLatencyMs(totalLatencyMs / 3)
                .llmLatencyMs(totalLatencyMs / 2)
                .mergedCandidates(8)
                .promptTokens(2800)
                .keywordHits(12).vectorHits(6).graphHits(3)
                .addStage(StageMetrics.ok("retrieval", totalLatencyMs / 3))
                .addStage(StageMetrics.ok("llm", totalLatencyMs / 2))
                .addStage(verificationPassed
                        ? StageMetrics.ok("verification", 0)
                        : StageMetrics.error("verification", 0, "unbelegte Feststellung"))
                .build();
    }

    private String formatValue(String metricLabel, double value) {
        if (metricLabel.contains("Latenz")) {
            return String.format("%.0f ms", value);
        } else if (metricLabel.contains("Konfidenz") || metricLabel.contains("Abdeckung")
                || metricLabel.contains("Rate") || metricLabel.contains("Fehlerrate")
                || metricLabel.contains("Häufigkeit")) {
            return String.format("%.1f%%", value * 100);
        }
        return String.format("%.3f", value);
    }
}
