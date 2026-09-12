package reasoning.ai.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Observational diagnostics built on existing PipelineRunMetrics data.
 *
 * <p>No LLM calls, no retrieval, no pipeline modification.
 * Pure deterministic comparison of recent runs vs baseline window
 * with root-cause hint correlation.
 */
@Service
public class PipelineDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(PipelineDiagnostics.class);

    private final AnalyticsRepository repository;
    private final AnalyticsService analyticsService;

    // Configurable thresholds
    private final int baselineWindowSize;
    private final int currentWindowSize;
    private final double latencyDegradationThreshold;     // relative (e.g. 0.20 = 20% increase)
    private final double confidenceDegradationThreshold;   // relative
    private final double rateDegradationThreshold;         // absolute pp (e.g. 0.05 = 5 pp drop)
    private final double coverageDegradationThreshold;     // absolute pp
    private final double retrievalVolumeDegradationThreshold; // relative

    public PipelineDiagnostics(
            AnalyticsRepository repository,
            AnalyticsService analyticsService,
            @Value("${diagnostics.baseline-window:25}") int baselineWindowSize,
            @Value("${diagnostics.current-window:25}") int currentWindowSize,
            @Value("${diagnostics.latency-threshold:0.20}") double latencyDegradationThreshold,
            @Value("${diagnostics.confidence-threshold:0.10}") double confidenceDegradationThreshold,
            @Value("${diagnostics.rate-threshold:0.05}") double rateDegradationThreshold,
            @Value("${diagnostics.coverage-threshold:0.10}") double coverageDegradationThreshold,
            @Value("${diagnostics.retrieval-volume-threshold:0.25}") double retrievalVolumeDegradationThreshold) {
        this.repository = repository;
        this.analyticsService = analyticsService;
        this.baselineWindowSize = baselineWindowSize;
        this.currentWindowSize = currentWindowSize;
        this.latencyDegradationThreshold = latencyDegradationThreshold;
        this.confidenceDegradationThreshold = confidenceDegradationThreshold;
        this.rateDegradationThreshold = rateDegradationThreshold;
        this.coverageDegradationThreshold = coverageDegradationThreshold;
        this.retrievalVolumeDegradationThreshold = retrievalVolumeDegradationThreshold;
    }

    /**
     * Builds a complete diagnostic report comparing recent runs against a baseline window.
     */
    public PipelineDiagnosticReport diagnose() {
        List<PipelineRunMetrics> allRuns = repository.allRuns();
        int minRequired = baselineWindowSize + currentWindowSize;

        if (allRuns.size() < Math.max(2, baselineWindowSize)) {
            return new PipelineDiagnosticReport(
                    "INSUFFICIENT_DATA", List.of(), null, null, List.of(),
                    true, Math.min(allRuns.size(), baselineWindowSize), 0);
        }

        // Split: baseline = older runs, current = recent runs
        int total = allRuns.size();
        int currentSize = Math.min(currentWindowSize, total / 2);
        int baselineSize = Math.min(baselineWindowSize, total - currentSize);

        if (currentSize < 2 || baselineSize < 2) {
            return new PipelineDiagnosticReport(
                    "INSUFFICIENT_DATA", List.of(), null, null, List.of(),
                    true, baselineSize, currentSize);
        }

        List<PipelineRunMetrics> baseline = allRuns.subList(0, baselineSize);
        List<PipelineRunMetrics> current = allRuns.subList(total - currentSize, total);

        List<DiagnosticFinding> findings = new ArrayList<>();

        // ── Metric comparisons ──
        compareConfidence(baseline, current).ifPresent(findings::add);
        compareLatency(baseline, current).ifPresent(findings::add);
        compareVerification(baseline, current).ifPresent(findings::add);
        compareEvidenceCoverage(baseline, current).ifPresent(findings::add);
        compareRepairRate(baseline, current).ifPresent(findings::add);
        compareRetrievalLatency(baseline, current).ifPresent(findings::add);
        compareOutcomeRate(baseline, current).ifPresent(findings::add);
        findings.addAll(compareStageHealth(baseline, current));

        // ── Correlation hints ──
        List<DiagnosticFinding> correlations = generateCorrelations(findings, baseline, current);

        // ── Determine primary regression ──
        DiagnosticFinding primaryRegression = findings.stream()
                .filter(f -> f.causalRelation() == CausalRelation.OBSERVED_REGRESSION)
                .max(Comparator.comparingInt(f -> f.severity().ordinal()))
                .orElse(null);

        // ── Bottleneck ──
        List<StageHealth> allStageHealth = analyticsService.stageHealth();
        StageHealth bottleneck = allStageHealth.stream()
                .max(Comparator.comparingDouble(StageHealth::avgLatencyMs))
                .orElse(null);

        // ── Overall health ──
        String overall = "GOOD";
        if (!findings.isEmpty()) {
            boolean hasCritical = findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
            overall = hasCritical ? "CRITICAL" : "WARNING";
        }

        return new PipelineDiagnosticReport(
                overall, findings, primaryRegression, bottleneck,
                correlations, false, baselineSize, currentSize);
    }

    // ═══════════════════════════════════════════════════════════════
    // Metric comparisons
    // ═══════════════════════════════════════════════════════════════

    private Optional<DiagnosticFinding> compareConfidence(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double base = avg(baseline, PipelineRunMetrics::confidence);
        double curr = avg(current, PipelineRunMetrics::confidence);
        if (base <= 0) return Optional.empty();
        double relChange = (base - curr) / base;
        if (relChange > confidenceDegradationThreshold) {
            return Optional.of(new DiagnosticFinding(
                    RegressionType.CONFIDENCE_REGRESSION,
                    "Ø Konfidenz",
                    base, curr, base - curr, relChange,
                    "decreased", "LLM Generation",
                    relChange > confidenceDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.85,
                    String.format(Locale.ROOT, "Durchschnittliche Modellkonfidenz sank von %.0f%% auf %.0f%% (%.0f%% relativ)",
                            base * 100, curr * 100, relChange * 100),
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private Optional<DiagnosticFinding> compareLatency(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double base = avg(baseline, m -> (double) m.totalLatencyMs());
        double curr = avg(current, m -> (double) m.totalLatencyMs());
        if (base <= 0) return Optional.empty();
        double relChange = (curr - base) / base;
        if (relChange > latencyDegradationThreshold) {
            return Optional.of(new DiagnosticFinding(
                    RegressionType.LATENCY_REGRESSION,
                    "Ø Gesamtlatenz",
                    base, curr, curr - base, relChange,
                    "increased", "Gesamt",
                    relChange > latencyDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.80,
                    String.format(Locale.ROOT, "Gesamtlatenz stieg von %.0f ms auf %.0f ms (+%.0f%%)",
                            base, curr, relChange * 100),
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private Optional<DiagnosticFinding> compareVerification(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double base = successRate(baseline);
        double curr = successRate(current);
        double absChange = base - curr;
        if (absChange > rateDegradationThreshold) {
            return Optional.of(new DiagnosticFinding(
                    RegressionType.VERIFICATION_REGRESSION,
                    "Verifikations-Erfolgsrate",
                    base, curr, absChange, base > 0 ? absChange / base : 0,
                    "decreased", "Verification",
                    absChange > rateDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.80,
                    String.format(Locale.ROOT, "Verifikations-Erfolgsrate fiel von %.0f%% auf %.0f%% (-%.0f pp)",
                            base * 100, curr * 100, absChange * 100),
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private Optional<DiagnosticFinding> compareEvidenceCoverage(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double base = avg(baseline, PipelineRunMetrics::evidenceCoverage);
        double curr = avg(current, PipelineRunMetrics::evidenceCoverage);
        double absChange = base - curr;
        if (absChange > coverageDegradationThreshold) {
            return Optional.of(new DiagnosticFinding(
                    RegressionType.EVIDENCE_COVERAGE_REGRESSION,
                    "Ø Evidenz-Abdeckung",
                    base, curr, absChange, base > 0 ? absChange / base : 0,
                    "decreased", "Evidence Package",
                    absChange > coverageDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.80,
                    String.format(Locale.ROOT, "Evidenz-Abdeckung fiel von %.0f%% auf %.0f%% (-%.0f pp)",
                            base * 100, curr * 100, absChange * 100),
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private Optional<DiagnosticFinding> compareRepairRate(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double base = repairRate(baseline);
        double curr = repairRate(current);
        double absChange = curr - base;
        if (absChange > rateDegradationThreshold) {
            return Optional.of(new DiagnosticFinding(
                    RegressionType.REPAIR_REGRESSION,
                    "Reparatur-Häufigkeit",
                    base, curr, absChange, base > 0 ? absChange / base : 0,
                    "increased", "Repair Engine",
                    absChange > rateDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.75,
                    String.format(Locale.ROOT, "Reparatur-Häufigkeit stieg von %.0f%% auf %.0f%% (+%.0f pp)",
                            base * 100, curr * 100, absChange * 100),
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private Optional<DiagnosticFinding> compareRetrievalLatency(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double base = avg(baseline, m -> (double) m.retrievalLatencyMs());
        double curr = avg(current, m -> (double) m.retrievalLatencyMs());
        if (base <= 0) return Optional.empty();
        double relChange = (curr - base) / base;

        // Also check candidate volume
        double baseCandidates = avg(baseline, m -> (double) m.mergedCandidates());
        double currCandidates = avg(current, m -> (double) m.mergedCandidates());

        if (relChange > latencyDegradationThreshold) {
            String explanation = String.format(Locale.ROOT,
                    "Retrieval-Latenz stieg von %.0f ms auf %.0f ms (+%.0f%%)",
                    base, curr, relChange * 100);
            if (currCandidates > baseCandidates * (1 + retrievalVolumeDegradationThreshold)) {
                explanation += String.format(Locale.ROOT,
                        ". Kandidaten-Volumen stieg ebenfalls (%.0f → %.0f).",
                        baseCandidates, currCandidates);
            }
            return Optional.of(new DiagnosticFinding(
                    RegressionType.RETRIEVAL_REGRESSION,
                    "Ø Retrieval-Latenz",
                    base, curr, curr - base, relChange,
                    "increased", "Retrieval",
                    relChange > latencyDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.80,
                    explanation,
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private Optional<DiagnosticFinding> compareOutcomeRate(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        double baseFail = failureRate(baseline);
        double currFail = failureRate(current);
        double absChange = currFail - baseFail;
        if (absChange > rateDegradationThreshold) {
            return Optional.of(new DiagnosticFinding(
                    RegressionType.OUTCOME_REGRESSION,
                    "Fehlerrate (Outcome)",
                    baseFail, currFail, absChange, baseFail > 0 ? absChange / baseFail : 0,
                    "increased", "Gesamt",
                    absChange > rateDegradationThreshold * 2 ? Severity.CRITICAL : Severity.WARNING,
                    0.70,
                    String.format(Locale.ROOT, "Fehlerrate stieg von %.0f%% auf %.0f%% (+%.0f pp)",
                            baseFail * 100, currFail * 100, absChange * 100),
                    CausalRelation.OBSERVED_REGRESSION));
        }
        return Optional.empty();
    }

    private List<DiagnosticFinding> compareStageHealth(List<PipelineRunMetrics> baseline, List<PipelineRunMetrics> current) {
        List<DiagnosticFinding> results = new ArrayList<>();

        Set<String> stageNames = new LinkedHashSet<>();
        for (PipelineRunMetrics m : baseline) {
            for (StageMetrics s : m.stages()) stageNames.add(s.stageName());
        }
        for (PipelineRunMetrics m : current) {
            for (StageMetrics s : m.stages()) stageNames.add(s.stageName());
        }

        for (String name : stageNames) {
            double baseErr = stageErrorRate(baseline, name);
            double currErr = stageErrorRate(current, name);
            // Only flag if we have actual errors in current window
            if (currErr > 0 && (currErr - baseErr) > rateDegradationThreshold) {
                results.add(new DiagnosticFinding(
                        RegressionType.STAGE_FAILURE_REGRESSION,
                        "Fehlerrate: " + name,
                        baseErr, currErr, currErr - baseErr, baseErr > 0 ? (currErr - baseErr) / baseErr : 1.0,
                        "increased", name,
                        currErr > rateDegradationThreshold * 3 ? Severity.CRITICAL : Severity.WARNING,
                        0.70,
                        String.format(Locale.ROOT, "Fehlerrate in Stage '%s' stieg von %.0f%% auf %.0f%%",
                                name, baseErr * 100, currErr * 100),
                        CausalRelation.OBSERVED_REGRESSION));
            }

            double baseLat = avgStageLatency(baseline, name);
            double currLat = avgStageLatency(current, name);
            if (baseLat > 0 && currLat > baseLat * (1 + latencyDegradationThreshold)) {
                results.add(new DiagnosticFinding(
                        RegressionType.LATENCY_REGRESSION,
                        "Latenz: " + name,
                        baseLat, currLat, currLat - baseLat, (currLat - baseLat) / baseLat,
                        "increased", name,
                        Severity.WARNING,
                        0.75,
                        String.format(Locale.ROOT, "Latenz in Stage '%s' stieg von %.0f ms auf %.0f ms (+%.0f%%)",
                                name, baseLat, currLat, (currLat - baseLat) / baseLat * 100),
                        CausalRelation.OBSERVED_REGRESSION));
            }
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    // Correlation / root-cause hints (deterministic)
    // ═══════════════════════════════════════════════════════════════

    private List<DiagnosticFinding> generateCorrelations(List<DiagnosticFinding> findings,
                                                          List<PipelineRunMetrics> baseline,
                                                          List<PipelineRunMetrics> current) {
        List<DiagnosticFinding> correlations = new ArrayList<>();

        boolean retrievalLatencyUp = hasRegression(findings, RegressionType.RETRIEVAL_REGRESSION, "increased");
        boolean verificationDown = hasRegression(findings, RegressionType.VERIFICATION_REGRESSION, "decreased");
        boolean coverageDown = hasRegression(findings, RegressionType.EVIDENCE_COVERAGE_REGRESSION, "decreased");
        boolean repairUp = hasRegression(findings, RegressionType.REPAIR_REGRESSION, "increased");
        boolean latencyUp = hasRegression(findings, RegressionType.LATENCY_REGRESSION, "increased");
        boolean confidenceDown = hasRegression(findings, RegressionType.CONFIDENCE_REGRESSION, "decreased");

        // Rule 1: retrieval latency ↑ + candidate volume ↑ + prompt size ↑
        boolean candidatesUp = avg(current, m -> (double) m.mergedCandidates())
                > avg(baseline, m -> (double) m.mergedCandidates()) * (1 + retrievalVolumeDegradationThreshold);
        boolean promptSizeUp = avg(current, m -> (double) m.promptTokens())
                > avg(baseline, m -> (double) m.promptTokens()) * (1 + retrievalVolumeDegradationThreshold);

        if (retrievalLatencyUp && (candidatesUp || promptSizeUp)) {
            correlations.add(new DiagnosticFinding(
                    RegressionType.RETRIEVAL_REGRESSION,
                    "Korrelation: Retrieval-Volumen → Latenz",
                    0, 0, 0, 0, "n/a", "Retrieval",
                    Severity.INFO,
                    0.55,
                    "Erhöhtes Retrieval-/Prompt-Volumen korreliert mit der Latenz-Regression. "
                            + "Mehr Kandidaten oder größere Prompts können die Verarbeitungszeit verlängern.",
                    CausalRelation.CORRELATED_SIGNAL));
        }

        // Rule 2: verification failures ↑ + evidence coverage ↓
        if (verificationDown && coverageDown) {
            correlations.add(new DiagnosticFinding(
                    RegressionType.VERIFICATION_REGRESSION,
                    "Korrelation: Evidenz-Abdeckung → Verifikation",
                    0, 0, 0, 0, "n/a", "Verification",
                    Severity.WARNING,
                    0.65,
                    "Reduzierte Evidenz-Abdeckung korreliert mit erhöhten Verifikations-Fehlern. "
                            + "Weniger Belege führen zu mehr unbelegten Feststellungen.",
                    CausalRelation.CORRELATED_SIGNAL));
        }

        // Rule 3: repair frequency ↑ + verification failure rate ↑
        if (repairUp && verificationDown) {
            correlations.add(new DiagnosticFinding(
                    RegressionType.REPAIR_REGRESSION,
                    "Korrelation: Verifikations-Fehler → Reparaturen",
                    0, 0, 0, 0, "n/a", "Repair Engine",
                    Severity.INFO,
                    0.65,
                    "Zunahme an Reparaturen korreliert mit erhöhten Verifikations-Fehlern. "
                            + "Mehr fehlgeschlagene Verifikationen führen zu mehr Reparatur-Versuchen.",
                    CausalRelation.CORRELATED_SIGNAL));
        }

        // Rule 4: overall latency ↑ + one stage dominant
        if (latencyUp) {
            List<StageHealth> stageHealth = analyticsService.stageHealth();
            StageHealth dominant = stageHealth.stream()
                    .max(Comparator.comparingDouble(StageHealth::avgLatencyMs))
                    .orElse(null);
            if (dominant != null && stageHealth.size() > 1) {
                double totalStageLatency = stageHealth.stream()
                        .mapToDouble(StageHealth::avgLatencyMs).sum();
                if (totalStageLatency > 0 && dominant.avgLatencyMs() / totalStageLatency > 0.40) {
                    correlations.add(new DiagnosticFinding(
                            RegressionType.LATENCY_REGRESSION,
                            "Korrelation: Dominante Stage → Latenz",
                            0, 0, 0, 0, "n/a", dominant.stageName(),
                            Severity.INFO,
                            0.60,
                            String.format(Locale.ROOT,
                                    "Stage '%s' dominiert die Gesamtlatenz (%.0f ms, %.0f%% der Summe). "
                                            + "Dies ist wahrscheinlich der primäre Latenztreiber.",
                                    dominant.stageName(), dominant.avgLatencyMs(),
                                    dominant.avgLatencyMs() / totalStageLatency * 100),
                            CausalRelation.LIKELY_EXPLANATION));
                }
            }
        }

        // Rule 5: confidence ↓ + coverage ↓
        if (confidenceDown && coverageDown) {
            correlations.add(new DiagnosticFinding(
                    RegressionType.CONFIDENCE_REGRESSION,
                    "Korrelation: Evidenz-Abdeckung → Konfidenz",
                    0, 0, 0, 0, "n/a", "LLM Generation",
                    Severity.INFO,
                    0.55,
                    "Reduzierte Evidenz-Abdeckung korreliert mit geringerer Modellkonfidenz. "
                            + "Schwächere Beleglage kann zu vorsichtigeren Bewertungen führen.",
                    CausalRelation.CORRELATED_SIGNAL));
        }

        return correlations;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private double avg(List<PipelineRunMetrics> runs, ToDoubleFunction<PipelineRunMetrics> fn) {
        if (runs.isEmpty()) return 0;
        return runs.stream().mapToDouble(fn).average().orElse(0);
    }

    private double successRate(List<PipelineRunMetrics> runs) {
        if (runs.isEmpty()) return 0;
        return (double) runs.stream().filter(PipelineRunMetrics::verificationPassed).count() / runs.size();
    }

    private double repairRate(List<PipelineRunMetrics> runs) {
        if (runs.isEmpty()) return 0;
        return (double) runs.stream().filter(PipelineRunMetrics::repairActivated).count() / runs.size();
    }

    private double failureRate(List<PipelineRunMetrics> runs) {
        if (runs.isEmpty()) return 0;
        return (double) runs.stream().filter(m -> "failed".equals(m.outcome())).count() / runs.size();
    }

    private double stageErrorRate(List<PipelineRunMetrics> runs, String stageName) {
        long total = 0, errors = 0;
        for (PipelineRunMetrics m : runs) {
            for (StageMetrics s : m.stages()) {
                if (s.stageName().equals(stageName)) {
                    total++;
                    if ("error".equals(s.status()) || "warning".equals(s.status())) errors++;
                }
            }
        }
        return total > 0 ? (double) errors / total : 0;
    }

    private double avgStageLatency(List<PipelineRunMetrics> runs, String stageName) {
        return runs.stream()
                .flatMap(m -> m.stages().stream())
                .filter(s -> s.stageName().equals(stageName))
                .mapToLong(StageMetrics::durationMs)
                .average().orElse(0);
    }

    private boolean hasRegression(List<DiagnosticFinding> findings, RegressionType type, String direction) {
        return findings.stream().anyMatch(f -> f.regressionType() == type && direction.equals(f.direction()));
    }
}
