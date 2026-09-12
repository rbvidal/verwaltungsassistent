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
 * Equivalence: the legacy hardcoded procurement implementation and the new
 * generic DB-backed ACTIVE structured knowledge must produce the SAME
 * deterministic decisions (decision output, not object identity).
 */
class StructuredKnowledgeEquivalenceTest {

    private static final String AV_BOUNDS = """
            {"bounds":[
              {"min":0,"max":500,"outcome":"Kein formelles Verfahren","requirements":["Keine Genehmigung erforderlich"]},
              {"min":500,"max":1000,"outcome":"Direktauftrag mit Genehmigung","requirements":["Schriftliche Genehmigung der Führungskraft"]},
              {"min":1000,"max":10000,"outcome":"Direktauftrag","requirements":["Vergabevermerk erforderlich","Drei Vergleichsangebote (ab 500 €)"]},
              {"min":10000,"max":100000,"outcome":"Beschränkte Ausschreibung","requirements":["Ex-post-Veröffentlichung (ab 25.000 €)"]},
              {"min":100000,"max":null,"outcome":"Öffentliche Ausschreibung / EU-weit","requirements":["EU-Schwellenwerte prüfen"]}
            ]}""";

    private record Decision(DecisionStrategy strategy, String decision, String procedure,
                            List<String> requirements, String source) {
    }

    private Decision route(DecisionRouter router, String question) {
        var result = router.route(question);
        if (result.strategy() != DecisionStrategy.RULE_ENGINE || !(result.decision() instanceof DecisionResult.ProcurementDecision pd)) {
            return new Decision(result.strategy(), null, null, List.of(), null);
        }
        return new Decision(result.strategy(), pd.decision(), pd.procedure(),
                pd.requirements(), pd.source());
    }

    private DecisionRouter legacyRouter() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        ThresholdTable av55 = new ThresholdTable(
                "AV zu Paragraph 55 LHO Berlin", "AV §55 LHO", LocalDate.of(2024, 1, 1));
        av55.addEntry(0, 500.0, "Kein formelles Verfahren",
                "Lieferung/Dienstleistung", List.of("Keine Genehmigung erforderlich"), "");
        av55.addEntry(500.0, 1000.0, "Direktauftrag mit Genehmigung",
                "Lieferung/Dienstleistung", List.of("Schriftliche Genehmigung der Führungskraft"), "");
        av55.addEntry(1000.0, 10_000.0, "Direktauftrag",
                "Lieferung/Dienstleistung", List.of("Vergabevermerk erforderlich", "Drei Vergleichsangebote (ab 500 €)"), "");
        av55.addEntry(10_000.0, 100_000.0, "Beschränkte Ausschreibung",
                "Lieferung/Dienstleistung", List.of("Ex-post-Veröffentlichung (ab 25.000 €)"), "");
        av55.addEntry(100_000.0, null, "Öffentliche Ausschreibung / EU-weit",
                "Lieferung/Dienstleistung", List.of("EU-Schwellenwerte prüfen"), "");
        registry.register(av55);
        return newRouter(registry);
    }

    private DecisionRouter genericRouter() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        registry.setStructuredKnowledgeStore(new ActiveStore(List.of(
                new StructuredKnowledgeItem(
                        UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT",
                        "AV §55 LHO", AV_BOUNDS, StructuredKnowledgeStatus.ACTIVE,
                        LocalDate.of(2024, 1, 1), null,
                        UUID.randomUUID(), 1, 3, 7,
                        "Direktauftrag bis 10.000 Euro fuer Lieferungen und Dienstleistungen. "
                                + "Beschraenkte Ausschreibung bis 100.000 Euro. Vergabevermerk erforderlich.",
                        0.97, "test", null, null, null))));
        registry.setExtractionEnabled(true);
        return newRouter(registry);
    }

    private DecisionRouter newRouter(KnowledgeRegistry registry) {
        return new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser());
    }

    private void assertEquivalent(String question) {
        Decision legacy = route(legacyRouter(), question);
        Decision generic = route(genericRouter(), question);
        assertEquals(DecisionStrategy.RULE_ENGINE, legacy.strategy(),
                "question must hit the deterministic path: " + question);
        assertEquals(legacy.strategy(), generic.strategy());
        assertEquals(legacy.decision(), generic.decision(),
                "decision output must be identical for: " + question);
        assertEquals(legacy.procedure(), generic.procedure());
        assertEquals(legacy.requirements(), generic.requirements());
        assertNotNull(generic.source());
        assertTrue(generic.source().contains("@v"),
                "provenance must carry document/version identity: " + generic.source());
    }

    @Test
    void boundaryMatrixProducesIdenticalDecisions() {
        for (String q : List.of(
                "IT-Auftrag über 250 Euro — brauche ich eine Ausschreibung?",
                "IT-Auftrag über 499,99 Euro freihändig vergeben?",
                "IT-Auftrag über 500 Euro freihändig vergeben?",
                "IT-Auftrag über 999,99 Euro freihändig vergeben?",
                "IT-Auftrag über 1.000 Euro freihändig vergeben?",
                "IT-Auftrag über 9.999,99 Euro freihändig vergeben?",
                "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?",
                "IT-Auftrag über 99.999,99 Euro freihändig vergeben?",
                "IT-Auftrag über 100.000 Euro freihändig vergeben?",
                "Softwarelizenz für 800 Euro beschaffen — welche Vergabeart?",
                "IT-Dienstleistung über 5.000 Euro — Direktauftrag möglich?",
                "Cloud-Abo für 250.000 Euro — Ausschreibung?")) {
            assertEquivalent(q);
        }
    }

    private static final class ActiveStore implements StructuredKnowledgeStore {
        private final List<StructuredKnowledgeItem> active;

        ActiveStore(List<StructuredKnowledgeItem> active) {
            this.active = active;
        }

        @Override
        public List<StructuredKnowledgeItem> findActive(String kind, String domain) {
            return active.stream()
                    .filter(i -> i.kind().name().equals(kind) && i.domain().equals(domain))
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findBySource(UUID documentId, int version) {
            return active.stream()
                    .filter(i -> i.sourceDocumentId().equals(documentId)
                            && i.sourceDocumentVersion() == version)
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findByStatus(StructuredKnowledgeStatus status) {
            return active.stream().filter(i -> i.status() == status).toList();
        }

        @Override
        public Optional<StructuredKnowledgeItem> findById(UUID id) {
            return active.stream().filter(i -> i.id().equals(id)).findFirst();
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
