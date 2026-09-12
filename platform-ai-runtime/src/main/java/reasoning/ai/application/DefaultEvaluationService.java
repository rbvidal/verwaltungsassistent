package reasoning.ai.application;

import reasoning.ai.api.EvaluationService;
import reasoning.ai.model.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Default evaluation service using heuristics for grounding, citation, and hallucination detection.
 * In production, a dedicated evaluation LLM or framework (deepeval, ragas) should be used.
 */
@Service
public class DefaultEvaluationService implements EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEvaluationService.class);

    // Patterns for hallucination indicators
    private static final Pattern HALLUCINATION_PATTERN = Pattern.compile(
            "\\b(?:I don't know|I'm not sure|I cannot (?:determine|verify|confirm|find)|" +
            "no (?:evidence|information|data|record)|not mentioned|unspecified|unclear|unknown)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\d+\\]|\\[Source\\s|\\[Ref\\s");

    @Override
    public EvaluationResult evaluate(String question, String answer, Object context) {
        List<String> issues = new ArrayList<>();
        String ctx = context != null ? context.toString() : "";

        // Citation coverage: count citation markers in answer
        long citationCount = CITATION_PATTERN.matcher(answer).results().count();
        double citationCoverage = Math.min(1.0, citationCount / 3.0);
        if (citationCoverage < 0.3) {
            issues.add("Low citation coverage: " + String.format("%.0f%%", citationCoverage * 100));
        }

        // Hallucination detection
        long hallucinationMatches = HALLUCINATION_PATTERN.matcher(answer).results().count();
        int hallucinationIndicators = (int) hallucinationMatches;
        if (hallucinationIndicators > 2) {
            issues.add("Multiple uncertainty/hallucination indicators: " + hallucinationIndicators);
        }

        // Grounding: check answer length vs context length ratio
        double groundingScore = ctx.length() > 0 ? Math.min(1.0, (double) ctx.length() / answer.length() / 5.0) : 0.2;
        if (groundingScore < 0.4) {
            issues.add("Low grounding: answer may exceed context support");
        }

        // Faithfulness: inversely related to hallucination indicators
        double faithfulness = Math.max(0.0, 1.0 - (hallucinationIndicators * 0.2));
        if (faithfulness < 0.5) {
            issues.add("Low faithfulness score: " + String.format("%.0f%%", faithfulness * 100));
        }

        // Answer relevance: answer should contain terms from the question
        double answerRelevance = computeTermOverlap(question, answer);

        // Context relevance: context should contain terms from the question
        double contextRelevance = computeTermOverlap(question, ctx);

        boolean passed = groundingScore >= 0.4 && faithfulness >= 0.5
                && hallucinationIndicators <= 2 && citationCoverage >= 0.2;

        log.debug("Evaluation: grounding={}, citation={}, faithfulness={}, answerRel={}, contextRel={}, passed={}",
                groundingScore, citationCoverage, faithfulness, answerRelevance, contextRelevance, passed);

        return new EvaluationResult(groundingScore, citationCoverage, faithfulness,
                answerRelevance, contextRelevance, hallucinationIndicators, issues, passed);
    }

    private static double computeTermOverlap(String source, String target) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) return 0.5;
        String[] sourceWords = source.toLowerCase().split("\\W+");
        String targetLower = target.toLowerCase();
        int overlap = 0;
        for (String word : sourceWords) {
            if (word.length() > 3 && targetLower.contains(word)) overlap++;
        }
        return Math.min(1.0, (double) overlap / Math.max(1, sourceWords.length));
    }
}
