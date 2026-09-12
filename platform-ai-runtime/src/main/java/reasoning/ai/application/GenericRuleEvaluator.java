package reasoning.ai.application;

import reasoning.ai.model.FactVocabulary;
import reasoning.ai.model.RulePredicate;
import reasoning.ai.model.StructuredIntent;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-agnostic deterministic evaluator for ACTIVE structured RULE items.
 *
 * <p>A rule is executable only when its payload contains a structured
 * {@code predicates} array ({@link RulePredicate}). Rules without predicates
 * are valid knowledge but are NOT deterministically evaluable — they remain
 * INDETERMINATE (RAG/context only). The evaluator never guesses, never calls
 * the LLM, and knows nothing about any specific regulation: facts come from
 * the query-fact layer ({@link #factsFrom(StructuredIntent)}), and the rule's
 * consequence and provenance come from the extracted item.
 */
@Component
public class GenericRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GenericRuleEvaluator.class);

    private final ObjectMapper objectMapper;

    public GenericRuleEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public enum Outcome { MATCH, NO_MATCH, INDETERMINATE }

    public record RuleEvaluation(Outcome outcome, StructuredKnowledgeItem item,
                                 String consequence, String reason,
                                 List<StructuredKnowledgeItem> competing) {
        public boolean isMatch() {
            return outcome == Outcome.MATCH;
        }
    }

    /**
     * Evaluates ACTIVE RULE items against structured query facts.
     *
     * <ul>
     *   <li>exactly one match → MATCH (with item + consequence + provenance)</li>
     *   <li>no match → NO_MATCH</li>
     *   <li>missing facts, malformed payloads, rules without predicates, or
     *       multiple simultaneous matches → INDETERMINATE (competing evidence
     *       preserved) — never a silent guess</li>
     * </ul>
     */
    public RuleEvaluation evaluate(List<StructuredKnowledgeItem> activeRules,
                                   Map<String, Object> facts, LocalDate asOf) {
        LocalDate effective = asOf != null ? asOf : LocalDate.now();
        List<StructuredKnowledgeItem> ruleMatches = new ArrayList<>();
        List<StructuredKnowledgeItem> exceptionMatches = new ArrayList<>();
        String indeterminateReason = null;
        boolean anyEvaluable = false;
        boolean missingFact = false;

        for (StructuredKnowledgeItem item : activeRules) {
            // Safety boundary: only ACTIVE RULE/EXCEPTION items may participate.
            if (item == null || item.status() != StructuredKnowledgeStatus.ACTIVE
                    || (item.kind() != StructuredKnowledgeKind.RULE
                    && item.kind() != StructuredKnowledgeKind.EXCEPTION)) {
                continue;
            }
            if (!temporallyApplicable(item, effective)) {
                continue;
            }
            RuleContent content = parseRule(item);
            if (content == null) {
                indeterminateReason = "Malformed payload";
                continue;
            }
            if (content.predicates().isEmpty()) {
                indeterminateReason = "Ohne strukturierte Prädikate (nicht deterministisch ausführbar)";
                continue;
            }
            anyEvaluable = true;
            Boolean result = evaluatePredicates(content.predicates(), facts);
            if (result == null) {
                // Missing facts must not be guessed: the rule cannot be decided.
                missingFact = true;
                indeterminateReason = "Erforderliche Fakten fehlen oder Prädikat nicht auswertbar";
                continue;
            }
            if (result) {
                if (item.kind() == StructuredKnowledgeKind.EXCEPTION) {
                    exceptionMatches.add(item);
                } else {
                    ruleMatches.add(item);
                }
            }
        }

        // EXCEPTION > RULE: a structured, matching exception is a carve-out of
        // the general rule (established from the kind semantics and tested).
        if (!exceptionMatches.isEmpty()) {
            if (exceptionMatches.size() > 1) {
                return new RuleEvaluation(Outcome.INDETERMINATE, null, null,
                        "Mehrdeutigkeit: mehrere Ausnahmen greifen gleichzeitig", exceptionMatches);
            }
            RuleContent content = parseRule(exceptionMatches.getFirst());
            return new RuleEvaluation(Outcome.MATCH, exceptionMatches.getFirst(),
                    content != null ? content.consequence() : "",
                    "Exception match (überschreibt generelle Regel)", List.of());
        }
        if (ruleMatches.size() == 1) {
            RuleContent content = parseRule(ruleMatches.getFirst());
            return new RuleEvaluation(Outcome.MATCH, ruleMatches.getFirst(),
                    content != null ? content.consequence() : "", "Match", List.of());
        }
        if (ruleMatches.size() > 1) {
            return new RuleEvaluation(Outcome.INDETERMINATE, null, null,
                    "Mehrdeutigkeit: mehrere Regeln greifen gleichzeitig", ruleMatches);
        }
        if (missingFact) {
            return new RuleEvaluation(Outcome.INDETERMINATE, null, null,
                    "Erforderliche Fakten fehlen — keine deterministische Entscheidung", List.of());
        }
        if (anyEvaluable) {
            return new RuleEvaluation(Outcome.NO_MATCH, null, null, "Keine Regel passt", List.of());
        }
        return new RuleEvaluation(Outcome.INDETERMINATE, null, null,
                indeterminateReason != null ? indeterminateReason : "Keine auswertbare Regel", List.of());
    }

    /** Generic facts from the intent — the only way the evaluator learns about the query. */
    public static Map<String, Object> factsFrom(StructuredIntent intent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (intent != null) {
            intent.amountEur().ifPresent(v -> facts.put(FactVocabulary.AMOUNT, v));
            intent.hours().ifPresent(v -> facts.put(FactVocabulary.HOURS, v));
            intent.distanceKm().ifPresent(v -> facts.put(FactVocabulary.DISTANCE_KM, v));
            intent.salaryGrade().ifPresent(v -> facts.put(FactVocabulary.SALARY_GRADE, v));
            intent.salaryStep().ifPresent(v -> facts.put(FactVocabulary.SALARY_STEP, v));
        }
        return facts;
    }

    private boolean temporallyApplicable(StructuredKnowledgeItem item, LocalDate asOf) {
        return (item.effectiveFrom() == null || !item.effectiveFrom().isAfter(asOf))
                && (item.effectiveUntil() == null || !item.effectiveUntil().isBefore(asOf));
    }

    /** Evaluates all predicates; null = indeterminate (missing fact or invalid predicate). */
    private Boolean evaluatePredicates(List<RulePredicate> predicates, Map<String, Object> facts) {
        for (RulePredicate p : predicates) {
            if (p == null || !RulePredicate.isKnownOp(p.op()) || !facts.containsKey(p.fact())) {
                return null;
            }
            // Currency is semantic data: a predicate denominated in one currency
            // must never silently match a fact denominated in another.
            if (p.currency() != null) {
                Object factCurrency = facts.get("currency");
                if (factCurrency != null && !p.currency().equalsIgnoreCase(factCurrency.toString())) {
                    return false;
                }
            }
            Boolean ok = compare(p, facts.get(p.fact()));
            if (ok == null) {
                return null;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deterministic comparison with explicit boundary semantics.
     * Numeric facts and values are compared as BigDecimal — never with
     * floating-point arithmetic; LE is the inclusive upper bound, LT the
     * exclusive one. No locale-dependent parsing exists in the core.
     */
    private Boolean compare(RulePredicate p, Object factValue) {
        if (factValue instanceof Number n) {
            java.math.BigDecimal fact = java.math.BigDecimal.valueOf(n.doubleValue());
            if (!(p.value() instanceof java.math.BigDecimal expected)) {
                return null;
            }
            int cmp = fact.compareTo(expected);
            return switch (p.op()) {
                case "EQ" -> cmp == 0;
                case "NE" -> cmp != 0;
                case "LT" -> cmp < 0;
                case "LE" -> cmp <= 0;
                case "GT" -> cmp > 0;
                case "GE" -> cmp >= 0;
                default -> null;
            };
        }
        if (factValue instanceof String s && p.value() instanceof String expected) {
            return switch (p.op()) {
                case "EQ" -> s.equalsIgnoreCase(expected);
                case "NE" -> !s.equalsIgnoreCase(expected);
                default -> null;
            };
        }
        return null;
    }

    /**
     * Parses a predicate from its JSON form; null when the shape is invalid.
     * Numeric values are parsed as BigDecimal — monetary comparisons never use
     * floating-point arithmetic, and no locale-specific number parsing exists.
     */
    private static RulePredicate parsePredicate(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String fact = node.has("fact") ? node.get("fact").asText() : null;
        String op = node.has("op") ? node.get("op").asText() : null;
        if (fact == null || fact.isBlank() || op == null || op.isBlank()
                || !node.has("value") || node.get("value").isNull()) {
            return null;
        }
        Object value;
        if (node.get("value").isNumber()) {
            value = node.get("value").decimalValue(); // BigDecimal
        } else if (node.get("value").isTextual()) {
            value = node.get("value").asText(); // equality predicates only
        } else {
            return null;
        }
        String currency = node.has("currency") && !node.get("currency").isNull()
                ? node.get("currency").asText() : null;
        return new RulePredicate(fact.trim(), op.trim(), value,
                currency == null || currency.isBlank() ? null : currency.trim());
    }

    private RuleContent parseRule(StructuredKnowledgeItem item) {
        try {
            JsonNode payload = objectMapper.readTree(item.payloadJson());
            if (payload == null || !payload.isObject()) {
                return null;
            }
            String condition = payload.has("condition") ? payload.get("condition").asText() : null;
            String consequence = payload.has("consequence") ? payload.get("consequence").asText() : null;
            if (condition == null || condition.isBlank() || consequence == null || consequence.isBlank()) {
                return null;
            }
            List<RulePredicate> predicates = new ArrayList<>();
            JsonNode preds = payload.get("predicates");
            if (preds != null && preds.isArray()) {
                for (JsonNode p : preds) {
                    RulePredicate parsed = parsePredicate(p);
                    if (parsed != null) {
                        predicates.add(parsed);
                    }
                }
            }
            return new RuleContent(condition, consequence, List.copyOf(predicates));
        } catch (Exception e) {
            log.warn("RULE payload parse failed for item {}: {}", item.id(), e.getMessage());
            return null;
        }
    }

    private record RuleContent(String condition, String consequence, List<RulePredicate> predicates) {
    }
}
