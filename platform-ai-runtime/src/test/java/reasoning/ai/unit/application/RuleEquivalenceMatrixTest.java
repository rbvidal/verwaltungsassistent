package reasoning.ai.unit.application;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.knowledge.ThresholdTable;
import reasoning.ai.model.DecisionResult;
import reasoning.ai.model.DecisionStrategy;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence matrix: legacy procurement vs the generic RULE path.
 *
 * <p>The rules mirror the REAL Stage-2 extracted ACTIVE payloads from
 * AV §55 LHO (document e8a033cc), with predicates added by the extended
 * extraction contract. Cases where the extracted knowledge genuinely differs
 * from legacy (lower bands, boundary inclusivity) are asserted as the generic
 * outcome and reported explicitly — not forced to match.
 */
class RuleEquivalenceMatrixTest {

    /** Real extracted payload (chunk 8) + predicates from the extended contract. */
    private static final String RULE_BIS_100K = """
            {"condition":"geschätzter Auftragswert von bis zu 100 000 Euro (ohne Umsatzsteuer)",
             "predicates":[{"fact":"amount","op":"LE","value":100000}],
             "consequence":"Beschränkte Ausschreibung ohne Teilnahmewettbewerb durchgeführt werden"}""";

    private static final UUID DOC_ID = UUID.fromString("e8a033cc-2e93-40af-ab86-ac2e597d234f");

