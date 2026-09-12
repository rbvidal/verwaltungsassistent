package reasoning.ai.application;

import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.knowledge.*;
import reasoning.ai.model.DecisionResult;
import reasoning.ai.model.FactVocabulary;
import reasoning.ai.model.DecisionStrategy;
import reasoning.ai.model.StructuredIntent;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.search.api.GraphSearchProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Routes every question to exactly one execution strategy.
 *
 * <p>Rule-first: iterates ALL registered structured knowledge tables
 * and attempts a deterministic lookup. If no table can resolve the
 * question, falls back to retrieval.
 *
 * <p>Semantic interpretation of the question is delegated to
 * {@link SemanticIntentParser}, which produces a {@link StructuredIntent}
 * with parameters (hours, distance, amount, grade/step, query type).
 * This router consumes only the structured result — never German
 * phrases. The parser is configurable: regex (default/fallback) or
 * LLM-based (language-neutral experiment).
 *
 * <p>The core contains no domain-specific keywords or table names.
 * Domain knowledge is supplied by the application via DomainClassifier
 * (externalized YAML) and KnowledgeRegistry (registered at startup).
 */
@Component
public class DecisionRouter {

    private static final Logger log = LoggerFactory.getLogger(DecisionRouter.class);

    private final KnowledgeRegistry registry;
    private final DomainClassifier domainClassifier;
    private final GraphSearchProvider graphSearchProvider;
    private final SemanticIntentParser semanticIntentParser;
    private final boolean domainGateActive;
    private final ObjectMapper objectMapper;
    private final GenericRuleEvaluator ruleEvaluator;
    private final TableEvaluator tableEvaluator;

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionRouter(KnowledgeRegistry registry, DomainClassifier domainClassifier,
                          GraphSearchProvider graphSearchProvider,
                          SemanticIntentParser semanticIntentParser) {
        // Mode-aware: the domain-coherence gate is active only when the LLM
        // semantic parser is the active parser. The legacy regex parser never
        // produces a domain; its behavior must remain unchanged.
        this(registry, domainClassifier, graphSearchProvider, semanticIntentParser,
                semanticIntentParser instanceof LlmSemanticIntentParser);
    }

    /**
     * Manual/test constructor. {@code domainGateActive} enables the
     * domain-coherence gate explicitly (LLM semantic mode).
     */
    public DecisionRouter(KnowledgeRegistry registry, DomainClassifier domainClassifier,
                          GraphSearchProvider graphSearchProvider,
                          SemanticIntentParser semanticIntentParser,
                          boolean domainGateActive) {
        this(registry, domainClassifier, graphSearchProvider, semanticIntentParser,
                domainGateActive, new ObjectMapper());
    }

    DecisionRouter(KnowledgeRegistry registry, DomainClassifier domainClassifier,
                   GraphSearchProvider graphSearchProvider,
                   SemanticIntentParser semanticIntentParser,
                   boolean domainGateActive, ObjectMapper objectMapper) {
        this(registry, domainClassifier, graphSearchProvider, semanticIntentParser,
                domainGateActive, objectMapper, new GenericRuleEvaluator(objectMapper),
                new TableEvaluator(objectMapper));
    }

    DecisionRouter(KnowledgeRegistry registry, DomainClassifier domainClassifier,
                   GraphSearchProvider graphSearchProvider,
                   SemanticIntentParser semanticIntentParser,
                   boolean domainGateActive, ObjectMapper objectMapper,
                   GenericRuleEvaluator ruleEvaluator, TableEvaluator tableEvaluator) {
        this.registry = registry;
        this.domainClassifier = domainClassifier;
        this.graphSearchProvider = graphSearchProvider;
        this.semanticIntentParser = semanticIntentParser;
        this.domainGateActive = domainGateActive;
        this.objectMapper = objectMapper;
        this.ruleEvaluator = ruleEvaluator;
        this.tableEvaluator = tableEvaluator;
    }

    public record RoutingResult(
            DecisionStrategy strategy, DecisionResult decision, String explanation,
            StructuredIntent intent) {
        public boolean isRuleEngine() { return strategy == DecisionStrategy.RULE_ENGINE; }
        public boolean needsRetrieval() {
            return strategy == DecisionStrategy.HYBRID_RETRIEVAL
                    || strategy == DecisionStrategy.GRAPH_REASONING;
        }
    }

