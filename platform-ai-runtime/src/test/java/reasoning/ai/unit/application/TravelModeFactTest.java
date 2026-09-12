package reasoning.ai.unit.application;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.application.TableEvaluator;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.model.DecisionResult;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.FactVocabulary;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Categorical "mode" fact: accommodation (with receipt 80 / flat 20 —
 * migrated from the deleted dead RuleEngine test spec) and overnight
 * An-/Abreise semantics, expressed with the generic TABLE evaluator.
 * No travel-specific evaluator exists.
 */
class TravelModeFactTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private final TableEvaluator evaluator = new TableEvaluator(new ObjectMapper());

    private StructuredKnowledgeItem table(String payload) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.TABLE, "TRAVEL", "Reisekosten",
                payload, StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 1, 1, "Quelle.", 0.95, "test", null, null, null);
    }

    private static final String ACCOMMODATION = """
            {"columns":["mode","rateEur"],
             "rows":[["withReceipt",80],["flat",20]],
             "lookup":{"mode":"mode"},
             "result":["rateEur"]}""";

    private static final String BANDS_WITH_MODE = """
            {"columns":["mode","hoursMin","hoursMax","allowanceEur","description"],
             "rows":[["standard",8,11,6,"8-11h"],["standard",11,24,12,"11-24h"],
                     ["standard",24,null,24,"24 Stunden"],["overnight",0,24,12,"An-/Abreisetag"]],
             "lookup":{"mode":"mode","hoursMin":"hours","hoursMax":"hours"},
             "result":["allowanceEur","description"]}""";

    // ── Accommodation (migrated from the deleted RuleEngine test spec) ──

    @Test
    void accommodationWithReceiptIs80() {
        var result = evaluator.evaluate(List.of(table(ACCOMMODATION)),
                Map.of(FactVocabulary.MODE, "withReceipt"), TODAY);
        assertTrue(result.isMatch());
        assertEquals(80.0, ((Number) result.row().get("rateEur")).doubleValue(), 0.001);
    }

    @Test
    void accommodationFlatIs20() {
        var result = evaluator.evaluate(List.of(table(ACCOMMODATION)),
                Map.of(FactVocabulary.MODE, "flat"), TODAY);
        assertTrue(result.isMatch());
        assertEquals(20.0, ((Number) result.row().get("rateEur")).doubleValue(), 0.001);
    }

    @Test
    void accommodationWithoutQualifierIsIndeterminate() {
        var result = evaluator.evaluate(List.of(table(ACCOMMODATION)), Map.of(), TODAY);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome(),
                "missing mode must never guess which accommodation rate applies");
    }

    // ── Overnight / An-/Abreise ──

    @Test
    void overnightAnreiseBandIs12() {
        var result = evaluator.evaluate(List.of(table(BANDS_WITH_MODE)),
                Map.of(FactVocabulary.MODE, "overnight", "hours", 12.0), TODAY);
        assertTrue(result.isMatch());
        assertEquals(12.0, ((Number) result.row().get("allowanceEur")).doubleValue(), 0.001);
    }

    @Test
    void standardBandIsUnaffectedByMode() {
        var result = evaluator.evaluate(List.of(table(BANDS_WITH_MODE)),
                Map.of(FactVocabulary.MODE, "standard", "hours", 12.0), TODAY);
        assertTrue(result.isMatch());
        assertEquals(12.0, ((Number) result.row().get("allowanceEur")).doubleValue(), 0.001);
    }

    @Test
    void overnightOutsideBandIsSafeNoMatch() {
        var result = evaluator.evaluate(List.of(table(BANDS_WITH_MODE)),
                Map.of(FactVocabulary.MODE, "overnight", "hours", 25.0), TODAY);
        assertEquals(TableEvaluator.Outcome.INDETERMINATE, result.outcome());
    }

    // ── Router: intent overnight flag → mode fact ──

    @Test
    void routerMapsOvernightIntentToModeFact() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        registry.setStructuredKnowledgeStore(new ActiveStore(List.of(table(BANDS_WITH_MODE))));
        registry.setExtractionEnabled(true);
        DecisionRouter router = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser());

        var result = router.route("Dienstreise mit Übernachtung für 12 Stunden — Tagegeld?");
        assertTrue(result.decision() instanceof DecisionResult.TravelDecision);
        assertEquals(12.0, ((DecisionResult.TravelDecision) result.decision()).allowanceEur(), 0.001);
    }

    @Test
    void routerStandardTripStillAnswersHourlyBand() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        registry.setStructuredKnowledgeStore(new ActiveStore(List.of(table(BANDS_WITH_MODE))));
        registry.setExtractionEnabled(true);
        DecisionRouter router = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new RegexSemanticIntentParser());

        var result = router.route("Tagegeld für 10 Stunden Dienstreise?");
        assertEquals(6.0, ((DecisionResult.TravelDecision) result.decision()).allowanceEur(), 0.001);
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
