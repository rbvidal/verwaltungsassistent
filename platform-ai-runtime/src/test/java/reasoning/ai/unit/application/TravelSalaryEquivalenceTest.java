package reasoning.ai.unit.application;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.RegexSemanticIntentParser;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.knowledge.SalaryTable;
import reasoning.ai.knowledge.TravelAllowanceTable;
import reasoning.ai.model.DecisionResult;
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
 * Cross-domain equivalence: legacy TravelAllowanceTable and SalaryTable vs the
 * SAME data expressed as generic TABLE knowledge. One generic mechanism serves
 * both domains — no domain-specific evaluator logic.
 */
class TravelSalaryEquivalenceTest {

    private KnowledgeRegistry legacyRegistry() {
        KnowledgeRegistry registry = new KnowledgeRegistry();

        TravelAllowanceTable brkg = new TravelAllowanceTable("BRKG", "BRKG", LocalDate.of(2024, 1, 1));
        brkg.addEntry(24, null, 24.0, "domestic", false, "24 Stunden");
        brkg.addEntry(11, 24.0, 12.0, "domestic", false, "11-24h");
        brkg.addEntry(8, 11.0, 6.0, "domestic", false, "8-11h");
        brkg.addEntry(0, 24.0, 12.0, "domestic", true, "An-/Abreisetag");
        brkg.addEntry(0, null, 0.35, "mileage", false, "PKW pro km");
        registry.register(brkg);

        SalaryTable tvl = new SalaryTable("TV-L 2025", "TV-L", LocalDate.of(2025, 2, 1), null);
        tvl.addEntry("EG 9a", 3, 4117.53, 0, "");
        tvl.addEntry("EG 13", 1, 5100.00, 0, "");
        tvl.addEntry("EG 1", 1, 2711.20, 0, "");
        tvl.addEntry("EG 15", 6, 7600.00, 0, "");
        registry.register(tvl);
        return registry;
    }

    private StructuredKnowledgeItem travelBands() {
        return item(StructuredKnowledgeKind.TABLE, "TRAVEL", "BRKG",
                """
                {"columns":["hoursMin","hoursMax","allowanceEur","description"],
                 "rows":[[8,11,6,"8-11h"],[11,24,12,"11-24h"],[24,null,24,"24 Stunden"]],
                 "lookup":{"hoursMin":"hours","hoursMax":"hours"},
                 "result":["allowanceEur","description"]}""");
    }

    private StructuredKnowledgeItem mileageRate() {
        return item(StructuredKnowledgeKind.TABLE, "TRAVEL", "BRKG",
                "{\"columns\":[\"mode\",\"rateEur\"],\"rows\":[[\"PKW\",0.35]],\"result\":[\"rateEur\"]}");
    }

    private StructuredKnowledgeItem salaryTable() {
        return item(StructuredKnowledgeKind.TABLE, "HR", "TV-L",
                """
                {"columns":["salaryGrade","salaryStep","monthlyAmount"],
                 "rows":[["EG 1",1,2711.20],["EG 9a",3,4117.53],["EG 13",1,5100.00],["EG 15",6,7600.00]],
                 "lookup":{"salaryGrade":"salaryGrade","salaryStep":"salaryStep"},
                 "result":["monthlyAmount"]}""");
    }

