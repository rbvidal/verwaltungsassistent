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
 * Safety boundary and precedence:
 * <ul>
 *   <li>only ACTIVE generic items can influence deterministic execution;</li>
 *   <li>ACTIVE generic knowledge takes precedence over legacy hardcoded tables;</li>
 *   <li>without ACTIVE generic knowledge, legacy fallback remains available while
 *       rollback mode is enabled (feature flag off → legacy behavior unchanged).</li>
 * </ul>
 */
class KnowledgeSafetyBoundaryTest {

    private static final String GENERIC_BOUNDS = """
            {"bounds":[
              {"min":0,"max":500,"outcome":"Kein formelles Verfahren","requirements":["Keine Genehmigung erforderlich"]},
              {"min":500,"max":1000,"outcome":"Direktauftrag mit Genehmigung","requirements":["Schriftliche Genehmigung der Führungskraft"]},
              {"min":1000,"max":8000,"outcome":"Direktauftrag (neue Wertgrenze)","requirements":["Vergabevermerk erforderlich"]},
              {"min":8000,"max":null,"outcome":"Beschränkte Ausschreibung","requirements":["Ex-post-Veröffentlichung"]}
            ]}""";

    private KnowledgeRegistry registryWithLegacy() {
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

    private StructuredKnowledgeItem genericThreshold(StructuredKnowledgeStatus status) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT",
                "AV §55 LHO", GENERIC_BOUNDS, status,
                LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 3, 7,
                "Direktauftrag bis 8.000 Euro. Beschraenkte Ausschreibung darueber.",
                0.97, "test", null, null, null);
    }

    private DecisionRouter router(KnowledgeRegistry registry) {
        return new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser());
    }

    private DecisionResult.ProcurementDecision procurement(DecisionRouter router, String question) {
        var result = router.route(question);
        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertNotNull(result.decision());
        assertTrue(result.decision() instanceof DecisionResult.ProcurementDecision);
        return (DecisionResult.ProcurementDecision) result.decision();
    }

    @Test
    void activeGenericKnowledgeTakesPrecedenceOverLegacy() {
        KnowledgeRegistry registry = registryWithLegacy();
        registry.setStructuredKnowledgeStore(new StubStore(List.of(genericThreshold(StructuredKnowledgeStatus.ACTIVE))));
        registry.setExtractionEnabled(true);

        // 9.000 €: legacy says "Direktauftrag" (up to 10.000); the ACTIVE
        // extracted knowledge (neue Wertgrenze 8.000) says "Beschränkte
        // Ausschreibung" — the generic ACTIVE item must win.
        DecisionResult.ProcurementDecision pd = procurement(router(registry),
                "Kann ich einen IT-Auftrag über 9.000 Euro freihändig vergeben?");
        assertEquals("Beschränkte Ausschreibung", pd.procedure());
        assertTrue(pd.source().contains("@v"), "decision provenance from the extracted item");
    }

    @Test
    void candidateKnowledgeCannotInfluenceDecisions() {
        KnowledgeRegistry registry = registryWithLegacy();
        registry.setStructuredKnowledgeStore(new StubStore(List.of(genericThreshold(StructuredKnowledgeStatus.CANDIDATE))));
        registry.setExtractionEnabled(true);

        // CANDIDATE item must not participate; legacy fallback still answers.
        DecisionResult.ProcurementDecision pd = procurement(router(registry),
                "Kann ich einen IT-Auftrag über 9.000 Euro freihändig vergeben?");
        assertEquals("Direktauftrag", pd.procedure(), "CANDIDATE knowledge must never influence the decision");
    }

    @Test
    void rejectedAndSupersededKnowledgeCannotInfluenceDecisions() {
        KnowledgeRegistry registry = registryWithLegacy();
        registry.setStructuredKnowledgeStore(new StubStore(List.of(
                genericThreshold(StructuredKnowledgeStatus.REJECTED),
                genericThreshold(StructuredKnowledgeStatus.SUPERSEDED))));
        registry.setExtractionEnabled(true);

        DecisionResult.ProcurementDecision pd = procurement(router(registry),
                "Kann ich einen IT-Auftrag über 9.000 Euro freihändig vergeben?");
        assertEquals("Direktauftrag", pd.procedure(), "non-ACTIVE knowledge must never influence the decision");
    }

    @Test
    void flagOffKeepsLegacyBehaviorEvenWithPopulatedStore() {
        KnowledgeRegistry registry = registryWithLegacy();
        registry.setStructuredKnowledgeStore(new StubStore(List.of(genericThreshold(StructuredKnowledgeStatus.ACTIVE))));
        registry.setExtractionEnabled(false); // rollback

        DecisionResult.ProcurementDecision pd = procurement(router(registry),
                "Kann ich einen IT-Auftrag über 9.000 Euro freihändig vergeben?");
        assertEquals("Direktauftrag", pd.procedure(),
                "flag off = legacy behavior unchanged, extracted knowledge must not alter decisions");
    }

    @Test
    void noGenericKnowledgeFallsBackToLegacyDeterministically() {
        KnowledgeRegistry registry = registryWithLegacy();
        registry.setStructuredKnowledgeStore(new StubStore(List.of()));
        registry.setExtractionEnabled(true);

        DecisionResult.ProcurementDecision pd = procurement(router(registry),
                "Kann ich einen IT-Auftrag über 9.000 Euro freihändig vergeben?");
        assertEquals("Direktauftrag", pd.procedure());
        assertEquals(9_000.0, pd.amount());
    }

    private static final class StubStore implements StructuredKnowledgeStore {
        private final List<StructuredKnowledgeItem> items;

        StubStore(List<StructuredKnowledgeItem> items) {
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
