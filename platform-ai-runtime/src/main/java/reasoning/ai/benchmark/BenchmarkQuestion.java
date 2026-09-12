package reasoning.ai.benchmark;

import reasoning.ai.model.DecisionStrategy;

import java.util.List;

/**
 * A single benchmark question with expected outcomes for automated validation.
 *
 * <p>Validation dimensions:
 * <ul>
 *   <li><b>HARD</b> — strategy, confidence, grounded, regulation, semantic concepts</li>
 *   <li><b>SOFT</b> — (none; all checks are hard in the current version)</li>
 * </ul>
 */
public record BenchmarkQuestion(
        String id,
        String category,
        String question,
        DecisionStrategy expectedStrategy,
        double minConfidence,
        double maxConfidence,
        List<String> expectedKeywords,
        List<String> forbiddenKeywords,
        String expectedRegulation,
        boolean expectedGrounded,
        boolean requiresRetrieval,
        List<String> mustContainConcepts,
        List<String> mustNotContainConcepts
) {
    /** Compact constructor for backward compatibility — defaults empty semantic lists. */
    public BenchmarkQuestion {
        if (mustContainConcepts == null) mustContainConcepts = List.of();
        if (mustNotContainConcepts == null) mustNotContainConcepts = List.of();
    }

    /** Backward-compatible constructor without semantic concepts. */
    public BenchmarkQuestion(String id, String category, String question,
            DecisionStrategy expectedStrategy, double minConfidence, double maxConfidence,
            List<String> expectedKeywords, List<String> forbiddenKeywords,
            String expectedRegulation, boolean expectedGrounded, boolean requiresRetrieval) {
        this(id, category, question, expectedStrategy, minConfidence, maxConfidence,
                expectedKeywords, forbiddenKeywords, expectedRegulation, expectedGrounded,
                requiresRetrieval, List.of(), List.of());
    }
}
