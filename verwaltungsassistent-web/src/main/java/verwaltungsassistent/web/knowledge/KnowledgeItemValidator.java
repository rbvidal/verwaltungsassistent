package verwaltungsassistent.web.knowledge;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.model.ClaimVerification;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation gate between LLM output and executable knowledge.
 *
 * <p>LLM extraction always enters as CANDIDATE. Promotion to ACTIVE requires
 * all of: deterministic structural validation, provenance validation,
 * temporal applicability, and LLM entailment against the cited source excerpt.
 * Unknown temporal applicability (no effectiveFrom and no known document
 * validity) NEVER becomes ACTIVE — the item remains CANDIDATE/RAG-only.
 */
@Component
public class KnowledgeItemValidator {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeItemValidator.class);

    private final ObjectMapper objectMapper;
    private final ClaimVerificationService claimVerifier;

    public KnowledgeItemValidator(ObjectMapper objectMapper, ClaimVerificationService claimVerifier) {
        this.objectMapper = objectMapper;
        this.claimVerifier = claimVerifier;
    }

    /** Outcome of validating one candidate. */
    public record Outcome(StructuredKnowledgeStatus status, String reason) {
        public boolean isActive() {
            return status == StructuredKnowledgeStatus.ACTIVE;
        }
    }

    /**
     * Result of the deterministic validation phase (structure, provenance,
     * temporal applicability) — performed without any LLM call.
     */
    public record Stage(StructuredKnowledgeStatus status, String reason, boolean entailmentRequired) {
        public static Stage reject(String reason) {
            return new Stage(StructuredKnowledgeStatus.REJECTED, reason, false);
        }

        public static Stage keepCandidate(String reason) {
            return new Stage(StructuredKnowledgeStatus.CANDIDATE, reason, false);
        }

        public static Stage requireEntailment() {
            return new Stage(null, "Entailment erforderlich", true);
        }
    }

    /**
     * Deterministic validation phase only (no LLM). Candidates that pass are
     * marked {@code entailmentRequired} and must be concluded with a verdict.
     */
    public Stage deterministicStage(StructuredKnowledgeItem candidate, boolean documentValidityKnown) {
        StructuralCheck structural = checkStructure(candidate);
        if (!structural.valid) {
            return Stage.reject(structural.reason);
        }
        if (!hasProvenance(candidate)) {
            return Stage.reject("Provenance unvollständig (source document/version/excerpt erforderlich)");
        }
        // Numeric support: THRESHOLD bounds and RULE predicate values must be
        // explicitly supported by the source excerpt — LLM-invented numbers
        // (e.g. a fabricated 130 km boundary from a per-km rate) must never
        // become executable. Failure keeps the item as CANDIDATE (knowledge is
        // retained, execution is prevented).
        if (!numericValuesSupported(candidate)) {
            return Stage.keepCandidate("Numeric value nicht durch Quelltext gestützt — bleibt RAG-only");
        }
        // Temporal: unknown applicability must never silently become current validity.
        boolean temporalKnown = candidate.effectiveFrom() != null || documentValidityKnown;
        if (!temporalKnown) {
            return Stage.keepCandidate("Temporal applicability unbekannt — bleibt RAG-only");
        }
        return Stage.requireEntailment();
    }

    /**
     * Final outcome of a stage. For entailment-required stages, only an
     * ENTAILED verdict promotes to ACTIVE; anything else (CONTRADICTED,
     * UNKNOWN, or a verification failure surfaced as UNKNOWN) stays CANDIDATE.
     */
    public Outcome conclude(Stage stage, ClaimVerification.Verdict verdict) {
        if (stage == null || !stage.entailmentRequired()) {
            return new Outcome(stage != null && stage.status() != null ? stage.status() : StructuredKnowledgeStatus.CANDIDATE,
                    stage != null && stage.reason() != null ? stage.reason() : "Unknown stage");
        }
        if (verdict == ClaimVerification.Verdict.ENTAILED) {
            return new Outcome(StructuredKnowledgeStatus.ACTIVE, "Validated");
        }
        return new Outcome(StructuredKnowledgeStatus.CANDIDATE, "Entailment nicht bestätigt (kein ACTIVE)");
    }

    /**
     * Validates a candidate against its source excerpt (single-item entry,
     * performs the entailment call itself).
     *
     * @param documentValidityKnown whether the source document carries explicit
     *                              validity dates (validFrom/validUntil) that can back the item
     */
    public Outcome validate(StructuredKnowledgeItem candidate, boolean documentValidityKnown) {
        Stage stage = deterministicStage(candidate, documentValidityKnown);
        if (!stage.entailmentRequired()) {
            return conclude(stage, null);
        }
        ClaimVerification.Verdict verdict = verifyEntailment(candidate);
        return conclude(stage, verdict);
    }

    /**
     * Validates multiple candidates (typically from the same source chunk) with
     * ONE claim-batch entailment call for all entailment-eligible candidates.
     * Each candidate keeps its individual verdict and its own promotion
     * decision; a verification failure is fail-safe (UNKNOWN → never ACTIVE).
     */
    public List<Outcome> validateBatch(List<StructuredKnowledgeItem> candidates,
                                       boolean documentValidityKnown) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Stage> stages = new ArrayList<>();
        List<Integer> pendingIndexes = new ArrayList<>();
        for (StructuredKnowledgeItem c : candidates) {
            Stage stage = deterministicStage(c, documentValidityKnown);
            if (stage.entailmentRequired()) {
                pendingIndexes.add(stages.size());
            }
            stages.add(stage);
        }

        List<ClaimVerification.Verdict> verdicts = verifyBatch(pendingIndexes, candidates);

        List<Outcome> outcomes = new ArrayList<>();
        int verdictIndex = 0;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).entailmentRequired()) {
                outcomes.add(conclude(stages.get(i), verdicts.get(verdictIndex++)));
            } else {
                outcomes.add(conclude(stages.get(i), null));
            }
        }
        return outcomes;
    }

    private List<ClaimVerification.Verdict> verifyBatch(List<Integer> pendingIndexes,
                                                        List<StructuredKnowledgeItem> candidates) {
        try {
            List<String> claims = pendingIndexes.stream()
                    .map(i -> claimFor(candidates.get(i))).toList();
            List<String> excerpts = pendingIndexes.stream()
                    .map(i -> excerptFor(candidates.get(i))).toList();
            List<List<ClaimVerification>> results = claimVerifier.verifyAllClaims(claims, excerpts);
            List<ClaimVerification.Verdict> verdicts = new ArrayList<>();
            for (int i = 0; i < pendingIndexes.size(); i++) {
                List<ClaimVerification> inner =
                        results != null && i < results.size() ? results.get(i) : List.of();
                verdicts.add(inner.isEmpty()
                        ? ClaimVerification.Verdict.UNKNOWN : inner.getFirst().verdict());
            }
            return verdicts;
        } catch (Exception e) {
            log.warn("Batched entailment verification failed: {}", e.getMessage());
            return pendingIndexes.stream().map(i -> ClaimVerification.Verdict.UNKNOWN).toList();
        }
    }

    /** The verification claim for an item ("key: payloadJson"). */
    public static String claimFor(StructuredKnowledgeItem item) {
        return (item.key() != null ? item.key() : item.kind().name())
                + ": " + item.payloadJson();
    }

    /** The verification evidence excerpt for an item (truncated to the verifier limit). */
    public static String excerptFor(StructuredKnowledgeItem item) {
        String excerpt = item.sourceExcerpt();
        return excerpt.length() > 500 ? excerpt.substring(0, 500) : excerpt;
    }

    private ClaimVerification.Verdict verifyEntailment(StructuredKnowledgeItem item) {
        try {
            List<List<ClaimVerification>> results = claimVerifier.verifyAllClaims(
                    List.of(claimFor(item)), List.of(excerptFor(item)));
            if (results.isEmpty() || results.getFirst().isEmpty()) {
                return ClaimVerification.Verdict.UNKNOWN;
            }
            return results.getFirst().getFirst().verdict();
        } catch (Exception e) {
            log.warn("Entailment verification failed: {}", e.getMessage());
            return ClaimVerification.Verdict.UNKNOWN;
        }
    }

    private boolean hasProvenance(StructuredKnowledgeItem i) {
        return i.sourceDocumentId() != null
                && i.sourceDocumentVersion() > 0
                && i.sourceExcerpt() != null && !i.sourceExcerpt().isBlank();
    }

    // ── Numeric support check (application/extraction boundary) ──

    /**
     * Verifies that every numeric value in the structured payload (THRESHOLD
     * bounds, RULE predicate values) is explicitly supported by the source
     * excerpt. Locale-tolerant semantic normalization: German "1.000,50" and
     * English "1,000.50" both normalize to the same BigDecimal; grouping
     * separators are never mistaken for decimals. The core evaluator is not
     * involved — this lives at the extraction/application boundary.
     */
    private boolean numericValuesSupported(StructuredKnowledgeItem item) {
        String excerpt = item.sourceExcerpt();
        if (excerpt == null || excerpt.isBlank()) {
            return true; // provenance check already handled blank excerpts
        }
        java.util.Set<java.math.BigDecimal> sourceNumbers = numbersIn(excerpt);
        try {
            JsonNode payload = objectMapper.readTree(item.payloadJson());
            if (item.kind() == StructuredKnowledgeKind.THRESHOLD) {
                JsonNode bounds = payload.get("bounds");
                if (bounds != null && bounds.isArray()) {
                    for (JsonNode b : bounds) {
                        // min == 0 is the implied natural lower bound for amounts
                        // and durations — never a fabrication risk. Any other min
                        // must be explicitly supported by the source.
                        if (b.has("min") && b.get("min").isNumber()
                                && b.get("min").decimalValue().compareTo(java.math.BigDecimal.ZERO) != 0
                                && !supported(sourceNumbers, b.get("min").decimalValue())) {
                            return false;
                        }
                        if (b.has("max") && b.get("max").isNumber()
                                && !supported(sourceNumbers, b.get("max").decimalValue())) {
                            return false;
                        }
                    }
                }
            }
            if (item.kind() == StructuredKnowledgeKind.RULE) {
                JsonNode predicates = payload.get("predicates");
                if (predicates != null && predicates.isArray()) {
                    for (JsonNode p : predicates) {
                        if (p.has("value") && p.get("value").isNumber()
                                && !supported(sourceNumbers, p.get("value").decimalValue())) {
                            return false;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Structural validation already handled malformed payloads.
            return true;
        }
        return true;
    }

    /** Scale-insensitive membership: 100000.5 and 100000.50 are the same value. */
    private static boolean supported(java.util.Set<java.math.BigDecimal> sourceNumbers,
                                     java.math.BigDecimal target) {
        java.math.BigDecimal normalized = target.stripTrailingZeros();
        for (java.math.BigDecimal v : sourceNumbers) {
            if (v.compareTo(normalized) == 0) {
                return true;
            }
        }
        return false;
    }

    /** All distinct normalized numeric values mentioned in a text. */
    static java.util.Set<java.math.BigDecimal> numbersIn(String text) {
        java.util.Set<java.math.BigDecimal> result = new java.util.HashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        // Merge digit-adjacent spaces so German thousands "10 000" form one token.
        String merged = text.replaceAll("(?<=\\d)\\s+(?=\\d)", "");
        java.util.regex.Matcher m = NUMBER_TOKEN.matcher(merged);
        while (m.find()) {
            java.math.BigDecimal value = normalizeNumber(m.group());
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private static final java.util.regex.Pattern NUMBER_TOKEN = java.util.regex.Pattern.compile(
            "\\d+(?:[.,]\\d+)*");

    /**
     * Locale-tolerant normalization of a numeric token:
     * <ul>
     *   <li>spaces removed (German thousands: "100 000");</li>
     *   <li>a separator followed by exactly three digits is a grouping
     *       separator ("1.000" → 1000, "1,000" → 1000);</li>
     *   <li>the LAST separator followed by one/two digits is the decimal
     *       separator ("1.000,50" → 1000.50, "0,35" → 0.35, "3.5" → 3.5);</li>
     *   <li>no separator → plain integer ("130" → 130).</li>
     * </ul>
     */
    static java.math.BigDecimal normalizeNumber(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.replace(" ", "");
        if (!t.matches("\\d+([.,]\\d+)*")) {
            return null;
        }
        int lastSep = Math.max(t.lastIndexOf('.'), t.lastIndexOf(','));
        if (lastSep < 0) {
            return new java.math.BigDecimal(t);
        }
        String decimalPart = t.substring(lastSep + 1);
        String intPart = t.substring(0, lastSep).replace(".", "").replace(",", "");
        if (decimalPart.length() <= 2) {
            return new java.math.BigDecimal(intPart + "." + decimalPart);
        }
        // three digits after the last separator → grouping, not decimal
        return new java.math.BigDecimal(intPart + decimalPart);
    }

    // ── Deterministic structural validation ──

    private StructuralCheck checkStructure(StructuredKnowledgeItem i) {
        if (i.kind() == null || i.domain() == null || i.domain().isBlank()
                || i.key() == null || i.key().isBlank()
                || i.payloadJson() == null || i.payloadJson().isBlank()) {
            return StructuralCheck.invalid("Pflichtfelder fehlen (kind/domain/key/payload)");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(i.payloadJson());
        } catch (Exception e) {
            return StructuralCheck.invalid("payload ist kein gültiges JSON: " + e.getMessage());
        }
        if (payload == null || !payload.isObject()) {
            return StructuralCheck.invalid("payload muss ein JSON-Objekt sein");
        }
        if (i.effectiveFrom() != null && i.effectiveUntil() != null
                && i.effectiveFrom().isAfter(i.effectiveUntil())) {
            return StructuralCheck.invalid("effectiveFrom nach effectiveUntil");
        }
        return switch (i.kind()) {
            case THRESHOLD -> checkThreshold(payload);
            case TABLE -> checkTable(payload);
            case RULE -> checkRulePayload(payload);
            case DEFINITION, EXCEPTION, EFFECTIVE_DATE -> checkTextPayload(payload);
        };
    }

    private StructuralCheck checkRulePayload(JsonNode payload) {
        JsonNode condition = payload.get("condition");
        JsonNode consequence = payload.get("consequence");
        if (condition == null || condition.asText().isBlank()
                || consequence == null || consequence.asText().isBlank()) {
            return StructuralCheck.invalid("RULE benötigt payload.condition und payload.consequence");
        }
        // Predicates are optional (rules without them are valid knowledge but
        // not deterministically executable). When present they must have the
        // generic shape {fact, op, value} with a known comparison operator.
        JsonNode predicates = payload.get("predicates");
        if (predicates != null) {
            if (!predicates.isArray()) {
                return StructuralCheck.invalid("RULE predicates muss ein Array sein");
            }
            for (JsonNode p : predicates) {
                if (!p.has("fact") || p.get("fact").asText().isBlank()) {
                    return StructuralCheck.invalid("RULE Prädikat ohne fact");
                }
                String op = p.has("op") ? p.get("op").asText() : "";
                if (!reasoning.ai.model.RulePredicate.isKnownOp(op)) {
                    return StructuralCheck.invalid("RULE Prädikat mit ungültigem op: " + op);
                }
                if (!p.has("value") || p.get("value").isNull()) {
                    return StructuralCheck.invalid("RULE Prädikat ohne value");
                }
                if (p.has("currency") && !p.get("currency").isNull()
                        && p.get("currency").asText().isBlank()) {
                    return StructuralCheck.invalid("RULE Prädikat mit leerem currency");
                }
            }
        }
        return StructuralCheck.pass();
    }

    private StructuralCheck checkThreshold(JsonNode payload) {
        JsonNode bounds = payload.get("bounds");
        if (bounds == null || !bounds.isArray() || bounds.isEmpty()) {
            return StructuralCheck.invalid("THRESHOLD benötigt nicht-leeres bounds-Array");
        }
        double previousMax = Double.NEGATIVE_INFINITY;
        for (JsonNode b : bounds) {
            if (!b.has("min") || !b.get("min").isNumber()) {
                return StructuralCheck.invalid("THRESHOLD bound ohne numerisches min");
            }
            double min = b.get("min").asDouble();
            double max = b.has("max") && !b.get("max").isNull() ? b.get("max").asDouble() : Double.POSITIVE_INFINITY;
            if (max < min) {
                return StructuralCheck.invalid("THRESHOLD bound: max < min");
            }
            if (min < previousMax) {
                return StructuralCheck.invalid("THRESHOLD bounds überlappen oder sind unsortiert");
            }
            previousMax = max;
            if (!b.has("outcome") || b.get("outcome").asText().isBlank()) {
                return StructuralCheck.invalid("THRESHOLD bound ohne outcome");
            }
        }
        return StructuralCheck.pass();
    }

    private StructuralCheck checkTable(JsonNode payload) {
        JsonNode columns = payload.get("columns");
        JsonNode rows = payload.get("rows");
        if (columns == null || !columns.isArray() || columns.isEmpty()) {
            return StructuralCheck.invalid("TABLE benötigt nicht-leeres columns-Array");
        }
        if (rows == null || !rows.isArray()) {
            return StructuralCheck.invalid("TABLE benötigt rows-Array");
        }
        int width = columns.size();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != width) {
                return StructuralCheck.invalid("TABLE row hat nicht die Spaltenanzahl " + width);
            }
        }
        // Optional lookup/result contract: lookup columns must exist and map to
        // canonical facts; result columns must exist. Malformed tables must not
        // become ACTIVE deterministic knowledge.
        JsonNode lookup = payload.get("lookup");
        if (lookup != null && lookup.isObject()) {
            var it = lookup.fields();
            while (it.hasNext()) {
                var entry = it.next();
                if (!containsText(columns, entry.getKey())) {
                    return StructuralCheck.invalid("TABLE lookup-Spalte existiert nicht: " + entry.getKey());
                }
                if (!reasoning.ai.model.FactVocabulary.isCanonical(entry.getValue().asText())) {
                    return StructuralCheck.invalid("TABLE lookup-Fakt nicht kanonisch: " + entry.getValue().asText());
                }
            }
        } else if (lookup != null) {
            return StructuralCheck.invalid("TABLE lookup muss ein Objekt sein");
        }
        JsonNode result = payload.get("result");
        if (result != null && result.isArray()) {
            for (JsonNode r : result) {
                if (!containsText(columns, r.asText())) {
                    return StructuralCheck.invalid("TABLE result-Spalte existiert nicht: " + r.asText());
                }
            }
        } else if (result != null) {
            return StructuralCheck.invalid("TABLE result muss ein Array sein");
        }
        return StructuralCheck.pass();
    }

    private static boolean containsText(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private StructuralCheck checkTextPayload(JsonNode payload) {
        JsonNode text = payload.get("text");
        if (text == null || text.asText().isBlank()) {
            return StructuralCheck.invalid("kind benötigt payload.text");
        }
        return StructuralCheck.pass();
    }

    private record StructuralCheck(boolean valid, String reason) {
        static StructuralCheck pass() {
            return new StructuralCheck(true, "");
        }

        static StructuralCheck invalid(String reason) {
            return new StructuralCheck(false, reason);
        }
    }
}
