package reasoning.ai.application;

import reasoning.ai.api.ConceptExtractionService;
import reasoning.ai.model.ExtractedConcept;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Extracts legal and factual concepts from a query using keyword-matching rules.
 */
@Component
public class DefaultConceptExtractionService implements ConceptExtractionService {

    private static final Map<String, ConceptRule> CONCEPT_RULES = new LinkedHashMap<>();

    static {
        CONCEPT_RULES.put("SECURITY_INSTRUMENT", new ConceptRule(
                Set.of("kaution", "deposit", "sicherheitsleistung",
                        "sicherheit", "deposit back", "bürgschaft",
                        "einbehalten", "verrechnen", "offset deposit",
                        "keep deposit", "return deposit"),
                List.of("551"),
                List.of("PAYMENT", "OFFSET", "DAMAGES"),
                "Security Instrument / Deposit"));

        CONCEPT_RULES.put("OFFSET", new ConceptRule(
                Set.of("offset", "set off", "set-off",
                        "verrechnen mit", "gegenforderung",
                        "offset against", "deduct from", "einbehalten",
                        "deposit", "retain", "retaining"),
                List.of("387", "389"),
                List.of("SECURITY_INSTRUMENT", "PAYMENT", "DAMAGES"),
                "Offset / Counterclaim"));

        CONCEPT_RULES.put("PAYMENT", new ConceptRule(
                Set.of("payment", "pay", "due", "amount",
                        "outstanding", "unpaid", "did not pay", "didn't pay",
                        "not paying", "arrears", "back", "owing"),
                List.of("535", "537", "556b"),
                List.of("DEFAULT", "TERMINATION", "OFFSET"),
                "Payment / Obligation"));

        CONCEPT_RULES.put("DEFAULT", new ConceptRule(
                Set.of("default", "arrears", "outstanding", "owing",
                        "did not pay", "didn't pay", "unpaid", "not paying",
                        "delinquent", "in default", "breach"),
                List.of("543", "569", "573"),
                List.of("PAYMENT", "TERMINATION", "DAMAGES"),
                "Default / Breach"));

        CONCEPT_RULES.put("SUBLEASE", new ConceptRule(
                Set.of("sublease", "sublet", "subtenant", "sub-tenant",
                        "subl", "subleased", "sublet to"),
                List.of("540", "553"),
                List.of("TERMINATION", "PAYMENT", "SECURITY_INSTRUMENT"),
                "Sublease / Subtenancy"));

        CONCEPT_RULES.put("TERMINATION", new ConceptRule(
                Set.of("termination", "terminate", "notice period",
                        "beenden", "beendigung", "end contract",
                        "leave before", "left before", "move out",
                        "eviction", "vacate"),
                List.of("542", "543", "568", "569", "573", "573c"),
                List.of("DEFAULT", "NOTICE_PERIOD", "DAMAGES"),
                "Termination / Contract End"));

        CONCEPT_RULES.put("NOTICE_PERIOD", new ConceptRule(
                Set.of("notice period", "notice", "deadline",
                        "two months", "2 months", "2 monate", "zwei monate"),
                List.of("573c", "580a"),
                List.of("TERMINATION", "PAYMENT"),
                "Notice Period"));

        CONCEPT_RULES.put("DAMAGES", new ConceptRule(
                Set.of("damages", "compensation", "loss",
                        "cost", "costs", "financial loss",
                        "claim damages", "recover"),
                List.of("280", "281", "536a", "546a"),
                List.of("DEFAULT", "TERMINATION", "OFFSET"),
                "Damages / Compensation"));

        CONCEPT_RULES.put("MAINTENANCE", new ConceptRule(
                Set.of("repair", "maintenance", "renovate",
                        "broken", "defect", "deficiency"),
                List.of("535", "538", "554", "555a"),
                List.of("REDUCTION", "MODERNIZATION"),
                "Maintenance / Repairs"));

        CONCEPT_RULES.put("MODERNIZATION", new ConceptRule(
                Set.of("modernization", "renovation", "upgrade",
                        "insulation", "heating", "windows", "balcony"),
                List.of("555b", "555c", "555d", "555e", "559"),
                List.of("MAINTENANCE", "INCREASE"),
                "Modernization"));

        CONCEPT_RULES.put("INCREASE", new ConceptRule(
                Set.of("increase", "higher rent", "more expensive"),
                List.of("557", "558", "558a", "559", "560"),
                List.of("MODERNIZATION", "PAYMENT"),
                "Increase / Adjustment"));

        CONCEPT_RULES.put("UTILITIES", new ConceptRule(
                Set.of("utilities", "utility costs", "operating costs",
                        "heating", "water", "electricity", "gas", "waste",
                        "utility bill", "billing"),
                List.of("556", "556a", "560"),
                List.of("PAYMENT", "INCREASE"),
                "Utilities / Operating Costs"));

        CONCEPT_RULES.put("EVICTION", new ConceptRule(
                Set.of("eviction", "vacate", "remove",
                        "throw out", "removal"),
                List.of("546", "546a", "574", "574a"),
                List.of("TERMINATION", "DEFAULT"),
                "Eviction / Removal"));

        CONCEPT_RULES.put("WRITTEN_FORM", new ConceptRule(
                Set.of("written form", "written", "oral", "signature",
                        "signed", "contract form"),
                List.of("550", "568"),
                List.of("TERMINATION", "CONTRACT"),
                "Written Form / Form Requirements"));
    }

    @Override
    public List<ExtractedConcept> classify(String query) {
        String lower = query.toLowerCase();
        List<ExtractedConcept> concepts = new ArrayList<>();

        for (var entry : CONCEPT_RULES.entrySet()) {
            ConceptRule rule = entry.getValue();
            int hits = 0;
            for (String keyword : rule.keywords()) {
                if (lower.contains(keyword.toLowerCase())) {
                    hits++;
                }
            }
            if (hits > 0) {
                double confidence = Math.min(1.0, hits / (double) Math.max(rule.keywords().size() / 3, 1));
                confidence = Math.max(0.3, Math.min(0.95, confidence + 0.2));
                concepts.add(new ExtractedConcept(
                        entry.getKey(),
                        rule.label(),
                        confidence,
                        rule.governingReferences(),
                        rule.relatedConcepts()));
            }
        }

        concepts.sort(Comparator.comparingDouble(ExtractedConcept::confidence).reversed());

        if (concepts.isEmpty()) {
            // Generic fallback without governing references: a general concept
            // must never fabricate a legal reference (the former hardcoded
            // "535" was not backed by any knowledge-base entry).
            concepts.add(new ExtractedConcept(
                    "GENERAL", "General",
                    0.3, List.of(), List.of()));
        }

        return concepts;
    }

    private record ConceptRule(
            Set<String> keywords,
            List<String> governingReferences,
            List<String> relatedConcepts,
            String label) {
    }
}
