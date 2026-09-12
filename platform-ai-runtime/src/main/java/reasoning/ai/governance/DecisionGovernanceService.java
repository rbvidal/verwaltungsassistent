package reasoning.ai.governance;

import reasoning.ai.model.*;
import reasoning.ai.verification.RepairResult;
import reasoning.ai.verification.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the governance layer: snapshot creation, judging, lineage building.
 *
 * <p>Post-processing only — consumes existing pipeline outputs.
 * Does not generate text, query LLMs, or duplicate retrieval logic.
 */
@Service
public class DecisionGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(DecisionGovernanceService.class);

    private final DecisionJudge judge;

    public DecisionGovernanceService(DecisionJudge judge) {
        this.judge = judge;
    }

    // ── Runtime Metrics ──

    private int verificationPassCount, verificationFailCount;
    private int repairActivationCount, repairSuccessCount;
    private int judgeOverrideCount, judgeOriginalCount;
    private double totalConfidenceGain;
    private long totalRepairLatencyMs;
    private int totalPipelineRuns;

    /**
     * Creates a snapshot from a pipeline execution.
     */
    public DecisionSnapshot captureSnapshot(String question, AiResponse response,
                                             VerificationResult verification, RepairResult repair,
                                             String stage) {
        return DecisionSnapshot.builder()
                .stage(stage)
                .question(question)
                .timestamp(Instant.now())
                .fromResponse(response)
                .fromVerification(verification)
                .repair(repair)
                .latencyMs(verification != null ? verification.totalMs() : 0)
                .build();
    }

    /**
     * Judges original vs. repaired snapshot and selects the winner.
     */
    public DecisionComparison evaluateAndSelect(DecisionSnapshot original, DecisionSnapshot candidate) {
        DecisionComparison result = judge.judge(original, candidate);

        synchronized (this) {
            totalPipelineRuns++;
            if (result.isRepairedSelected()) {
                judgeOverrideCount++;
                repairSuccessCount++;
                totalConfidenceGain += result.candidate().confidence() - result.original().confidence();
            } else {
                judgeOriginalCount++;
            }
            if (original.verification() != null && original.verification().unsupportedFindings().isEmpty()) {
                verificationPassCount++;
            } else {
                verificationFailCount++;
            }
            if (candidate.repair() != null) {
                repairActivationCount++;
                totalRepairLatencyMs += candidate.latencyMs();
            }
        }

        log.info("Governance: {} selected (orig={:.3f}, cand={:.3f})",
                result.isRepairedSelected() ? "Repaired" : "Original",
                result.originalScore(), result.candidateScore());

        return result;
    }

    /**
     * Builds a complete decision lineage from pipeline artifacts.
     */
    public DecisionLineage buildLineage(String question,
                                         AiResponse originalResponse,
                                         VerificationResult verification,
                                         RepairResult repair,
                                         DecisionSnapshot originalSnapshot,
                                         DecisionSnapshot finalSnapshot,
                                         DecisionComparison comparison) {

        DecisionLineage.Builder lb = DecisionLineage.builder()
                .question(question)
                .timestamp(Instant.now());

        int order = 0;

        // 1. Intent Classification
        if (verification != null) {
            Map<String, Object> intentDetails = new LinkedHashMap<>();
            intentDetails.put("primär", verification.intentPrimary());
            intentDetails.put("konfidenz", String.format("%.0f%%", verification.intentConfidence() * 100));
            if (verification.intentSecondary() != null) {
                intentDetails.put("sekundär", verification.intentSecondary());
            }
            String status = verification.intentMismatch() != null ? "warning" : "ok";
            lb.intent(DecisionLineage.LineageNode.withStatus(
                    "intent", "Intent-Klassifikation", ++order, intentDetails,
                    verification.intentMs(), status));
        }

        // 2. Routing
        if (verification != null) {
            Map<String, Object> routeDetails = new LinkedHashMap<>();
            routeDetails.put("strategie", verification.routingStrategy());
            routeDetails.put("deterministisch", verification.routingDeterministic());
            lb.routing(DecisionLineage.LineageNode.of(
                    "routing", "Routing", ++order, routeDetails, verification.routingMs()));
        }

        // 3. Structured Knowledge
        if (verification != null) {
            Map<String, Object> knowledgeDetails = new LinkedHashMap<>();
            knowledgeDetails.put("regeln", verification.rulesFired());
            knowledgeDetails.put("tabellen", verification.tablesConsulted());
            if (!verification.unusedRules().isEmpty()) {
                knowledgeDetails.put("ungenutzteRegeln", verification.unusedRules());
            }
            lb.knowledge(DecisionLineage.LineageNode.of(
                    "knowledge", "Strukturiertes Wissen", ++order, knowledgeDetails, 0));
        }

        // 4. Retrieval
        if (verification != null) {
            Map<String, Object> retrievalDetails = new LinkedHashMap<>();
            retrievalDetails.put("keywordTreffer", verification.keywordHits());
            retrievalDetails.put("vektorTreffer", verification.vectorHits());
            retrievalDetails.put("graphTreffer", verification.graphHits());
            retrievalDetails.put("merged", verification.mergedCandidates());
            lb.retrieval(DecisionLineage.LineageNode.of(
                    "retrieval", "Hybrid Retrieval", ++order, retrievalDetails, verification.retrievalMs()));
        }

        // 5. Candidates
        if (verification != null) {
            Map<String, Object> candDetails = new LinkedHashMap<>();
            candDetails.put("belege", verification.evidenceCount());
            candDetails.put("vorschriften", verification.authorityCount());
            candDetails.put("duplikate", verification.duplicateEvidence());
            String candStatus = verification.duplicateEvidence() > 0 ? "warning" : "ok";
            lb.candidates(DecisionLineage.LineageNode.withStatus(
                    "candidates", "Candidate Generation", ++order, candDetails, 0, candStatus));
        }

        // 6. Reranking
        if (verification != null && !verification.rerankingExplanations().isEmpty()) {
            Map<String, Object> rerankDetails = new LinkedHashMap<>();
            rerankDetails.put("topDokumente", verification.rerankingExplanations().stream()
                    .map(r -> r.document() + " (" + String.format("%.2f", r.finalScore()) + ")")
                    .toList());
            lb.reranking(DecisionLineage.LineageNode.of(
                    "reranking", "Domain-aware Reranking", ++order, rerankDetails, 0));
        }

        // 7. Evidence Package
        if (verification != null) {
            Map<String, Object> evidenceDetails = new LinkedHashMap<>();
            evidenceDetails.put("abdeckung", String.format("%.0f%%", verification.coverage() * 100));
            evidenceDetails.put("seitenRefs", verification.pageReferences() + "/" + verification.totalCitations());
            evidenceDetails.put("orphan", verification.orphanEvidence());
            String evStatus = verification.coverage() < 0.3 ? "warning" : "ok";
            lb.evidence(DecisionLineage.LineageNode.withStatus(
                    "evidence", "Evidence Package", ++order, evidenceDetails, 0, evStatus));
        }

        // 8. Prompt
        if (verification != null) {
            Map<String, Object> promptDetails = new LinkedHashMap<>();
            promptDetails.put("tokens", verification.promptTokens());
            promptDetails.put("belege", verification.promptEvidenceCount());
            promptDetails.put("regeln", verification.promptRuleCount());
            promptDetails.put("vorschriften", verification.promptAuthorityCount());
            if (!verification.missingContext().isEmpty()) {
                promptDetails.put("fehlenderKontext", verification.missingContext());
            }
            lb.prompt(DecisionLineage.LineageNode.of(
                    "prompt", "Prompt Builder", ++order, promptDetails, verification.promptMs()));
        }

        // 9. LLM
        lb.llm(DecisionLineage.LineageNode.of(
                "llm", "LLM Generation", ++order,
                Map.of("modell", originalResponse.metadata().model(),
                       "konfidenz", String.format("%.0f%%", originalResponse.answer().confidence().overallConfidence() * 100)),
                verification != null ? verification.llmMs() : 0));

        // 10. Verification
        if (verification != null) {
            Map<String, Object> verifyDetails = new LinkedHashMap<>();
            verifyDetails.put("bestanden", verification.unsupportedFindings().isEmpty() && verification.unsupportedRecommendations().isEmpty());
            verifyDetails.put("unbelegteFeststellungen", verification.unsupportedFindings().size());
            verifyDetails.put("unbelegteEmpfehlungen", verification.unsupportedRecommendations().size());
            String vStatus = verification.unsupportedFindings().isEmpty() ? "ok" : "error";
            lb.verification(DecisionLineage.LineageNode.withStatus(
                    "verification", "Verifikation", ++order, verifyDetails, 0, vStatus));
        }

        // 11. Repair (optional)
        if (repair != null && repair.activated()) {
            Map<String, Object> repairDetails = new LinkedHashMap<>();
            repairDetails.put("aktiviert", true);
            repairDetails.put("grund", repair.reason());
            repairDetails.put("erfolgreich", repair.passed());
            repairDetails.put("konfidenzGewinn", String.format("%.0f%%", repair.confidenceGain() * 100));
            repairDetails.put("schritte", repair.steps().stream()
                    .map(s -> s.stage() + ": " + s.action()).toList());
            repairDetails.put("zyklen", repair.cycles().size());
            String rStatus = repair.passed() ? "repaired" : "warning";
            lb.repair(DecisionLineage.LineageNode.withStatus(
                    "repair", "Repair Engine", ++order, repairDetails,
                    repair.steps().stream().mapToLong(RepairResult.RepairStep::latencyMs).sum(), rStatus));
        }

        // 12. Judging
        if (comparison != null) {
            Map<String, Object> judgeDetails = new LinkedHashMap<>();
            judgeDetails.put("originalScore", String.format("%.3f", comparison.originalScore()));
            judgeDetails.put("kandidatScore", String.format("%.3f", comparison.candidateScore()));
            judgeDetails.put("gewinner", comparison.isRepairedSelected() ? "Repariert" : "Original");
            judgeDetails.put("gründe", comparison.reasons());
            lb.judging(DecisionLineage.LineageNode.of(
                    "judge", "Decision Judge", ++order, judgeDetails, 0));
        }

        // 13. Final Decision
        DecisionSnapshot winner = comparison != null ? comparison.winner() : originalSnapshot;
        Map<String, Object> finalDetails = new LinkedHashMap<>();
        finalDetails.put("konfidenz", String.format("%.0f%%", winner.confidence() * 100));
        finalDetails.put("belege", winner.evidenceCount());
        finalDetails.put("vorschriften", winner.authorityCount());
        finalDetails.put("abdeckung", String.format("%.0f%%", winner.coverage() * 100));
        finalDetails.put("qualitätsscore", String.format("%.3f", winner.qualityScore()));
        finalDetails.put("quelle", winner.isRepaired() ? "repariert" : "original");
        lb.finalDecision(DecisionLineage.LineageNode.of(
                "final", "Finale Entscheidung", ++order, finalDetails, 0));

        lb.originalSnapshot(originalSnapshot)
          .finalSnapshot(finalSnapshot)
          .comparison(comparison);

        return lb.build();
    }

    // ── Runtime Metrics ──

    public synchronized Map<String, Object> getMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipelineRuns", totalPipelineRuns);
        m.put("verificationPassRate", totalPipelineRuns > 0
                ? (double) verificationPassCount / totalPipelineRuns : 0);
        m.put("repairActivationRate", totalPipelineRuns > 0
                ? (double) repairActivationCount / totalPipelineRuns : 0);
        m.put("repairSuccessRate", repairActivationCount > 0
                ? (double) repairSuccessCount / repairActivationCount : 0);
        m.put("judgeOverrideRate", totalPipelineRuns > 0
                ? (double) judgeOverrideCount / totalPipelineRuns : 0);
        m.put("avgConfidenceGain", repairActivationCount > 0
                ? totalConfidenceGain / repairActivationCount : 0);
        m.put("avgRepairLatencyMs", repairActivationCount > 0
                ? totalRepairLatencyMs / repairActivationCount : 0);
        return m;
    }

    public synchronized void resetMetrics() {
        verificationPassCount = 0; verificationFailCount = 0;
        repairActivationCount = 0; repairSuccessCount = 0;
        judgeOverrideCount = 0; judgeOriginalCount = 0;
        totalConfidenceGain = 0; totalRepairLatencyMs = 0; totalPipelineRuns = 0;
    }
}
