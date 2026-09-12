package reasoning.search.application;

import reasoning.common.model.DocumentFileType;
import reasoning.search.api.QueryIntentClassifier;
import reasoning.search.model.QueryIntent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Classifies query intent by matching keywords against predefined rule sets (GENERAL, CONTRACT, FINANCE, etc.). */
public class KeywordIntentClassifier implements QueryIntentClassifier {

    private static final Map<String, IntentRule> RULES = Map.of(
            "GENERAL", new IntentRule(
                    Set.of("what is", "how to", "explain", "describe", "define",
                            "what are", "tell me", "information"),
                    Map.of()
),
            "CONTRACT", new IntentRule(
                    Set.of("contract", "agreement", "terms", "clause", "obligation",
                            "party", "parties", "amendment", "revision",
                            "sign", "execute", "effective date"),
                    Map.of(DocumentFileType.PDF, 1.5, DocumentFileType.DOCX, 1.2)),
            "FINANCE", new IntentRule(
                    Set.of("invoice", "payment", "bill", "fee", "due", "amount",
                            "charge", "balance", "receipt", "expense",
                            "financial", "accounting", "budget"),
                    Map.of(DocumentFileType.PDF, 1.3, DocumentFileType.TXT, 1.1)),
            "COMPLIANCE", new IntentRule(
                    Set.of("compliance", "regulation", "policy", "standard",
                            "requirement", "audit", "governance",
                            "violation", "breach"),
                    Map.of(DocumentFileType.PDF, 1.5)),
            "PROCEDURE", new IntentRule(
                    Set.of("procedure", "how to proceed", "what steps", "next steps",
                            "process", "workflow", "guide", "manual",
                            "instruction", "tutorial", "checklist"),
                    Map.of(DocumentFileType.TXT, 1.2, DocumentFileType.HTML, 1.1)),
            "COMMUNICATION", new IntentRule(
                    Set.of("ask", "request", "tell", "say", "email", "letter", "write",
                            "contact", "message", "reply", "respond", "send",
                            "discuss", "propose", "notify"),
                    Map.of(DocumentFileType.TXT, 1.3, DocumentFileType.PDF, 0.8))
    );

    private static final Map<DocumentFileType, Double> DEFAULT_WEIGHTS = Map.of(
            DocumentFileType.PDF, 1.0,
            DocumentFileType.DOCX, 1.0,
            DocumentFileType.TXT, 1.0,
            DocumentFileType.HTML, 1.0);

    @Override
    public QueryIntent classify(String query) {
        String lower = query.toLowerCase();
        String bestIntent = "GENERAL";
        int bestScore = 0;

        for (var entry : RULES.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue().keywords()) {
                if (lower.contains(keyword.toLowerCase())) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIntent = entry.getKey();
            }
        }

        if (bestScore == 0) {
            return new QueryIntent("GENERAL", DEFAULT_WEIGHTS);
        }

        IntentRule rule = RULES.get(bestIntent);
        Map<DocumentFileType, Double> weights = new HashMap<>(DEFAULT_WEIGHTS);
        weights.putAll(rule.weights());
        return new QueryIntent(bestIntent, weights);
    }

    private record IntentRule(
            Set<String> keywords,
            Map<DocumentFileType, Double> weights) {
    }
}
