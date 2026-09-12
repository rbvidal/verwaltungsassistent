package reasoning.ai.application;

import reasoning.ai.model.FactVocabulary;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-agnostic deterministic evaluator for ACTIVE TABLE knowledge.
 *
 * <p>Contract (inside payloadJson):
 * <pre>
 * {
 *   "columns": ["hoursMin","hoursMax","allowanceEur"],
 *   "rows":    [[8,11,6],[11,24,12]],
 *   "lookup":  {"hoursMin":"hours","hoursMax":"hours"},   // column → canonical fact
 *   "result":  ["allowanceEur"]                            // columns returned on match
 * }
 * </pre>
 * Lookup semantics: a fact mapped to ONE column matches by equality; a fact
 * mapped to TWO columns matches the inclusive range [min,max] (null max =
 * open). A table without {@code lookup} is a constant (single-row) table.
 * Exactly one matching row → MATCH; multiple → explicit conflict/INDETERMINATE;
 * none → NO_MATCH. Order-independent, BigDecimal arithmetic, ACTIVE-only,
 * temporal eligibility, no locale/domain knowledge.
 */
@Component
public class TableEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TableEvaluator.class);

    private final ObjectMapper objectMapper;

    public TableEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public enum Outcome { MATCH, NO_MATCH, INDETERMINATE }

    public record TableEvaluation(Outcome outcome, StructuredKnowledgeItem item,
                                  Map<String, Object> row, String reason,
                                  List<StructuredKnowledgeItem> competing) {
        public boolean isMatch() {
            return outcome == Outcome.MATCH;
        }
    }

    public TableEvaluation evaluate(List<StructuredKnowledgeItem> items,
                                    Map<String, Object> facts, LocalDate asOf) {
        LocalDate effective = asOf != null ? asOf : LocalDate.now();
        List<StructuredKnowledgeItem> matches = new ArrayList<>();
        List<StructuredKnowledgeItem> competing = new ArrayList<>();
        Map<String, Object> matchedRow = null;
        String indeterminateReason = null;

        for (StructuredKnowledgeItem item : items) {
            if (item == null || item.status() != StructuredKnowledgeStatus.ACTIVE
                    || item.kind() != StructuredKnowledgeKind.TABLE) {
                continue;
            }
            if (!temporallyApplicable(item, effective)) {
                continue;
            }
            TableContent content = parseTable(item);
            if (content == null) {
                indeterminateReason = "Malformed TABLE payload";
                continue;
            }
            RowMatch match = matchRow(content, facts);
            if (match.indeterminate()) {
                indeterminateReason = "Erforderliche Fakten fehlen oder Lookup nicht auswertbar";
                continue;
            }
            if (match.multipleRows()) {
                // Two rows of the same table match → genuine ambiguity, never a
                // silent first-row selection.
                competing.add(item);
                continue;
            }
            if (match.rowIndex() >= 0) {
                matches.add(item);
                matchedRow = content.rowValues(match.rowIndex());
            }
        }

        if (matches.size() == 1 && competing.isEmpty()) {
            return new TableEvaluation(Outcome.MATCH, matches.getFirst(), matchedRow,
                    "Match", List.of());
        }
        if (matches.size() > 1 || !competing.isEmpty()) {
            List<StructuredKnowledgeItem> all = new ArrayList<>(matches);
            all.addAll(competing);
            return new TableEvaluation(Outcome.INDETERMINATE, null, null,
                    "Mehrdeutigkeit: mehrere Tabellenzeilen/-items greifen gleichzeitig", all);
        }
        return new TableEvaluation(Outcome.INDETERMINATE, null, null,
                indeterminateReason != null ? indeterminateReason : "Keine passende Zeile", List.of());
    }

    private boolean temporallyApplicable(StructuredKnowledgeItem item, LocalDate asOf) {
        return (item.effectiveFrom() == null || !item.effectiveFrom().isAfter(asOf))
                && (item.effectiveUntil() == null || !item.effectiveUntil().isBefore(asOf));
    }

    /**
     * Row match result: {@code rowIndex} >= 0 = exactly one matching row,
     * {@code multipleRows} = more than one row of the table matches
     * (genuine ambiguity), {@code indeterminate} = missing fact/malformed.
     */
    private RowMatch matchRow(TableContent content, Map<String, Object> facts) {
        if (content.lookup().isEmpty()) {
            // Constant table: exactly one row.
            return content.rows().size() == 1
                    ? new RowMatch(0, false, false) : new RowMatch(-1, false, false);
        }
        int firstMatch = -1;
        int matchCount = 0;
        for (int r = 0; r < content.rows().size(); r++) {
            List<Object> row = content.rows().get(r);
            boolean rowOk = true;
            java.util.Set<String> processed = new java.util.HashSet<>();
            for (Map.Entry<String, String> e : content.lookup().entrySet()) {
                String column = e.getKey();
                if (processed.contains(column)) {
                    continue;
                }
                String fact = e.getValue();
                if (!facts.containsKey(fact)) {
                    return new RowMatch(-1, true, false); // missing fact → indeterminate
                }
                Object factValue = facts.get(fact);
                // Same fact mapped to two columns (min/max band) → half-open range,
                // evaluated ONCE for the pair (the partner column is skipped).
                String other = bandPartner(content, column, fact);
                if (other != null) {
                    if (processed.contains(other)) {
                        continue;
                    }
                    processed.add(column);
                    processed.add(other);
                    int ci = content.columnIndex(column);
                    int oi = content.columnIndex(other);
                    if (ci < 0 || oi < 0 || ci >= row.size() || oi >= row.size()) {
                        return new RowMatch(-1, true, false);
                    }
                    BigDecimal min = asNumber(row.get(Math.min(ci, oi)));
                    BigDecimal max = asNumber(row.get(Math.max(ci, oi)));
                    if (min == null || !inBand(factValue, min, max)) {
                        rowOk = false;
                        break;
                    }
                } else {
                    int ci = content.columnIndex(column);
                    if (ci < 0 || ci >= row.size()) {
                        return new RowMatch(-1, true, false);
                    }
                    if (!equalsValue(factValue, row.get(ci))) {
                        rowOk = false;
                        break;
                    }
                }
            }
            if (rowOk) {
                matchCount++;
                if (firstMatch < 0) firstMatch = r;
            }
        }
        return new RowMatch(matchCount == 1 ? firstMatch : -1, false, matchCount > 1);
    }

    private String bandPartner(TableContent content, String column, String fact) {
        for (Map.Entry<String, String> e : content.lookup().entrySet()) {
            if (!e.getKey().equals(column) && e.getValue().equals(fact)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Half-open band: min ≤ fact < max (null max = open), matching THRESHOLD/legacy semantics. */
    private boolean inBand(Object factValue, BigDecimal min, BigDecimal max) {
        if (!(factValue instanceof Number n)) {
            return false;
        }
        BigDecimal fact = BigDecimal.valueOf(n.doubleValue());
        return fact.compareTo(min) >= 0 && (max == null || fact.compareTo(max) < 0);
    }

    private boolean equalsValue(Object factValue, Object cellValue) {
        if (factValue instanceof Number n && cellValue instanceof Number c) {
            return BigDecimal.valueOf(n.doubleValue())
                    .compareTo(BigDecimal.valueOf(c.doubleValue())) == 0;
        }
        if (factValue instanceof String s && cellValue instanceof String c) {
            return s.equalsIgnoreCase(c);
        }
        return false;
    }

    private static BigDecimal asNumber(Object o) {
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return null;
    }

    private TableContent parseTable(StructuredKnowledgeItem item) {
        try {
            JsonNode payload = objectMapper.readTree(item.payloadJson());
            if (payload == null || !payload.isObject()) {
                return null;
            }
            JsonNode columnsNode = payload.get("columns");
            JsonNode rowsNode = payload.get("rows");
            if (columnsNode == null || !columnsNode.isArray() || columnsNode.isEmpty()
                    || rowsNode == null || !rowsNode.isArray()) {
                return null;
            }
            List<String> columns = new ArrayList<>();
            columnsNode.forEach(c -> columns.add(c.asText()));
            List<List<Object>> rows = new ArrayList<>();
            for (JsonNode row : rowsNode) {
                if (!row.isArray()) {
                    return null;
                }
                List<Object> cells = new ArrayList<>();
                for (JsonNode cell : row) {
                    if (cell.isNumber()) {
                        cells.add(cell.decimalValue());
                    } else if (cell.isTextual()) {
                        cells.add(cell.asText());
                    } else if (cell.isNull()) {
                        cells.add(null);
                    } else {
                        return null;
                    }
                }
                rows.add(cells);
            }
            Map<String, String> lookup = new LinkedHashMap<>();
            JsonNode lookupNode = payload.get("lookup");
            if (lookupNode != null && lookupNode.isObject()) {
                lookupNode.fields().forEachRemaining(e ->
                        lookup.put(e.getKey(), e.getValue().asText()));
                for (String column : lookup.keySet()) {
                    if (!columns.contains(column)) {
                        return null;
                    }
                }
                if (!lookup.values().stream().allMatch(FactVocabulary::isCanonical)) {
                    return null; // unsupported fact names → malformed (never executable)
                }
            }
            List<String> result = new ArrayList<>();
            JsonNode resultNode = payload.get("result");
            if (resultNode != null && resultNode.isArray()) {
                resultNode.forEach(c -> result.add(c.asText()));
                if (!columns.containsAll(result)) {
                    return null;
                }
            }
            return new TableContent(columns, rows, lookup, result);
        } catch (Exception e) {
            log.warn("TABLE payload parse failed for item {}: {}", item.id(), e.getMessage());
            return null;
        }
    }

    private record TableContent(List<String> columns, List<List<Object>> rows,
                                Map<String, String> lookup, List<String> result) {
        int columnIndex(String column) {
            return columns.indexOf(column);
        }

        Map<String, Object> rowValues(int rowIndex) {
            List<Object> row = rows.get(rowIndex);
            List<String> resultColumns = result.isEmpty()
                    ? columns.stream().filter(c -> !lookup.containsKey(c)).toList()
                    : result;
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : resultColumns) {
                int ci = columns.indexOf(column);
                if (ci >= 0 && ci < row.size()) {
                    values.put(column, row.get(ci));
                }
            }
            return values;
        }
    }

    private record RowMatch(int rowIndex, boolean indeterminate, boolean multipleRows) {
    }
}
