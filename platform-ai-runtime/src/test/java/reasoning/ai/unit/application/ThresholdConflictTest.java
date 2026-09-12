package reasoning.ai.unit.application;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.GenericRuleEvaluator;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.knowledge.ThresholdTable;
import reasoning.ai.model.DecisionResult;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.FactVocabulary;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THRESHOLD conflict semantics + canonical fact vocabulary.
 *
 * <p>Multiple matching ACTIVE thresholds must never be resolved by
 * first-match/insertion order — the result is an explicit conflict that falls
 * through to the legacy fallback. Facts use the canonical vocabulary only.
 */
class ThresholdConflictTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private StructuredKnowledgeItem threshold(String key, double min, Double max, String outcome) {
        String maxJson = max == null ? "null" : Double.toString(max);
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", key,
                "{\"bounds\":[{\"min\":" + min + ",\"max\":" + maxJson
                        + ",\"outcome\":\"" + outcome + "\",\"requirements\":[]}]}",
                StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 8, 8, "Quelle.", 0.95, "test", null, null, null);
    }

    private DecisionRouter router() {
        return new DecisionRouter(new KnowledgeRegistry(),
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser());
    }

    // ── Evaluator-level ──

    @Test
    void singleMatchingThresholdIsDeterministic() {
        var items = List.of(
                threshold("0-10k", 0, 10_000.0, "Verhandlungsvergabe"),
                threshold("10-100k", 10_000.0, 100_000.0, "Beschränkte Ausschreibung"));
        var result = router().evaluateThresholds(items, 5_000.0);
        assertTrue(result.match());
        assertEquals("0-10k", result.item().key());
        assertFalse(result.isConflict());
    }

    @Test
    void overlappingItemsWithOnlyOneMatchingBoundStillConflictOnSharedRange() {
        // 5.000 lies in BOTH 0-10k and 0-100k → conflict, never first-match.
        var items = List.of(
                threshold("0-10k", 0, 10_000.0, "Verhandlungsvergabe"),
                threshold("0-100k", 0, 100_000.0, "Beschränkte Ausschreibung"));
        var result = router().evaluateThresholds(items, 5_000.0);
        assertFalse(result.match());
        assertTrue(result.isConflict());
        assertEquals(2, result.competing().size());
    }

    @Test
    void twoOverlappingActiveThresholdsProduceConflict() {
        var items = List.of(
                threshold("0-25k", 0, 25_000.0, "elektronische Vergabe"),
                threshold("0-100k", 0, 100_000.0, "Beschränkte Ausschreibung"));
        var result = router().evaluateThresholds(items, 18_000.0);
        assertFalse(result.match(), "overlap must never silently select one threshold");
        assertTrue(result.isConflict());
        assertEquals(2, result.competing().size());
    }

    @Test
    void threeOverlappingActiveThresholdsProduceConflict() {
        var items = List.of(
                threshold("0-25k", 0, 25_000.0, "elektronische Vergabe"),
                threshold("0-100k", 0, 100_000.0, "Beschränkte Ausschreibung"),
                threshold("0-10k", 0, 10_000.0, "Verhandlungsvergabe"));
        var result = router().evaluateThresholds(items, 18_000.0);
        assertFalse(result.match());
        assertTrue(result.isConflict());
        assertEquals(2, result.competing().size(), "only the 0-10k item does not match 18.000");
    }

    @Test
    void resultIsIndependentOfListOrder() {
        var a = threshold("A-25k", 0, 25_000.0, "elektronische Vergabe");
        var b = threshold("B-100k", 0, 100_000.0, "Beschränkte Ausschreibung");
        var forward = router().evaluateThresholds(List.of(a, b), 18_000.0);
        var backward = router().evaluateThresholds(List.of(b, a), 18_000.0);
        assertEquals(forward.isConflict(), backward.isConflict());
        assertEquals(forward.competing().size(), backward.competing().size());
        List<String> idsF = forward.competing().stream().map(i -> i.id().toString()).sorted().toList();
        List<String> idsB = backward.competing().stream().map(i -> i.id().toString()).sorted().toList();
        assertEquals(idsF, idsB, "conflict result must not depend on list/DB/insertion order");
    }

    @Test
    void noMatchingThresholdFallsThrough() {
        var result = router().evaluateThresholds(
                List.of(threshold("0-10k", 0, 10_000.0, "Verhandlungsvergabe")), 18_000.0);
        assertFalse(result.match());
        assertFalse(result.isConflict());
    }

    @Test
    void boundaryAndNonOverlappingBehaviorIsUnchanged() {
        // Half-open intervals: 10000 matches 0-10000 but NOT 10000-20000.
        var items = List.of(
                threshold("0-10k", 0, 10_000.0, "A"),
                threshold("10-20k", 10_000.0, 20_000.0, "B"));
        assertTrue(router().evaluateThresholds(items, 9_999.99).match());
        assertTrue(router().evaluateThresholds(items, 10_000.0).match());
        assertEquals("10-20k", router().evaluateThresholds(items, 10_000.0).item().key());
        assertFalse(router().evaluateThresholds(items, 20_000.0).match());
    }

    // ── Router-level: conflict falls through to legacy (the live 18.000 case) ──

    @Test
    void overlappingActiveThresholdsFallBackToLegacyInsteadOfArbitrarySelection() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        ThresholdTable av55 = new ThresholdTable("AV zu Paragraph 55 LHO Berlin", "AV §55 LHO",
                LocalDate.of(2024, 1, 1));
        av55.addEntry(10_000.0, 100_000.0, "Beschränkte Ausschreibung",
                "Lieferung/Dienstleistung", List.of(), "");
        registry.register(av55);
        registry.setStructuredKnowledgeStore(new ActiveStore(List.of(
                threshold("0-25k", 0, 25_000.0, "elektronische Vergabe"),
                threshold("0-100k", 0, 100_000.0, "Beschränkte Ausschreibung"))));
        registry.setExtractionEnabled(true);
        DecisionRouter router = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser());

        var result = router.route("Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?");

        assertEquals(reasoning.ai.model.DecisionStrategy.RULE_ENGINE, result.strategy());
        assertTrue(result.decision() instanceof DecisionResult.ProcurementDecision,
                "conflict must fall through to the legacy table, not an arbitrary generic pick");
        assertEquals("Beschränkte Ausschreibung",
                ((DecisionResult.ProcurementDecision) result.decision()).procedure());
    }

    // ── Fact vocabulary ──

    @Test
    void unsupportedFactNameCannotExecute() {
        var rule = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT", "AV §55 LHO",
                "{\"condition\":\"Kosten bis 150\",\"predicates\":[{\"fact\":\"cost\",\"op\":\"LE\",\"value\":150}],\"consequence\":\"mündlich\"}",
                StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 34, 34, "Kosten bis 150 Euro.", 0.95, "test", null, null, null);
        GenericRuleEvaluator evaluator = new GenericRuleEvaluator(new com.fasterxml.jackson.databind.ObjectMapper());
        var result = evaluator.evaluate(List.of(rule),
                GenericRuleEvaluator.factsFrom(new reasoning.ai.model.StructuredIntent(
                        "q", null, "PROCUREMENT_THRESHOLD",
                        java.util.Map.of("amountEur", 100.0))), TODAY);
        assertEquals(GenericRuleEvaluator.Outcome.INDETERMINATE, result.outcome(),
                "unsupported fact must fail into INDETERMINATE, never execute");
    }

    @Test
    void canonicalFactVocabularyIsDefinedAndUsed() {
        assertEquals(List.of("amount", "hours", "distanceKm", "salaryGrade", "salaryStep", "mode"),
                FactVocabulary.supportedFacts());
        assertTrue(FactVocabulary.isCanonical("amount"));
        assertTrue(FactVocabulary.isCanonical("mode"));
        assertFalse(FactVocabulary.isCanonical("cost"));
        assertFalse(FactVocabulary.isCanonical("Auftragswert"));
        // factsFrom produces only canonical keys.
        var facts = GenericRuleEvaluator.factsFrom(new reasoning.ai.model.StructuredIntent(
                "q", null, "PROCUREMENT_THRESHOLD",
                java.util.Map.of("amountEur", 500.0)));
        assertTrue(facts.keySet().stream().allMatch(FactVocabulary::isCanonical));
        assertEquals(500.0, facts.get("amount"));
    }

    private static final class ActiveStore implements StructuredKnowledgeStore {
        private final List<StructuredKnowledgeItem> items;

        ActiveStore(List<StructuredKnowledgeItem> items) {
            this.items = items;
        }

        @Override
        public List<StructuredKnowledgeItem> findActive(String kind, String domain) {
            return items.stream()
                    .filter(i -> i.status() == StructuredKnowledgeStatus.ACTIVE)
                    .filter(i -> i.kind().name().equals(kind) && i.domain().equals(domain))
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findBySource(UUID documentId, int version) {
            return items.stream()
                    .filter(i -> documentId.equals(i.sourceDocumentId()) && i.sourceDocumentVersion() == version)
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findByStatus(StructuredKnowledgeStatus status) {
            return items.stream().filter(i -> i.status() == status).toList();
        }

        @Override
        public Optional<StructuredKnowledgeItem> findById(UUID id) {
            return items.stream().filter(i -> i.id().equals(id)).findFirst();
        }

        @Override
        public StructuredKnowledgeItem save(StructuredKnowledgeItem item) {
            return item;
        }

        @Override
        public List<StructuredKnowledgeItem> saveAll(List<StructuredKnowledgeItem> items) {
            return items;
        }

        @Override
        public void markStatus(UUID id, StructuredKnowledgeStatus status) {
        }

        @Override
        public int supersedeBySource(UUID documentId, int version, String extractedBy) {
            return 0;
        }
    }
}
