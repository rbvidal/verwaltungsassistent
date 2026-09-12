package reasoning.ai.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Structured decision produced by the RuleEngine. Immutable.
 * When present, the LLM receives ONLY this object — no retrieval,
 * no evidence chunks, no document search.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DecisionResult.SalaryDecision.class, name = "SALARY"),
        @JsonSubTypes.Type(value = DecisionResult.TravelDecision.class, name = "TRAVEL"),
        @JsonSubTypes.Type(value = DecisionResult.ProcurementDecision.class, name = "PROCUREMENT"),
        @JsonSubTypes.Type(value = DecisionResult.FeeDecision.class, name = "FEE"),
        @JsonSubTypes.Type(value = DecisionResult.RuleDecision.class, name = "RULE")
})
public sealed interface DecisionResult
        permits DecisionResult.SalaryDecision,
                DecisionResult.TravelDecision,
                DecisionResult.ProcurementDecision,
                DecisionResult.FeeDecision,
                DecisionResult.RuleDecision {

    /** The human-readable decision text. */
    String decision();

    /** The legal/source-based reason for the decision. */
    String reason();

    /** The source document or regulation. */
    String source();

    /** Confidence 0.0–1.0. */
    double confidence();

    /** Structured values keyed by name. */
    Map<String, Object> values();

    /** Effective dates. */
    List<String> effectiveDates();

    /** The issuing authority. */
    String authority();

    /** The strategy used to produce this decision. */
    default DecisionStrategy strategy() { return DecisionStrategy.RULE_ENGINE; }

    // ── Concrete decision types ──

    record SalaryDecision(
            String decision, String reason, String source, double confidence,
            String grade, int step, double monthlyAmount, String payScale,
            String effectiveDate, String authority
    ) implements DecisionResult {
        public Map<String, Object> values() {
            return Map.of("grade", grade, "step", step,
                    "monthlyAmount", monthlyAmount, "payScale", payScale,
                    "effectiveDate", effectiveDate);
        }
        public List<String> effectiveDates() { return List.of(effectiveDate); }
    }

    record TravelDecision(
            String decision, String reason, String source, double confidence,
            double hours, double allowanceEur, String category,
            String description, String effectiveDate, String authority
    ) implements DecisionResult {
        public Map<String, Object> values() {
            return Map.of("hours", hours, "allowanceEur", allowanceEur,
                    "category", category, "description", description);
        }
        public List<String> effectiveDates() { return List.of(effectiveDate); }
    }

    record ProcurementDecision(
            String decision, String reason, String source, double confidence,
            double amount, String procedure, List<String> requirements,
            String category, String effectiveDate, String authority
    ) implements DecisionResult {
        public Map<String, Object> values() {
            return Map.of("amount", amount, "procedure", procedure,
                    "requirements", requirements, "category", category);
        }
        public List<String> effectiveDates() { return List.of(effectiveDate); }
    }

    /**
     * Decision produced by a generic RULE knowledge item. The decision text is
     * the rule's consequence as extracted; provenance is the source
     * document/version. Domain-agnostic — no regulation-specific fields.
     */
    record RuleDecision(
            String decision, String reason, String source, double confidence,
            String effectiveDate, String authority
    ) implements DecisionResult {
        public Map<String, Object> values() {
            return Map.of("effectiveDate", effectiveDate);
        }
        public List<String> effectiveDates() { return List.of(effectiveDate); }
    }

    record FeeDecision(
            String decision, String reason, String source, double confidence,
            String feeType, double amount, String regulation,
            String authority, String effectiveDate
    ) implements DecisionResult {
        public Map<String, Object> values() {
            return Map.of("feeType", feeType, "amount", amount,
                    "regulation", regulation);
        }
        public List<String> effectiveDates() { return List.of(effectiveDate); }
    }
}
