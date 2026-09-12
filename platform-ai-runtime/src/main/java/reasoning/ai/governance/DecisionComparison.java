package reasoning.ai.governance;

import java.util.List;

/**
 * Result of a DecisionJudge comparison between original and candidate snapshots.
 */
public record DecisionComparison(
        DecisionSnapshot original,
        DecisionSnapshot candidate,
        DecisionSnapshot winner,
        double originalScore,
        double candidateScore,
        List<String> reasons,
        String rationale
) {
    public boolean isRepairedSelected() {
        return winner == candidate;
    }

    public boolean isOriginalSelected() {
        return winner == original;
    }

    public double scoreDelta() {
        return Math.abs(originalScore - candidateScore);
    }
}
