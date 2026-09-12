package reasoning.ai.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Compares DecisionSnapshots and selects the best one.
 *
 * <p>The Judge NEVER generates text. It only evaluates existing outputs
 * against defined criteria. Every comparison produces a scored rationale.
 *
 * <p>Evaluation criteria (weighted):
 * <ol>
 *   <li>Verification passed (0.25)</li>
 *   <li>Hallucinations removed (0.20)</li>
 *   <li>Evidence coverage (0.15)</li>
 *   <li>Confidence (0.15)</li>
 *   <li>Supported findings ratio (0.10)</li>
 *   <li>Citation completeness (0.05)</li>
 *   <li>Structured knowledge usage (0.05)</li>
 *   <li>Retrieval diversity (0.05)</li>
 *   <li>Latency penalty (negative weight)</li>
 * </ol>
 */
@Component
public class DecisionJudge {

    private static final Logger log = LoggerFactory.getLogger(DecisionJudge.class);

    /** Maximum latency increase (ms) before penalty applies */
    private static final long LATENCY_TOLERANCE_MS = 5000L;
    /** Maximum latency increase factor before strong penalty */
    private static final double LATENCY_PENALTY_FACTOR = 2.0;

    /**
     * Judges the original decision vs. a repaired candidate.
     *
     * @param original   the original (pre-repair) snapshot
     * @param candidate  the repaired candidate snapshot
     * @return comparison with winner, scores, and rationale
     */
    public DecisionComparison judge(DecisionSnapshot original, DecisionSnapshot candidate) {
        double origScore = score(original);
        double candScore = score(candidate);

        DecisionSnapshot winner = candScore > origScore ? candidate : original;
        DecisionSnapshot loser = candScore > origScore ? original : candidate;

        List<String> reasons = buildReasons(original, candidate, winner);

        log.info("Judge: {} wins (orig={:.3f} vs cand={:.3f}) — {}",
                winner == original ? "Original" : "Repaired",
                origScore, candScore,
                reasons.isEmpty() ? "no decisive factors" : reasons.get(0));

        return new DecisionComparison(
                original, candidate, winner,
                origScore, candScore,
                reasons,
                winner == candidate ? "Reparatur verbessert die Entscheidungsqualität" : "Originalentscheidung bevorzugt");
    }

    /**
     * Scores a single snapshot against all criteria.
     */
    public double score(DecisionSnapshot s) {
        double sc = 0.0;

        // Verification quality (0.25)
        if (s.verification() != null) {
            if (s.verification().unsupportedFindings().isEmpty()
                    && s.verification().unsupportedRecommendations().isEmpty()) {
                sc += 0.25;
            } else if (s.verification().unsupportedFindings().isEmpty()
                    || s.verification().unsupportedRecommendations().isEmpty()) {
                sc += 0.12;
            }
        } else {
            sc += 0.12; // no verification = neutral
        }

        // Hallucinations (0.20)
        int unsupported = s.unsupportedFindings();
        if (unsupported == 0) sc += 0.20;
        else if (unsupported <= 2) sc += 0.10;

        // Coverage (0.15)
        double cov = s.coverage();
        if (cov >= 0.7) sc += 0.15;
        else if (cov >= 0.4) sc += 0.08;
        else if (cov > 0) sc += 0.03;

        // Confidence (0.15)
        double conf = s.confidence();
        if (conf >= 0.8) sc += 0.15;
        else if (conf >= 0.6) sc += 0.10;
        else if (conf >= 0.4) sc += 0.05;

        // Supported findings (0.10)
        if (s.totalFindings() > 0) {
            double ratio = (double) s.supportedFindings() / s.totalFindings();
            sc += ratio * 0.10;
        }

        // Citations (0.05)
        if (s.evidenceCount() >= 3) sc += 0.05;
        else if (s.evidenceCount() >= 1) sc += 0.03;

        // Knowledge usage (0.05)
        if (!s.knowledgeTablesUsed().isEmpty()) sc += 0.05;
        else if (!s.rulesTriggered().isEmpty()) sc += 0.03;

        // Retrieval diversity (0.05)
        if (s.mergedCandidates() >= 5) sc += 0.05;
        else if (s.mergedCandidates() >= 2) sc += 0.03;

        // Latency penalty (subtractive)
        if (s.isRepaired() && s.repair() != null) {
            long latencyMs = s.latencyMs();
            if (latencyMs > LATENCY_TOLERANCE_MS * LATENCY_PENALTY_FACTOR) {
                sc -= 0.05;
            }
        }

        return Math.max(0.0, Math.min(1.0, sc));
    }

    private List<String> buildReasons(DecisionSnapshot orig, DecisionSnapshot cand, DecisionSnapshot winner) {
        List<String> reasons = new ArrayList<>();

        if (winner == cand) {
            // Repaired wins — explain why
            if (cand.unsupportedFindings() < orig.unsupportedFindings()) {
                reasons.add("Halluzinationen entfernt (" + (orig.unsupportedFindings() - cand.unsupportedFindings()) + " weniger)");
            }
            if (cand.coverage() > orig.coverage() + 0.05) {
                reasons.add("Abdeckung verbessert (+"
                        + String.format("%.0f", (cand.coverage() - orig.coverage()) * 100) + "%)");
            }
            if (cand.confidence() > orig.confidence() + 0.03) {
                reasons.add("Konfidenz gestiegen (+"
                        + String.format("%.0f", (cand.confidence() - orig.confidence()) * 100) + "%)");
            }
            if (cand.evidenceCount() > orig.evidenceCount()) {
                reasons.add("Mehr Belege (" + cand.evidenceCount() + " vs " + orig.evidenceCount() + ")");
            }
            if (cand.verification() != null && orig.verification() != null
                    && cand.verification().unsupportedFindings().isEmpty()
                    && !orig.verification().unsupportedFindings().isEmpty()) {
                reasons.add("Verifikation bestanden (Original nicht)");
            }
        } else {
            // Original wins — explain why repair didn't help
            if (cand.latencyMs() > orig.latencyMs() * 1.5) {
                reasons.add("Reparatur erhöhte Latenz ohne ausreichenden Gewinn");
            }
            if (Math.abs(cand.confidence() - orig.confidence()) < 0.03) {
                reasons.add("Keine signifikante Konfidenz-Verbesserung");
            }
            if (Math.abs(cand.coverage() - orig.coverage()) < 0.05) {
                reasons.add("Keine signifikante Abdeckungs-Verbesserung");
            }
            if (cand.unsupportedFindings() >= orig.unsupportedFindings()) {
                reasons.add("Keine Reduktion unbelegter Feststellungen");
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Geringfügige Unterschiede — Original beibehalten");
        }
        return reasons;
    }
}
