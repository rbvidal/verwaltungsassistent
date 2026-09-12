package reasoning.ai.model;

import java.util.List;

/**
 * Result of AI answer evaluation, containing quality metrics and indicators.
 * Used for both automated quality assessment and prompt regression testing.
 */
public record EvaluationResult(
        /** 0-1 score: how well the answer is grounded in retrieved sources */
        double groundingScore,
        /** 0-1 score: fraction of claims that cite a source */
        double citationCoverage,
        /** 0-1 score: how factually faithful the answer is to the sources */
        double faithfulness,
        /** 0-1 score: how relevant the answer is to the question */
        double answerRelevance,
        /** 0-1 score: how relevant the retrieved context is to the question */
        double contextRelevance,
        /** Number of hallucination indicators detected */
        int hallucinationIndicators,
        /** List of specific issues found */
        List<String> issues,
        /** Overall pass/fail for automated quality gates */
        boolean passed
) {}