    private static final int MAX_QUESTION_LENGTH = 5000;

    public RoutingResult route(String question) {
        if (question == null || question.isBlank()) {
            return new RoutingResult(DecisionStrategy.HYBRID_RETRIEVAL, null,
                    "Cannot classify null or blank question", null);
        }

        // Semantic interpretation is delegated to the configurable parser.
        // The parser produces structured parameters; this router never
        // interprets natural-language phrases itself.
        StructuredIntent intent = semanticIntentParser.parse(question);
        log.info("DecisionRouter: parsed intent={} params={} domain={}",
                intent.intentType(), intent.parameters(), intent.domain());

        // Domain handling: StructuredIntent.domain is AUTHORITATIVE when present
        // (semantic-intent path). DomainClassifier is only used as fallback when
        // the parser produced no domain (legacy regex mode or LLM omission).
        if (intent.domain() != null) {
            log.info("DecisionRouter: authoritative domain from semantic intent: {}", intent.domain());
        } else {
            domainClassifier.classify(question); // legacy fallback — logging side effect only
        }

        // Try all registered structured knowledge tables generically
        var result = tryStructuredKnowledge(intent);
        if (result != null) {
            log.info("DecisionRouter → RULE_ENGINE ({})", result.getClass().getSimpleName());
            return new RoutingResult(DecisionStrategy.RULE_ENGINE, result,
                    "Structured knowledge lookup", intent);
        }

        boolean graphAvailable = graphSearchProvider != null && graphSearchProvider.isAvailable();
        DecisionStrategy s = graphAvailable ? DecisionStrategy.GRAPH_REASONING : DecisionStrategy.HYBRID_RETRIEVAL;
        log.info("DecisionRouter → {}", s);
        return new RoutingResult(s, null, graphAvailable
                ? "No deterministic match — graph-enhanced retrieval"
                : "No deterministic match — full retrieval", intent);
    }

    // ── Generic structured knowledge lookup ──

