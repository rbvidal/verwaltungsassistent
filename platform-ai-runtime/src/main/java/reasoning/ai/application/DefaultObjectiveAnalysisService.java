package reasoning.ai.application;

import reasoning.ai.api.ObjectiveAnalysisService;
import reasoning.ai.model.AnalysisObjective;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Classifies a query into prioritized analysis objectives using keyword-matching rules.
 */
@Component
public class DefaultObjectiveAnalysisService implements ObjectiveAnalysisService {

    private static final Map<String, ObjectiveRule> RULES = new LinkedHashMap<>();

    static {
        RULES.put("RECOVERY", new ObjectiveRule(
                Set.of("deposit", "offset", "set off", "set-off",
                        "retain", "withhold", "keep",
                        "use the deposit", "use deposit",
                        "still have", "difference"),
                1,
                List.of("Retain security against outstanding obligations",
                        "Set off deposit against outstanding debt",
                        "Calculate remaining claim after offset"),
                List.of("387", "389", "551")));

        RULES.put("DAMAGE_RECOVERY", new ObjectiveRule(
                Set.of("damages", "compensation",
                        "loss", "recover",
                        "cost me", "costs", "financial loss",
                        "sue", "claim damages", "recover money",
                        "still owe", "remaining", "difference"),
                1,
                List.of("Calculate total damages owed",
                        "Determine continuing obligations during notice period",
                        "File claim for remaining unpaid amount after offset"),
                List.of("280", "281", "535", "546a")));

        RULES.put("ENFORCEMENT", new ObjectiveRule(
                Set.of("did not pay", "unpaid", "not paying", "didn't pay",
                        "owing", "outstanding",
                        "get money", "collect", "enforce",
                        "force to pay", "make them pay"),
                2,
                List.of("Send formal demand",
                        "Calculate exact amount owed",
                        "Pursue enforcement action for unpaid obligations"),
                List.of("535", "543", "569", "556b")));

        RULES.put("SECURITY_RETENTION", new ObjectiveRule(
                Set.of("deposit", "security",
                        "return deposit", "deposit back",
                        "deposit refund"),
                2,
                List.of("Determine if security can be legally retained",
                        "Calculate deposit coverage vs. total claim",
                        "Follow proper accounting procedure"),
                List.of("551")));

        RULES.put("TERMINATION", new ObjectiveRule(
                Set.of("termination", "terminate",
                        "end", "dissolve",
                        "get out of contract", "end agreement",
                        "leave before", "left before", "move out"),
                3,
                List.of("Verify termination validity",
                        "Confirm notice period compliance",
                        "Document termination formalities"),
                List.of("542", "543", "568", "569", "573c")));

        RULES.put("PROCEDURAL_ACTION", new ObjectiveRule(
                Set.of("what can i do", "what should i do", "how to proceed",
                        "next steps", "what steps", "procedure",
                        "file a", "submit", "petition", "application",
                        "what are my rights", "what rights"),
                3,
                List.of("Identify strongest actionable position",
                        "Prioritize practical next steps",
                        "Distinguish urgent vs. long-term actions"),
                List.of()));

        RULES.put("ANALYSIS", new ObjectiveRule(
                Set.of("liable", "liability", "responsibility",
                        "who pays", "who is responsible", "whose fault"),
                4,
                List.of("Determine who is liable",
                        "Establish basis for liability claim",
                        "Quantify liability amount"),
                List.of("280", "535", "540")));

        RULES.put("EVICTION", new ObjectiveRule(
                Set.of("eviction", "vacate",
                        "throw out", "get tenant out", "remove tenant"),
                2,
                List.of("Verify grounds for eviction",
                        "Follow proper eviction procedure",
                        "Document all notice and filing requirements"),
                List.of("543", "546", "546a", "569", "573")));

        RULES.put("INFORMATION", new ObjectiveRule(
                Set.of("what are my rights", "what rights do i have",
                        "am i allowed", "is it legal", "can i",
                        "information", "explain"),
                5,
                List.of("Clarify position",
                        "Explain applicable framework",
                        "Identify key rights and obligations"),
                List.of()));
    }

    @Override
    public List<AnalysisObjective> classify(String query) {
        String lower = query.toLowerCase();
        List<AnalysisObjective> objectives = new ArrayList<>();

        for (var entry : RULES.entrySet()) {
            ObjectiveRule rule = entry.getValue();
            int hits = 0;
            for (String keyword : rule.keywords()) {
                if (lower.contains(keyword.toLowerCase())) {
                    hits++;
                }
            }
            if (hits > 0) {
                double confidence = Math.min(1.0, hits / (double) Math.max(rule.keywords().size() / 4, 1));
                confidence = Math.max(0.35, Math.min(0.95, confidence + 0.15));
                objectives.add(new AnalysisObjective(
                        entry.getKey(),
                        rule.description(),
                        rule.keyActions().getFirst(),
                        confidence,
                        rule.priority(),
                        rule.keyActions(),
                        rule.governingReferences()));
            }
        }

        objectives.sort(Comparator.comparingInt(AnalysisObjective::priority)
                .thenComparing(Comparator.comparingDouble(AnalysisObjective::confidence).reversed()));

        if (lower.contains("what can i do") || lower.contains("what should i do")) {
            boolean alreadyHas = objectives.stream().anyMatch(o -> o.objectiveId().equals("PROCEDURAL_ACTION"));
            if (!alreadyHas) {
                ObjectiveRule rule = RULES.get("PROCEDURAL_ACTION");
                objectives.add(new AnalysisObjective(
                        "PROCEDURAL_ACTION", rule.description(), rule.keyActions().getFirst(),
                        0.7, 3, rule.keyActions(), rule.governingReferences()));
            }
        }

        if (objectives.isEmpty()) {
            ObjectiveRule rule = RULES.get("INFORMATION");
            objectives.add(new AnalysisObjective(
                    "INFORMATION", rule.description(), rule.keyActions().getFirst(),
                    0.3, 5, rule.keyActions(), rule.governingReferences()));
        }

        return objectives;
    }

    private record ObjectiveRule(
            Set<String> keywords,
            int priority,
            List<String> keyActions,
            List<String> governingReferences) {
        String description() {
            return keyActions.getFirst();
        }
    }
}
