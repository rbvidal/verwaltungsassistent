package reasoning.ai.unit.evaluation;

import reasoning.ai.application.DefaultEvaluationService;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Evaluation Service")
class EvaluationServiceTest {

    private DefaultEvaluationService evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultEvaluationService();
    }

    @Nested
    @DisplayName("Grounding score")
    class GroundingScore {

        @Test
        @DisplayName("high grounding when context is larger than answer")
        void highGroundingWithLargeContext() {
            String question = "What is the key finding?";
            String answer = "The key finding is X.";
            String context = "A".repeat(200);

            var result = evaluator.evaluate(question, answer, context);
            assertTrue(result.groundingScore() > 0.4,
                    "Grounding score should be reasonable with large context, was: " + result.groundingScore());
        }

        @Test
        @DisplayName("lower grounding when answer is much longer than context")
        void lowerGroundingWhenAnswerExceedsContext() {
            String question = "?";
            String answer = "A".repeat(500);
            String context = "B";

            var result = evaluator.evaluate(question, answer, context);
            assertTrue(result.groundingScore() < 0.5,
                    "Grounding should be low when answer far exceeds context, was: " + result.groundingScore());
        }
    }

    @Nested
    @DisplayName("Hallucination detection")
    class HallucinationDetection {

        @Test
        @DisplayName("detects uncertainty phrases as hallucination indicators")
        void detectsUncertaintyPhrases() {
            String question = "What is X?";
            String answer = "I don't know what X is. I'm not sure about Y. I cannot verify Z.";
            String context = "context";

            var result = evaluator.evaluate(question, answer, context);
            assertTrue(result.hallucinationIndicators() >= 2,
                    "Should detect multiple hallucination indicators, was: " + result.hallucinationIndicators());
        }

        @Test
        @DisplayName("confident answer has low hallucination indicators")
        void confidentAnswerHasLowHallucinationIndicators() {
            String question = "What is X?";
            String answer = "X is established in the document. It clearly states Y.";
            String context = "X is established. It states Y.";

            var result = evaluator.evaluate(question, answer, context);
            assertTrue(result.hallucinationIndicators() <= 1,
                    "Confident answer should have low hallucination indicators, was: "
                            + result.hallucinationIndicators());
        }
    }

    @Nested
    @DisplayName("Evaluation result structure")
    class ResultStructure {

        @Test
        @DisplayName("produces all evaluation dimensions")
        void producesAllDimensions() {
            var result = evaluator.evaluate("question", "answer", "context");
            assertTrue(result.groundingScore() >= 0);
            assertTrue(result.groundingScore() <= 1);
            assertTrue(result.faithfulness() >= 0);
            assertTrue(result.faithfulness() <= 1);
            assertTrue(result.citationCoverage() >= 0);
            assertTrue(result.citationCoverage() <= 1);
        }

        @Test
        @DisplayName("pass/fail gate is set")
        void passFailGateIsSet() {
            var result = evaluator.evaluate("question", "answer with citations [1] [2] [3]", "sufficient context");
            assertNotNull(result.passed());
            // Result with citations and sufficient context should pass
        }
    }
}