    private DecisionResult tryStructuredKnowledge(StructuredIntent intent) {
        // Domain-coherence gate (LLM semantic mode): a deterministic rule may
        // execute only when the intent carries a non-null domain matching the
        // domain its rule family requires. An isolated intent or parameter —
        // even a plausible one — never triggers a rule without coherent domain.
        // Legacy regex mode (gate inactive) preserves its existing behavior.
        if (domainGateActive && !domainCoherent(intent)) {
            log.info("DecisionRouter: domain-coherence gate refused structured lookup"
                    + " (intent={} domain={})", intent.intentType(), intent.domain());
            return null;
        }

        // Safety guard: a parameter only authorizes a deterministic rule lookup
        // when the semantic intentType declares that parameter class. An isolated
        // parameter (e.g. hours=12 with intentType=GENERAL) must never trigger a rule.
        boolean hoursAuthorized = StructuredIntent.INTENT_TRAVEL_ALLOWANCE.equals(intent.intentType());
        boolean amountAuthorized = StructuredIntent.INTENT_PROCUREMENT_THRESHOLD.equals(intent.intentType());
        boolean salaryAuthorized = StructuredIntent.INTENT_SALARY_LOOKUP.equals(intent.intentType());

        // Generic RULE path first: predicate-bearing ACTIVE rules encode specific
        // procedures (more specific than open-ended tables). INDETERMINATE or
        // NO_MATCH falls through to the generic THRESHOLD path and then to the
        // legacy fallback. Domain-agnostic — facts come from the intent.
        String ruleDomain = requiredDomainName(intent.intentType());
        if (ruleDomain != null) {
            var ruleEval = ruleEvaluator.evaluate(
                    registry.findActive("RULE", ruleDomain),
                    GenericRuleEvaluator.factsFrom(intent), null);
            if (ruleEval.isMatch()) {
                log.info("DecisionRouter → generic RULE match ({})", ruleEval.item().id());
                StructuredKnowledgeItem item = ruleEval.item();
                String source = item.sourceDocumentId() + "@v" + item.sourceDocumentVersion();
                String effectiveDate = item.effectiveFrom() != null ? item.effectiveFrom().toString() : "";
                return new DecisionResult.RuleDecision(
                        ruleEval.consequence(), source, source, 0.98, effectiveDate, "");
            }
            if (ruleEval.outcome() == GenericRuleEvaluator.Outcome.INDETERMINATE) {
                log.info("DecisionRouter: generic RULE indeterminate ({}) — falling through", ruleEval.reason());
            }
        }

        // Salary: generic TABLE path first (ACTIVE extracted tables), then
        // grade/step lookup on all registered legacy salary tables.
        // No silent step default: a missing salaryStep means the question did not
        // state one — insufficient information must fall through to retrieval
        // rather than inventing a step and answering authoritatively.
        if (salaryAuthorized && intent.salaryGrade().isPresent()
                && intent.salaryStep().isPresent()) {
            String grade = intent.salaryGrade().get();
            int step = intent.salaryStep().get();
            String sDomain = requiredDomainName(intent.intentType());
            if (sDomain != null) {
                TableEvaluator.TableEvaluation te = tableEvaluator.evaluate(
                        registry.findActive("TABLE", sDomain),
                        GenericRuleEvaluator.factsFrom(intent), null);
                if (te.isMatch()) {
                    Object amountValue = firstNumber(te.row());
                    if (amountValue instanceof Number num) {
                        String source = te.item().sourceDocumentId() + "@v"
                                + te.item().sourceDocumentVersion();
                        String effectiveDate = te.item().effectiveFrom() != null
                                ? te.item().effectiveFrom().toString() : "";
                        return new DecisionResult.SalaryDecision(
                                grade + " Stufe " + step + " = " + plain(num.doubleValue()),
                                source, source, 0.99, grade, step, num.doubleValue(),
                                te.item().key(), effectiveDate, "");
                    }
                }
                if (te.outcome() == TableEvaluator.Outcome.INDETERMINATE) {
                    log.info("DecisionRouter: generic salary TABLE indeterminate ({}) — falling through",
                            te.reason());
                }
            }
            for (SalaryTable t : registry.salaryTables()) {
                var e = t.lookup(grade, step);
                if (e.isPresent()) {
                    var v = e.get();
                    return new DecisionResult.SalaryDecision(
                            v.grade() + " Stufe " + v.step() + " = " + plain(v.monthlyAmount()),
                            t.sourceDocument(), t.sourceDocument(), 0.99,
                            v.grade(), v.step(), v.monthlyAmount(),
                            t.payScale(), t.effectiveFrom().toString(), "");
                }
            }
        }

        // Travel: generic TABLE path first (ACTIVE extracted tables), then
        // hours/distance on all registered legacy travel tables.
        // Categorical mode fact: the intent's overnight flag maps to the
        // canonical "mode" fact; plain duration questions use "standard".
        // Values are knowledge data — the router only maps intent semantics.
        String tDomain = requiredDomainName(intent.intentType());
        if (tDomain != null) {
            Map<String, Object> travelFacts = GenericRuleEvaluator.factsFrom(intent);
            if (intent.isOvernight()) {
                travelFacts.put(FactVocabulary.MODE, "overnight");
            } else if (intent.hours().isPresent()) {
                travelFacts.put(FactVocabulary.MODE, "standard");
            }
            TableEvaluator.TableEvaluation te = tableEvaluator.evaluate(
                    registry.findActive("TABLE", tDomain), travelFacts, null);
            if (te.isMatch()) {
                String source = te.item().sourceDocumentId() + "@v"
                        + te.item().sourceDocumentVersion();
                String effectiveDate = te.item().effectiveFrom() != null
                        ? te.item().effectiveFrom().toString() : "";
                Object value = firstNumber(te.row());
                String description = firstText(te.row());
                if (value instanceof Number num) {
                    var km = intent.distanceKm();
                    if (km.isPresent() && !hoursAuthorized) {
                        // Distance-based allowance: the row value is the rate per km.
                        return new DecisionResult.TravelDecision(
                                "Kilometerpauschale: " + plain(num.doubleValue()) + " pro km",
                                source, source, 0.99, km.get(), num.doubleValue(),
                                "mileage", description != null ? description : "Kilometerpauschale",
                                effectiveDate, "");
                    }
                    var h = intent.hours();
                    if (h.isPresent() && hoursAuthorized) {
                        return new DecisionResult.TravelDecision(
                                "Tagegeld: " + plain(num.doubleValue()),
                                source, source, 0.99, h.get(), num.doubleValue(),
                                "domestic", description != null ? description : "Tagegeld",
                                effectiveDate, "");
                    }
                }
            }
            if (te.outcome() == TableEvaluator.Outcome.INDETERMINATE) {
                log.info("DecisionRouter: generic travel TABLE indeterminate ({}) — falling through",
                        te.reason());
            }
        }
        for (TravelAllowanceTable t : registry.travelTables()) {
            var km = intent.distanceKm();
            var rate = t.mileageRate();
            if (km.isPresent() && rate.isPresent()) {
                return new DecisionResult.TravelDecision(
                        "Kilometerpauschale: " + plain(rate.get()) + " pro km",
                        t.sourceDocument(), t.sourceDocument(), 0.99,
                        km.get(), rate.get(), "mileage", "Kilometerpauschale",
                        t.effectiveFrom().toString(), "");
            }
            if (hoursAuthorized) {
                var h = intent.hours();
                if (h.isPresent()) {
                    double hrs = h.get();
                    boolean overnight = intent.isOvernight();
                    var e = t.lookup(hrs, overnight, "domestic");
                    if (e.isPresent()) {
                        var v = e.get();
                        return new DecisionResult.TravelDecision(
                                "Tagegeld: " + plain(v.allowanceEur()),
                                t.sourceDocument(), t.sourceDocument(), 0.99,
                                hrs, v.allowanceEur(), v.category(), v.description(),
                                t.effectiveFrom().toString(), "");
                    }
                }
            }
        }

        // Threshold: try amount lookup on all registered threshold tables.
        // Try without category filter first (returns best match regardless of
        // procurement type), then with common VgV categories.
        if (amountAuthorized) {
            var amount = intent.amountEur();
            if (amount.isPresent()) {
                double value = amount.get();

                // Generic path first: ACTIVE THRESHOLD knowledge from the
                // persisted store (knowledge.extraction.enabled). Domain-agnostic —
                // the regulation identity lives in the item data, never in this router.
                // Exactly one matching ACTIVE threshold → deterministic decision.
                // Multiple matching ACTIVE thresholds → explicit conflict: no
                // arbitrary first-match selection — fall through to legacy safely.
                String domain = requiredDomainName(intent.intentType());
                if (domain != null) {
                    ThresholdEvaluation evaluation = evaluateThresholds(
                            registry.findActive("THRESHOLD", domain), value);
                    if (evaluation.match()) {
                        return evaluateThresholdItem(evaluation.item(), value).orElse(null);
                    }
                    if (evaluation.isConflict()) {
                        log.warn("DecisionRouter: generic THRESHOLD conflict — {} competing ACTIVE items "
                                        + "match amount {} — falling through to legacy instead of arbitrary selection",
                                evaluation.competing().size(), value);
                    }
                }

                for (ThresholdTable t : registry.thresholdTables()) {
                    // Try unfiltered first — finds the applicable entry regardless of category
                    var e = t.lookup(value, null);
                    if (e.isEmpty()) {
                        // Fall back to common VgV categories
                        for (String cat : List.of("Lieferung/Dienstleistung", "Bauleistung")) {
                            e = t.lookup(value, ThresholdTable.normalizeCategory(cat));
                            if (e.isPresent()) break;
                        }
                    }
                    if (e.isPresent()) {
                        var v = e.get();
                        return new DecisionResult.ProcurementDecision(
                                v.procedure() + ". " + String.join("; ", v.requirements()),
                                v.notes(), t.sourceDocument(), 0.98, value,
                                v.procedure(), v.requirements(), v.category(),
                                t.effectiveFrom().toString(), "");
                    }
                }
            }
        }

        return null;
    }