    private StructuredKnowledgeItem item(StructuredKnowledgeKind kind, String domain, String key, String payload) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), kind, domain, key, payload,
                StructuredKnowledgeStatus.ACTIVE, LocalDate.of(2024, 1, 1), null,
                UUID.randomUUID(), 1, 1, 1, "Quelle.", 0.95, "test", null, null, null);
    }

    private DecisionRouter genericRouter(List<StructuredKnowledgeItem> items) {
        KnowledgeRegistry registry = legacyRegistry();
        registry.setStructuredKnowledgeStore(new ActiveStore(items));
        registry.setExtractionEnabled(true);
        return new DecisionRouter(registry, new DomainClassifier(DomainKnowledge.skeletal()),
                null, new RegexSemanticIntentParser());
    }

    private DecisionRouter legacyRouter() {
        return new DecisionRouter(legacyRegistry(), new DomainClassifier(DomainKnowledge.skeletal()),
                null, new RegexSemanticIntentParser());
    }

    private DecisionResult route(DecisionRouter router, String question) {
        var result = router.route(question);
        assertNotNull(result.decision(), "expected RULE_ENGINE decision for: " + question);
        return result.decision();
    }

    // ── TRAVEL equivalence ──

    @Test
    void travelMealAllowanceIsEquivalent() {
        DecisionRouter legacy = legacyRouter();
        DecisionRouter generic = genericRouter(List.of(travelBands()));
        for (String q : List.of(
                "Tagegeld für 8 Stunden Dienstreise?",
                "Tagegeld für 10 Stunden Dienstreise?",
                "Tagegeld für 11 Stunden Dienstreise?",
                "Tagegeld für 20 Stunden Dienstreise?",
                "Tagegeld für 24 Stunden Dienstreise?")) {
            double legacyValue = ((DecisionResult.TravelDecision) route(legacy, q)).allowanceEur();
            double genericValue = ((DecisionResult.TravelDecision) route(generic, q)).allowanceEur();
            assertEquals(legacyValue, genericValue, 0.001, q);
        }
    }

    @Test
    void mileageRateIsEquivalent() {
        double legacyValue = ((DecisionResult.TravelDecision) route(legacyRouter(),
                "Kilometerpauschale für 50 km Dienstreise?")).allowanceEur();
        double genericValue = ((DecisionResult.TravelDecision) route(
                genericRouter(List.of(mileageRate())),
                "Kilometerpauschale für 50 km Dienstreise?")).allowanceEur();
        assertEquals(legacyValue, genericValue, 0.001);
    }

    @Test
    void genericTravelDecisionCarriesItemProvenance() {
        DecisionResult.TravelDecision d = (DecisionResult.TravelDecision) route(
                genericRouter(List.of(travelBands())), "Tagegeld für 12 Stunden Dienstreise?");
        assertTrue(d.source().contains("@v"), "provenance must identify the extracted item: " + d.source());
    }

    // ── SALARY equivalence ──

    @Test
    void salaryLookupIsEquivalentAcrossGradesAndSteps() {
        DecisionRouter legacy = legacyRouter();
        DecisionRouter generic = genericRouter(List.of(salaryTable()));
        for (String q : List.of(
                "Gehalt EG 1 Stufe 1?",
                "Gehalt EG 9a Stufe 3?",
                "Gehalt EG 13 Stufe 1?",
                "Gehalt EG 15 Stufe 6?")) {
            double legacyValue = ((DecisionResult.SalaryDecision) route(legacy, q)).monthlyAmount();
            double genericValue = ((DecisionResult.SalaryDecision) route(generic, q)).monthlyAmount();
            assertEquals(legacyValue, genericValue, 0.001, q);
        }
    }

    @Test
    void unsupportedSalaryGradeDoesNotFabricateARow() {
        // Neither the generic TABLE nor the legacy table contains EG 99 —
        // the correct outcome is NO deterministic decision (safe failure),
        // never a substitution of another grade's row.
        var legacy = legacyRouter().route("Gehalt EG 99 Stufe 1?");
        var generic = genericRouter(List.of(salaryTable())).route("Gehalt EG 99 Stufe 1?");
        assertEquals(legacy.strategy(), generic.strategy(),
                "unknown grade must behave identically on both paths");
        assertTrue(generic.decision() == null
                        || !(generic.decision() instanceof DecisionResult.SalaryDecision sd
                        && sd.monthlyAmount() == 4117.53),
                "no arbitrary substitution to EG 9a Stufe 3");
        // Known grade still resolves deterministically (sanity).
        var ok = genericRouter(List.of(salaryTable())).route("Gehalt EG 9a Stufe 3?");
        assertEquals(4117.53, ((DecisionResult.SalaryDecision) ok.decision()).monthlyAmount(), 0.001);
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
