package verwaltungsassistent.web.evaluation;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.PipelineProfiler;
import verwaltungsassistent.web.evaluation.model.EvalModels.EvalSummary;
import verwaltungsassistent.web.evaluation.runner.EvaluationRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-integrated evaluation test.
 *
 * <p>Run with:
 * <pre>mvn test -Dtest=EvaluationTest -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>Set {@code evaluation.failOnRegression=true} to fail the build on regression detection.
 * Set {@code evaluation.skip=true} to skip evaluation during normal development.
 */
@SpringBootTest
@Tag("evaluation")
class EvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTest.class);

    @Autowired(required = false)
    private AiFacade aiFacade;

    @Autowired(required = false)
    private PipelineProfiler profiler;

    @Value("${evaluation.skip:false}")
    private boolean skip;

    @Value("${evaluation.failOnRegression:false}")
    private boolean failOnRegression;

    @Value("${evaluation.benchmarkDir:evaluation/benchmarks}")
    private String benchmarkDir;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void runFullEvaluation() throws Exception {
        if (skip) {
            log.info("Evaluation skipped (evaluation.skip=true)");
            return;
        }

        if (aiFacade == null) {
            log.warn("AiFacade not available — evaluation skipped");
            return;
        }

        log.info("═══════════════════════════════════════════");
        log.info("  Starting Evaluation Run");
        log.info("═══════════════════════════════════════════");

        EvaluationRunner runner = new EvaluationRunner(aiFacade, profiler, mapper);
        EvalSummary summary = runner.runAll(benchmarkDir);

        // Write reports
        runner.writeReports(summary, "target/evaluation-reports");

        // Log summary
        log.info("═══════════════════════════════════════════");
        log.info("  Evaluation Complete");
        log.info("═══════════════════════════════════════════");
        log.info("  Total cases    : {}", summary.totalCases());
        log.info("  Passed         : {}", summary.passed());
        log.info("  Failed         : {}", summary.failed());
        log.info("  Overall score  : {:.1f}%", summary.overallScore() * 100);
        log.info("  Avg P@5        : {:.3f}", summary.aggregates().avgPrecisionAt5());
        log.info("  Avg Grounding  : {:.3f}", summary.aggregates().avgGroundingScore());
        log.info("  Avg Latency    : {:.0f} ms", summary.aggregates().avgLatencyMs());
        log.info("  Regressions    : {}", summary.regressions().size());
        log.info("  Reports        : target/evaluation-reports/dashboard.html");
        log.info("═══════════════════════════════════════════");

        // Assertions for CI
        assertThat(summary.totalCases()).isGreaterThan(0);

        if (failOnRegression && !summary.regressions().isEmpty()) {
            log.error("Regression detected — failing build");
            assertThat(summary.regressions()).isEmpty();
        }
    }
}
