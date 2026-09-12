package reasoning.ai.api;

import reasoning.ai.model.EvaluationResult;

/**
 * SPI for evaluating AI-generated answers against retrieval context.
 * Produces grounding scores, citation coverage, faithfulness, and relevance metrics.
 */
public interface EvaluationService {

    /**
     * Evaluates an AI-generated answer against the provided retrieval context.
     * @param question the user's original question
     * @param answer the AI-generated answer
     * @param context the retrieval context used to generate the answer
     * @return evaluation result with scores and indicators
     */
    EvaluationResult evaluate(String question, String answer, Object context);
}