    private KnowledgeRegistry legacyRegistry() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        ThresholdTable av55 = new ThresholdTable(
                "AV zu Paragraph 55 LHO Berlin", "AV §55 LHO", LocalDate.of(2024, 1, 1));
        av55.addEntry(0, 500.0, "Kein formelles Verfahren",
                "Lieferung/Dienstleistung", List.of("Keine Genehmigung erforderlich"), "");
        av55.addEntry(500.0, 1000.0, "Direktauftrag mit Genehmigung",
                "Lieferung/Dienstleistung", List.of("Schriftliche Genehmigung der Führungskraft"), "");
        av55.addEntry(1000.0, 10_000.0, "Direktauftrag",
                "Lieferung/Dienstleistung", List.of("Vergabevermerk erforderlich"), "");
        av55.addEntry(10_000.0, 100_000.0, "Beschränkte Ausschreibung",
                "Lieferung/Dienstleistung", List.of(), "");
        av55.addEntry(100_000.0, null, "Öffentliche Ausschreibung / EU-weit",
                "Lieferung/Dienstleistung", List.of(), "");
        registry.register(av55);
        return registry;
    }

    private StructuredKnowledgeItem activeRule(String payload) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT", "AV §55 LHO Berlin (Vergabe)",
                payload, StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                DOC_ID, 1, 8, 8,
                "3.3.1 kann in Anwendung des § 8 Abs. 3 Nr. 2 die Beschränkte Ausschreibung ohne Teilnahmewettbewerb durchgeführt werden.",
                0.95, "test", null, null, null);
    }

    private DecisionRouter genericRouter(StructuredKnowledgeStore store, boolean enabled) {
        KnowledgeRegistry registry = legacyRegistry();
        registry.setStructuredKnowledgeStore(store);
        registry.setExtractionEnabled(enabled);
        return new DecisionRouter(registry, new DomainClassifier(DomainKnowledge.skeletal()),
                null, new RegexSemanticIntentParser());
    }

    private DecisionResult route(DecisionRouter router, String question) {
        var result = router.route(question);
        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertNotNull(result.decision());
        return result.decision();
    }

    private String legacyDecision(String question) {
        DecisionResult d = route(new DecisionRouter(legacyRegistry(),
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser()), question);
        return d instanceof DecisionResult.ProcurementDecision pd ? pd.procedure() : d.decision();
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

    // ── Matrix ──

    @Test
    void eur18000ReachesTheSpecificActiveRuleAndMatchesLegacy() {
        DecisionRouter router = genericRouter(
                new ActiveStore(List.of(activeRule(RULE_BIS_100K))), true);
        DecisionResult d = route(router, "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?");

        assertTrue(d instanceof DecisionResult.RuleDecision, "generic RULE path must be used");
        assertTrue(d.decision().contains("Beschränkte Ausschreibung"));
        assertEquals("Beschränkte Ausschreibung", legacyDecision(
                "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?"),
                "legacy says Beschränkte Ausschreibung for the 10.000-100.000 band");
        assertTrue(d.source().contains("e8a033cc-2e93-40af-ab86-ac2e597d234f@v1"),
                "provenance must identify the extracted document/version: " + d.source());
    }

    @Test
    void midBandAmountsAreEquivalent() {
        for (String q : List.of(
                "IT-Auftrag über 50.000 Euro freihändig vergeben?",
                "IT-Auftrag über 99.999,99 Euro freihändig vergeben?",
                "IT-Auftrag über 12.000 Euro freihändig vergeben?")) {
            DecisionResult d = route(genericRouter(
                    new ActiveStore(List.of(activeRule(RULE_BIS_100K))), true), q);
            assertTrue(d.decision().contains("Beschränkte Ausschreibung"), q);
            assertEquals("Beschränkte Ausschreibung", legacyDecision(q), q);
        }
    }

    @Test
    void boundary100kDiffersBecauseExtractedRuleIsInclusive() {
        // Legacy half-open band: 100.000 falls OUTSIDE 10.000-100.000 →
        // "Öffentliche Ausschreibung / EU-weit". Extracted rule "bis zu 100 000"
        // (LE) includes 100.000 → "Beschränkte Ausschreibung". Genuine semantic
        // difference of the extracted knowledge — asserted, not forced.
        String q = "IT-Auftrag über 100.000 Euro freihändig vergeben?";
        DecisionResult d = route(genericRouter(
                new ActiveStore(List.of(activeRule(RULE_BIS_100K))), true), q);
        assertTrue(d.decision().contains("Beschränkte Ausschreibung"), "generic: inclusive LE");
        assertEquals("Öffentliche Ausschreibung / EU-weit", legacyDecision(q),
                "legacy: half-open upper bound excluded");
    }

    @Test
    void above100kGenericRuleDoesNotMatchAndLegacyFallbackOperates() {
        String q = "IT-Auftrag über 120.000 Euro freihändig vergeben?";
        DecisionResult d = route(genericRouter(
                new ActiveStore(List.of(activeRule(RULE_BIS_100K))), true), q);
        // Generic RULE: NO_MATCH → no ACTIVE THRESHOLD in the store → legacy fallback.
        assertTrue(d instanceof DecisionResult.ProcurementDecision);
        assertEquals("Öffentliche Ausschreibung / EU-weit",
                ((DecisionResult.ProcurementDecision) d).procedure());
    }

    @Test
    void lowerBandDiffersBecauseExtractedCorpusIsCoarser() {
        // Legacy: 1.000-10.000 → "Direktauftrag". Extracted ACTIVE rule set has
        // only the ≤100.000 band → generic says "Beschränkte Ausschreibung".
        // Genuine corpus granularity difference — reported, not forced.
        String q = "IT-Auftrag über 5.000 Euro freihändig vergeben?";
        DecisionResult d = route(genericRouter(
                new ActiveStore(List.of(activeRule(RULE_BIS_100K))), true), q);
        assertTrue(d.decision().contains("Beschränkte Ausschreibung"));
        assertEquals("Direktauftrag", legacyDecision(q));
    }

    @Test
    void flagOffKeepsLegacyBehaviorWithRulesPresent() {
        String q = "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?";
        DecisionResult d = route(genericRouter(
                new ActiveStore(List.of(activeRule(RULE_BIS_100K))), false), q);
        assertTrue(d instanceof DecisionResult.ProcurementDecision);
        assertEquals("Beschränkte Ausschreibung",
                ((DecisionResult.ProcurementDecision) d).procedure(),
                "flag off = legacy path only, extracted rules must not influence");
    }

    @Test
    void candidateRuleCannotOverrideLegacy() {
        StructuredKnowledgeItem candidate = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.RULE, "PROCUREMENT",
                "AV §55 LHO Berlin (Vergabe)", RULE_BIS_100K,
                StructuredKnowledgeStatus.CANDIDATE, LocalDate.of(2024, 1, 1), null,
                DOC_ID, 1, 8, 8, "Kandidat.", 0.9, "test", null, null, null);
        String q = "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?";
        DecisionResult d = route(genericRouter(
                new ActiveStore(List.of(candidate)), true), q);
        assertTrue(d instanceof DecisionResult.ProcurementDecision,
                "CANDIDATE rule must not execute; legacy answers");
        assertEquals("Beschränkte Ausschreibung",
                ((DecisionResult.ProcurementDecision) d).procedure());
    }
}