    /** First numeric value among the matched row's result columns (the deterministic value). */
    private static Object firstNumber(Map<String, Object> row) {
        if (row == null) return null;
        for (Object v : row.values()) {
            if (v instanceof Number) return v;
        }
        return null;
    }

    /**
     * Locale-free plain decimal rendering for decision TEXT (no currency symbol,
     * no grouping/decimal separators). Presentation formatting (€, German
     * separators) belongs to the web/explanation layer — the structured numeric
     * value stays in the decision's values().
     */
    private static String plain(double value) {
        return java.math.BigDecimal.valueOf(value).toPlainString();
    }

    /** First textual value among the matched row's result columns (optional description). */
    private static String firstText(Map<String, Object> row) {
        if (row == null) return null;
        for (Object v : row.values()) {
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    // ── Domain-coherence gate ──

    /**
     * The domain each structured-intent family requires for a deterministic
     * rule. {@code null} = the intent authorizes no deterministic rule.
     */
    private static String requiredDomainName(String intentType) {
        return switch (intentType) {
            case StructuredIntent.INTENT_TRAVEL_ALLOWANCE -> "TRAVEL";
            case StructuredIntent.INTENT_PROCUREMENT_THRESHOLD -> "PROCUREMENT";
            case StructuredIntent.INTENT_SALARY_LOOKUP -> "HR";
            default -> null;
        };
    }

    private static boolean domainCoherent(StructuredIntent intent) {
        String required = requiredDomainName(intent.intentType());
        if (required == null) return false;
        return intent.domain() != null && required.equalsIgnoreCase(intent.domain().name());
    }

    // ── Generic THRESHOLD evaluation (domain-agnostic) ──

    /**
     * Outcome of matching ACTIVE THRESHOLD items against an amount.
     * Deterministic and independent of list/DB/extraction order: the outcome is
     * a pure function of the SET of matching items.
     */
    public record ThresholdEvaluation(boolean match, StructuredKnowledgeItem item,
                               List<StructuredKnowledgeItem> competing) {
        static ThresholdEvaluation match(StructuredKnowledgeItem item) {
            return new ThresholdEvaluation(true, item, List.of());
        }

        static ThresholdEvaluation conflict(List<StructuredKnowledgeItem> competing) {
            return new ThresholdEvaluation(false, null, List.copyOf(competing));
        }

        static ThresholdEvaluation none() {
            return new ThresholdEvaluation(false, null, List.of());
        }

        public boolean isConflict() {
            return !competing.isEmpty();
        }
    }

    /**
     * Evaluates ACTIVE THRESHOLD items against an amount. Within one item the
     * bound whose half-open interval {@code min <= amount < max} contains the
     * amount matches. Exactly one matching item → MATCH; multiple matching
     * items → explicit CONFLICT with the competing items preserved (never a
     * first-match selection); none → NO match. Order-independent by design.
     */
    public ThresholdEvaluation evaluateThresholds(List<StructuredKnowledgeItem> items, double amount) {
        List<StructuredKnowledgeItem> matches = new ArrayList<>();
        for (StructuredKnowledgeItem item : items) {
            if (evaluateThresholdItem(item, amount).isPresent()) {
                matches.add(item);
            }
        }
        if (matches.size() == 1) {
            return ThresholdEvaluation.match(matches.getFirst());
        }
        if (matches.size() > 1) {
            return ThresholdEvaluation.conflict(matches);
        }
        return ThresholdEvaluation.none();
    }

    private Optional<DecisionResult> evaluateThresholdItem(StructuredKnowledgeItem item, double amount) {
        try {
            JsonNode payload = objectMapper.readTree(item.payloadJson());
            JsonNode bounds = payload.get("bounds");
            if (bounds == null || !bounds.isArray()) return Optional.empty();
            for (JsonNode b : bounds) {
                if (!b.has("min") || !b.get("min").isNumber()) return Optional.empty();
                double min = b.get("min").asDouble();
                boolean openEnded = !b.has("max") || b.get("max").isNull();
                double max = openEnded ? Double.POSITIVE_INFINITY : b.get("max").asDouble();
                if (amount < min || (!openEnded && amount >= max)) {
                    continue;
                }
                String outcome = b.has("outcome") ? b.get("outcome").asText() : "";
                List<String> requirements = new ArrayList<>();
                JsonNode reqs = b.get("requirements");
                if (reqs != null && reqs.isArray()) {
                    reqs.forEach(r -> requirements.add(r.asText()));
                }
                String source = item.sourceDocumentId() + "@v" + item.sourceDocumentVersion();
                String effectiveDate = item.effectiveFrom() != null ? item.effectiveFrom().toString() : "";
                return Optional.of(new DecisionResult.ProcurementDecision(
                        outcome + ". " + String.join("; ", requirements),
                        source, source, 0.98, amount, outcome, requirements, "",
                        effectiveDate, ""));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Generic threshold evaluation failed for item {}: {}", item.id(), e.getMessage());
            return Optional.empty();
        }
    }
}
