package verwaltungsassistent.web.ai;

import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.DomainClassifier.DomainResult;
import reasoning.ai.knowledge.*;
import reasoning.ai.model.Domain;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.DomainKnowledge.TermWeight;
import reasoning.ai.model.RetrievalPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves Verwaltungsassistent core is genuinely reusable outside municipal context.
 *
 * Uses a synthetic INSURANCE domain with completely artificial
 * terminology. No municipal terms, no German vocabulary, no Berlin
 * authorities. The core must operate without any modification.
 */
class SyntheticDomainTest {

    @Test
    void syntheticDomain_classification() {
        // Register a synthetic domain
        Domain insurance = Domain.of("INSURANCE");

        // Build domain knowledge with artificial terms
        Map<Domain, List<TermWeight>> terms = new LinkedHashMap<>();
        terms.put(insurance, List.of(
                new TermWeight("premium", 10.0),
                new TermWeight("deductible", 8.0),
                new TermWeight("coverage", 8.0),
                new TermWeight("claim", 5.0)));
        DomainKnowledge knowledge = new DomainKnowledge(terms, Set.of(), 0.85, 0.15, 0.20);

        // Classify using generic DomainClassifier
        DomainClassifier classifier = new DomainClassifier(knowledge);
        DomainResult r = classifier.classify("What is the premium for full coverage?");

        assertEquals(insurance, r.primary(), "Synthetic domain must classify correctly");
        assertTrue(r.primaryConfidence() > 0.5, "Must have meaningful confidence");
    }

    @Test
    void syntheticDomain_structuredKnowledge() {
        // Register synthetic structured knowledge
        KnowledgeRegistry registry = new KnowledgeRegistry();
        ThresholdTable premiumTable = new ThresholdTable(
                "Premium Rules 2025", "PREMIUM_RULES", LocalDate.of(2025, 1, 1));
        premiumTable.addEntry(0, 500.0, "Standard premium", "basic",
                List.of("No deductible"), "Under $500");
        premiumTable.addEntry(500.0, 2000.0, "Enhanced premium", "standard",
                List.of("$250 deductible"), "$500-$2000");
        premiumTable.addEntry(2000.0, null, "Premium plus", "comprehensive",
                List.of("$100 deductible"), "Over $2000");
        registry.register(premiumTable);

        // Verify lookup works generically
        var entry = premiumTable.lookup(1000.0, ThresholdTable.normalizeCategory("standard"));
        assertTrue(entry.isPresent(), "Must find matching threshold entry");
        assertEquals("Enhanced premium", entry.get().procedure());

        // Verify registry listing
        assertEquals(1, registry.thresholdTables().size());
        assertEquals(3, registry.totalThresholdEntries());
    }

    @Test
    void syntheticDomain_retrievalPlanGeneric() {
        Domain insurance = Domain.of("INSURANCE");
        RetrievalPlan plan = new RetrievalPlan(insurance, null,
                List.of("insurance-policies", "coverage-rules"),
                List.of("Insurance Authority"),
                "HYBRID", 10, 3);

        assertEquals(insurance, plan.primaryDomain());
        assertFalse(plan.isGeneralDomain());
        assertEquals(2, plan.eligibleCollections().size());
    }

    @Test
    void syntheticDomain_noMunicipalLeakage() {
        // Prove that core classes contain no municipal knowledge
        KnowledgeRegistry registry = new KnowledgeRegistry();
        assertTrue(registry.salaryTables().isEmpty(), "Core must start with no salary tables");
        assertTrue(registry.travelTables().isEmpty(), "Core must start with no travel tables");
        assertTrue(registry.thresholdTables().isEmpty(), "Core must start with no threshold tables");
    }
}
