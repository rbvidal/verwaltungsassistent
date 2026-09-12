package reasoning.ai.analytics;

import reasoning.ai.governance.*;
import reasoning.ai.model.*;
import reasoning.ai.verification.RepairResult;
import reasoning.ai.verification.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Consumes governance outputs and produces PipelineRunMetrics.
 * No business logic — pure aggregation from existing artifacts.
 */
@Component
public class AnalyticsCollector {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsCollector.class);

    private final AnalyticsRepository repository;

    public AnalyticsCollector(AnalyticsRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a pipeline execution from governance artifacts.
     */
    public PipelineRunMetrics record(String question, VerificationResult vr,
                                      RepairResult repair, DecisionSnapshot original,
                                      DecisionSnapshot finalSnapshot, DecisionComparison comparison,
                                      DecisionLineage lineage) {
        PipelineRunMetrics.Builder b = PipelineRunMetrics.builder()
                .question(question)
                .timestamp(original != null ? original.timestamp() : java.time.Instant.now());

        // Domain & intent
        if (vr != null) {
            b.domain(mapDomain(vr.intentPrimary()))
             .intentPrimary(vr.intentPrimary())
             .intentConfidence(vr.intentConfidence());
        }

        // Routing
        if (original != null) {
            b.routeStrategy(original.strategy())
             .routeDeterministic("RULE_ENGINE".equals(original.strategy()));
        }

        // Retrieval
        if (vr != null) {
            b.keywordHits(vr.keywordHits())
             .vectorHits(vr.vectorHits())
             .graphHits(vr.graphHits())
             .mergedCandidates(vr.mergedCandidates())
             .retrievalLatencyMs(vr.retrievalMs());
            b.addStage(StageMetrics.ok("retrieval", vr.retrievalMs()));
        }

        // Evidence
        if (vr != null) {
            b.evidenceCount(vr.evidenceCount())
             .authorityCount(vr.authorityCount())
             .totalCitations(vr.totalCitations())
             .pageRefs(vr.pageReferences())
             .evidenceCoverage(vr.coverage());
        }

        // Prompt
        if (vr != null) {
            b.promptTokens(vr.promptTokens())
             .promptRuleCount(vr.promptRuleCount())
             .promptLatencyMs(vr.promptMs());
            b.addStage(StageMetrics.ok("prompt", vr.promptMs()));
        }

        // LLM
        if (original != null && original.decision() != null) {
            ReasonedAnswer a = original.decision().answer();
            InferenceMetadata m = original.decision().metadata();
            b.model(m.model())
             .confidence(a.confidence() != null ? a.confidence().overallConfidence() : 0)
             .llmLatencyMs(vr != null ? vr.llmMs() : 0);
            b.addStage(StageMetrics.ok("llm", vr != null ? vr.llmMs() : 0));
            if (a.findingHierarchy() != null) {
                FindingHierarchy fh = a.findingHierarchy();
                int total = fh.primaryFindings().size() + fh.secondaryFindings().size()
                        + fh.proceduralFindings().size() + fh.supportingFindings().size();
                b.findingCount(total).supportedFindings(fh.primaryFindings().size())
                 .unsupportedFindings(total - fh.primaryFindings().size());
            }
        }

        // Verification
        if (vr != null) {
            boolean passed = vr.unsupportedFindings().isEmpty()
                    && vr.unsupportedRecommendations().isEmpty();
            b.verificationPassed(passed)
             .verificationFailures(vr.unsupportedFindings().size() + vr.unsupportedRecommendations().size());
            b.addStage(StageMetrics.ok("verification", 0));
        }

        // Knowledge
        if (vr != null) {
            b.knowledgeTables(vr.tablesConsulted())
             .rulesTriggered(vr.rulesFired());
        }

        // Repair
        if (repair != null) {
            long repairMs = repair.steps().stream().mapToLong(RepairResult.RepairStep::latencyMs).sum();
            b.repairActivated(repair.activated())
             .repairSucceeded(repair.passed())
             .repairReason(repair.reason())
             .confidenceGain(repair.confidenceGain())
             .repairLatencyMs(repairMs);
            if (repair.activated()) {
                b.addStage(StageMetrics.ok("repair", repairMs));
            }
        }

        // Governance
        if (comparison != null) {
            b.repairedSelected(comparison.isRepairedSelected())
             .originalScore(comparison.originalScore())
             .candidateScore(comparison.candidateScore());
        }

        // Overall
        if (vr != null) b.totalLatencyMs(vr.totalMs());
        b.outcome(determineOutcome(vr, repair, comparison));

        PipelineRunMetrics metrics = b.build();
        repository.store(metrics);
        log.debug("Recorded pipeline run: {} outcome={} confidence={:.2f}",
                metrics.executionId(), metrics.outcome(), metrics.confidence());
        return metrics;
    }

    private String mapDomain(String intent) {
        if (intent == null) return "unknown";
        return switch (intent.toUpperCase()) {
            case "PROCUREMENT" -> "Vergabewesen";
            case "BUILDING" -> "Bauordnung";
            case "HR" -> "Personal";
            case "TRAVEL" -> "Reisekosten";
            default -> intent;
        };
    }

    private String determineOutcome(VerificationResult vr, RepairResult repair, DecisionComparison comp) {
        if (vr == null) return "unknown";
        boolean vPass = vr.unsupportedFindings().isEmpty() && vr.unsupportedRecommendations().isEmpty();
        if (vPass && (repair == null || !repair.activated())) return "success";
        if (repair != null && repair.passed()) return "repaired";
        if (comp != null && comp.isRepairedSelected()) return "repaired";
        return vPass ? "success" : "failed";
    }
}
