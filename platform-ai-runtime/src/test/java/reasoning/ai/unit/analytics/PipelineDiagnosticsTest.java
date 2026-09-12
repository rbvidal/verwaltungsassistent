package reasoning.ai.unit.analytics;

import reasoning.ai.analytics.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PipelineDiagnostics")
class PipelineDiagnosticsTest {

    private AnalyticsRepository repository;
    private AnalyticsService analyticsService;
    private PipelineDiagnostics diagnostics;

    @BeforeEach
    void setUp() {
        repository = new AnalyticsRepository(100);
        analyticsService = new AnalyticsService(repository);
        diagnostics = new PipelineDiagnostics(
                repository, analyticsService,
                5,   // baselineWindowSize (small for testing)
                5,   // currentWindowSize
                0.20, // latencyDegradationThreshold
                0.10, // confidenceDegradationThreshold
                0.05, // rateDegradationThreshold (5 pp)
                0.10, // coverageDegradationThreshold (10 pp)
                0.25  // retrievalVolumeDegradationThreshold
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. Insufficient data
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("insufficient data: empty repository")
    void emptyRepository() {
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertTrue(report.insufficientData());
        assertEquals("INSUFFICIENT_DATA", report.overallHealth());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    @DisplayName("insufficient data: single run")
    void singleRun() {
        repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertTrue(report.insufficientData());
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. Stable metrics — no findings
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("stable metrics: no regressions detected")
    void stableMetrics() {
        // 10 runs with identical metrics
        for (int i = 0; i < 10; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertFalse(report.insufficientData());
        assertEquals("GOOD", report.overallHealth());
        assertTrue(report.findings().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. Latency regression
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("latency regression: significant increase in total latency")
    void latencyRegression() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 9000, true, false, 0.80, "success"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertFalse(report.insufficientData());
        assertNotEquals("GOOD", report.overallHealth());

        boolean hasLatencyRegression = report.findings().stream()
                .anyMatch(f -> f.regressionType() == RegressionType.LATENCY_REGRESSION
                        && "Gesamt".equals(f.affectedStage()));
        assertTrue(hasLatencyRegression, "Should detect latency regression");
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. Confidence regression
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("confidence regression: significant drop in model confidence")
    void confidenceRegression() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.90, 5000, true, false, 0.80, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.70, 5000, true, false, 0.80, "success"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        boolean hasConfidenceRegression = report.findings().stream()
                .anyMatch(f -> f.regressionType() == RegressionType.CONFIDENCE_REGRESSION);
        assertTrue(hasConfidenceRegression, "Should detect confidence regression");
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. Evidence coverage regression
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("evidence coverage regression: significant drop")
    void evidenceCoverageRegression() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.85, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.55, "success"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        boolean hasCoverageRegression = report.findings().stream()
                .anyMatch(f -> f.regressionType() == RegressionType.EVIDENCE_COVERAGE_REGRESSION);
        assertTrue(hasCoverageRegression, "Should detect evidence coverage regression");
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. Verification regression
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("verification regression: more failures in current window")
    void verificationRegression() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        for (int i = 0; i < 5; i++) {
            boolean passed = i < 2; // 2 of 5 pass = 60% failure
            repository.store(baseMetrics(0.85, 5000, passed, false, 0.80, passed ? "success" : "failed"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        boolean hasVerificationRegression = report.findings().stream()
                .anyMatch(f -> f.regressionType() == RegressionType.VERIFICATION_REGRESSION);
        assertTrue(hasVerificationRegression, "Should detect verification regression");
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. Repair regression
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("repair regression: repair frequency increased")
    void repairRegression() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, true, 0.80, "repaired"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        boolean hasRepairRegression = report.findings().stream()
                .anyMatch(f -> f.regressionType() == RegressionType.REPAIR_REGRESSION);
        assertTrue(hasRepairRegression, "Should detect repair regression");
    }

    // ═══════════════════════════════════════════════════════════════
    // 8. Bottleneck detection
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bottleneck detection: one stage dominates latency")
    void bottleneckDetection() {
        for (int i = 0; i < 5; i++) {
            PipelineRunMetrics metrics = PipelineRunMetrics.builder()
                    .confidence(0.85).totalLatencyMs(5000).verificationPassed(true)
                    .outcome("success").evidenceCoverage(0.80)
                    .retrievalLatencyMs(2000)
                    .addStage(StageMetrics.ok("retrieval", 2000))
                    .addStage(StageMetrics.ok("llm", 1000))
                    .build();
            repository.store(metrics);
        }
        for (int i = 0; i < 5; i++) {
            PipelineRunMetrics metrics = PipelineRunMetrics.builder()
                    .confidence(0.85).totalLatencyMs(8000).verificationPassed(true)
                    .outcome("success").evidenceCoverage(0.80)
                    .retrievalLatencyMs(5000)
                    .addStage(StageMetrics.ok("retrieval", 5000))
                    .addStage(StageMetrics.ok("llm", 1000))
                    .build();
            repository.store(metrics);
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        // Bottleneck should be retrieval since it dominates
        assertNotNull(report.primaryBottleneck());
    }

    // ═══════════════════════════════════════════════════════════════
    // 9. Correlation hints: verification + coverage
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("correlation: verification failures correlate with coverage drop")
    void correlationVerificationAndCoverage() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.85, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, false, false, 0.50, "failed"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        boolean hasVerificationCoverageCorrelation = report.correlatedSignals().stream()
                .anyMatch(f -> f.explanation().contains("Evidenz-Abdeckung")
                        && f.explanation().contains("Verifikation"));
        assertTrue(hasVerificationCoverageCorrelation,
                "Should detect correlation between verification failures and coverage drop");
    }

    // ═══════════════════════════════════════════════════════════════
    // 10. No false causal claims
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("regression findings use OBSERVED_REGRESSION, not CONFIRMED_CAUSE")
    void noFalseCausalClaims() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.90, 5000, true, false, 0.85, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.70, 9000, false, true, 0.50, "failed"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        // All observed regressions should be OBSERVED_REGRESSION, not CONFIRMED_CAUSE
        for (DiagnosticFinding f : report.findings()) {
            if (f.causalRelation() == CausalRelation.OBSERVED_REGRESSION) {
                // This is correct — regressions are observations
            }
            // No finding should claim CONFIRMED_CAUSE since we don't do that
            assertNotEquals(CausalRelation.CONFIRMED_CAUSE, f.causalRelation(),
                    "No finding should claim CONFIRMED_CAUSE: " + f.explanation());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 11. Baseline/current comparison structure
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("report contains correct baseline and current run counts")
    void baselineCurrentWindowCounts() {
        for (int i = 0; i < 10; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertEquals(5, report.baselineRunCount());
        assertEquals(5, report.currentRunCount());
    }

    // ═══════════════════════════════════════════════════════════════
    // 12. Empty repository
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("empty repository returns insufficient data")
    void emptyRepository_insufficientData() {
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertTrue(report.insufficientData());
        assertNull(report.primaryRegression());
        assertNull(report.primaryBottleneck());
    }

    // ═══════════════════════════════════════════════════════════════
    // 13. Single-run repository
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("single run: insufficient data")
    void singleRunRepository() {
        repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        PipelineDiagnosticReport report = diagnostics.diagnose();
        assertTrue(report.insufficientData());
        assertEquals("INSUFFICIENT_DATA", report.overallHealth());
    }

    // ═══════════════════════════════════════════════════════════════
    // 14. Mixed outcomes (success/repaired/failed)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("mixed outcomes: correctly handles success/repaired/failed runs")
    void mixedOutcomes() {
        for (int i = 0; i < 3; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        repository.store(baseMetrics(0.70, 6000, false, true, 0.60, "repaired"));
        repository.store(baseMetrics(0.60, 7000, false, false, 0.40, "failed"));
        // Current window with degradation
        for (int i = 0; i < 3; i++) {
            repository.store(baseMetrics(0.80, 5500, true, false, 0.75, "success"));
        }
        repository.store(baseMetrics(0.50, 7500, false, true, 0.30, "repaired"));
        repository.store(baseMetrics(0.40, 8000, false, false, 0.20, "failed"));
        PipelineDiagnosticReport report = diagnostics.diagnose();
        // Verify we get a report even with mixed data
        assertNotNull(report);
        assertFalse(report.insufficientData());
    }

    // ═══════════════════════════════════════════════════════════════
    // 15. DiagnosticFinding explanation is deterministic
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findings have deterministic human-readable explanations")
    void findingsHaveDeterministicExplanations() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 5000, true, false, 0.80, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.85, 9000, true, false, 0.80, "success"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        for (DiagnosticFinding f : report.findings()) {
            assertNotNull(f.explanation());
            assertFalse(f.explanation().isBlank(), "Explanation must not be blank");
            // Explanation must contain numeric values, not just placeholder text
            if (f.causalRelation() == CausalRelation.OBSERVED_REGRESSION) {
                assertTrue(f.explanation().contains("%") || f.explanation().contains("ms"),
                        "Explanation should contain units: " + f.explanation());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 16. Detection confidence is always set
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("all findings have detection confidence between 0 and 1")
    void detectionConfidenceRange() {
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.90, 5000, true, false, 0.85, "success"));
        }
        for (int i = 0; i < 5; i++) {
            repository.store(baseMetrics(0.60, 9000, false, true, 0.40, "failed"));
        }
        PipelineDiagnosticReport report = diagnostics.diagnose();
        for (DiagnosticFinding f : report.findings()) {
            assertTrue(f.detectionConfidence() >= 0.0 && f.detectionConfidence() <= 1.0,
                    "Detection confidence must be in [0,1]: " + f.detectionConfidence());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════════

    private PipelineRunMetrics baseMetrics(double confidence, long totalLatencyMs,
                                            boolean verificationPassed, boolean repairActivated,
                                            double evidenceCoverage, String outcome) {
        return PipelineRunMetrics.builder()
                .confidence(confidence)
                .totalLatencyMs(totalLatencyMs)
                .verificationPassed(verificationPassed)
                .repairActivated(repairActivated)
                .evidenceCoverage(evidenceCoverage)
                .outcome(outcome)
                .retrievalLatencyMs(totalLatencyMs / 4)
                .llmLatencyMs(totalLatencyMs / 2)
                .mergedCandidates(5)
                .promptTokens(2000)
                .addStage(StageMetrics.ok("retrieval", totalLatencyMs / 4))
                .addStage(StageMetrics.ok("llm", totalLatencyMs / 2))
                .addStage(StageMetrics.ok("verification", 0))
                .build();
    }
}
