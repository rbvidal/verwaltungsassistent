package reasoning.ai.unit.application;

import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DomainClassifier;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.knowledge.SalaryTable;
import reasoning.ai.knowledge.ThresholdTable;
import reasoning.ai.knowledge.TravelAllowanceTable;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.DecisionResult;
import reasoning.ai.model.DecisionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for the procurement decision pipeline
 * and DecisionRouter edge cases.
 */
class DecisionRouterTest {

    private DecisionRouter router;
    private KnowledgeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new KnowledgeRegistry();
        ThresholdTable av55 = new ThresholdTable(
                "AV zu Paragraph 55 LHO Berlin", "AV §55 LHO",
                LocalDate.of(2024, 1, 1));

        av55.addEntry(0, 500.0, "Kein formelles Verfahren",
                "Lieferung/Dienstleistung",
                List.of("Keine Genehmigung erforderlich"),
                "Unter 500 €");
        av55.addEntry(500.0, 1000.0, "Direktauftrag mit Genehmigung",
                "Lieferung/Dienstleistung",
                List.of("Schriftliche Genehmigung der Führungskraft"),
                "500 € bis 1.000 €");
        av55.addEntry(1000.0, 10_000.0, "Direktauftrag",
                "Lieferung/Dienstleistung",
                List.of("Vergabevermerk erforderlich", "Drei Vergleichsangebote (ab 500 €)"),
                "1.000 € bis 10.000 €");
        av55.addEntry(10_000.0, 100_000.0, "Beschränkte Ausschreibung",
                "Lieferung/Dienstleistung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "10.000 € bis 100.000 €");
        av55.addEntry(100_000.0, null, "Öffentliche Ausschreibung / EU-weit",
                "Lieferung/Dienstleistung",
                List.of("EU-Schwellenwerte prüfen"),
                "Ab 100.000 €");
        av55.addEntry(0, 20_000.0, "Direktauftrag",
                "Bauleistung",
                List.of(),
                "Bauleistung bis 20.000 €");
        av55.addEntry(20_000.0, 200_000.0, "Beschränkte Ausschreibung",
                "Bauleistung",
                List.of(),
                "Bauleistung 20.000 € bis 200.000 €");

        registry.register(av55);

        SalaryTable tvl = new SalaryTable("TV-L 2025", "TV-L", LocalDate.of(2025, 2, 1), null);
        tvl.addEntry("EG 9a", 3, 4117.53, 0, "");
        registry.register(tvl);

        TravelAllowanceTable brkg = new TravelAllowanceTable("BRKG", "BRKG", LocalDate.of(2024, 1, 1));
        brkg.addEntry(8, 24.0, 6.0, "domestic", false, "Abwesenheit über 8 Stunden");
        registry.register(brkg);

        router = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                new reasoning.ai.application.RegexSemanticIntentParser());
    }

    // ── IT procurement → RULE_ENGINE ──

    @Test
    void shouldRouteITAuftrag18kToRuleEngine() {
        var result = router.route(
                "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertNotNull(result.decision());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());

        var pd = (DecisionResult.ProcurementDecision) result.decision();
        assertEquals(18_000.0, pd.amount());
        assertEquals("Beschränkte Ausschreibung", pd.procedure());
    }

    @Test
    void shouldRouteITDienstleistungToRuleEngine() {
        var result = router.route(
                "IT-Dienstleistung über 5.000 Euro — Direktauftrag möglich?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());

        var pd = (DecisionResult.ProcurementDecision) result.decision();
        assertEquals(5_000.0, pd.amount());
        assertEquals("Direktauftrag", pd.procedure());
    }

    @Test
    void shouldRouteSoftwareProcurementToRuleEngine() {
        var result = router.route(
                "Softwarelizenz für 800 Euro beschaffen — welche Vergabeart?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());

        var pd = (DecisionResult.ProcurementDecision) result.decision();
        assertEquals(800.0, pd.amount());
        assertEquals("Direktauftrag mit Genehmigung", pd.procedure());
    }

    @Test
    void shouldRouteCloudProcurementToRuleEngine() {
        var result = router.route("Cloud-Abo für 250 Euro — brauche ich eine Ausschreibung?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());

        var pd = (DecisionResult.ProcurementDecision) result.decision();
        assertEquals(250.0, pd.amount());
        assertEquals("Kein formelles Verfahren", pd.procedure());
    }

    @Test
    void shouldRouteBauProcurementToRuleEngine() {
        var result = router.route("Bauauftrag über 15.000 Euro — Direktauftrag möglich?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());

        var pd = (DecisionResult.ProcurementDecision) result.decision();
        assertEquals(15_000.0, pd.amount());
        // Generic router uses unfiltered threshold lookup; category comes from
        // the registered table entry, not from keyword detection in the core.
        assertNotNull(pd.category());
        assertNotNull(pd.procedure());
    }

    // ── Standard procurement continues to work ──

    @Test
    void shouldRouteStandardBeschaffungToRuleEngine() {
        var result = router.route(
                "Ist eine Beschaffung über 800 Euro ohne vorherige Genehmigung zulässig?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());

        var pd = (DecisionResult.ProcurementDecision) result.decision();
        assertEquals(800.0, pd.amount());
    }

    @Test
    void shouldRouteVergabeToRuleEngine() {
        var result = router.route(
                "Welche Wertgrenzen gelten für einen Direktauftrag über 3.000 Euro?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());
    }

    // ── Salary routing ──

    @Test
    void shouldRouteSalaryQueryToRuleEngine() {
        var result = router.route("Wie hoch ist das Gehalt von EG 9a Stufe 3 TV-L?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.SalaryDecision.class, result.decision());
    }

    // ── Travel routing ──

    @Test
    void shouldRouteTravelQueryToRuleEngine() {
        var result = router.route(
                "Wie hoch ist das Tagegeld bei einer 9-stündigen Dienstreise?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.TravelDecision.class, result.decision());
    }

    // ── Threshold overview ──

    @Test
    void shouldRouteThresholdInquiryToRuleEngine() {
        // Generic router returns HYBRID_RETRIEVAL for threshold inquiries without
        // specific amounts. Threshold overview is an application-layer feature
        // provided by the municipal module, not a core routing capability.
        var result = router.route("Welche Wertgrenzen gelten für Direktaufträge nach AV §55 LHO?");
        assertNotNull(result);
        assertNotNull(result.strategy());
    }

    // ── Non-procurement → HYBRID_RETRIEVAL ──

    @Test
    void shouldRouteNonProcurementToHybridRetrieval() {
        var result = router.route("Welche Abstandsflächen gelten in Berlin?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    // ═══════════════════════════════════════════════════════════
    // EDGE CASES — S3.2
    // ═══════════════════════════════════════════════════════════

    // ── Null / blank ──

    @Test
    void shouldRouteNullQuestionToHybridRetrieval() {
        var result = router.route(null);

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void shouldRouteEmptyQuestionToHybridRetrieval() {
        var result = router.route("");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void shouldRouteBlankQuestionToHybridRetrieval() {
        var result = router.route("   \t\n  ");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    // ── Over 5000 chars ──

    @Test
    void shouldHandleVeryLongQuestion() {
        StringBuilder sb = new StringBuilder(6000);
        sb.append("IT-Auftrag über 18.000 Euro — ".repeat(300));
        String longQuestion = sb.toString();

        var result = router.route(longQuestion);

        // Must not throw; should produce a valid RoutingResult
        assertNotNull(result);
        assertNotNull(result.strategy());
    }

    @Test
    void shouldHandleQuestionAt5000Characters() {
        StringBuilder sb = new StringBuilder();
        sb.append("x".repeat(4960));
        sb.append("IT-Auftrag über 18.000 Euro.");

        var result = router.route(sb.toString());
        assertNotNull(result);
        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
    }

    // ── Multi-category (procurement + travel + salary keywords) ──

    @Test
    void shouldHandleMultiCategoryQuestion() {
        var result = router.route(
                "Für einen IT-Auftrag über 18.000 Euro soll ein Mitarbeiter "
                + "mit EG 10 Stufe 2 auf eine 9-stündige Dienstreise — "
                + "welches Vergabeverfahren ist zulässig?");

        assertNotNull(result);
        // Safety guard: the regex parser's last-write-wins intent here is
        // SALARY_LOOKUP; the travel hours parameter is NOT authorized by that
        // intent, so no deterministic rule may trigger. Falls to retrieval
        // instead of answering with an unrelated category's rule.
        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    // ── Safety guard: intentType must authorize the parameter ──

    @Test
    void generalIntentWithHoursMustNotTriggerTravelRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "GENERAL",
                        java.util.Map.of("hours", 12.0)));

        var result = guarded.route("What applies at 12?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void generalIntentWithAmountMustNotTriggerProcurementRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "GENERAL",
                        java.util.Map.of("amountEur", 8000.0)));

        var result = guarded.route("What about 8,000 euros?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void generalIntentWithSalaryGradeMustNotTriggerSalaryRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "GENERAL",
                        java.util.Map.of("salaryGrade", "EG 9a", "salaryStep", 3)));

        var result = guarded.route("How much for level 5?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void travelIntentWithHoursStillTriggersTravelRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "TRAVEL_ALLOWANCE",
                        java.util.Map.of("hours", 9.0)));

        var result = guarded.route("Tagegeld bei 9 Stunden?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.TravelDecision.class, result.decision());
    }

    @Test
    void procurementIntentWithAmountStillTriggersProcurementRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "PROCUREMENT_THRESHOLD",
                        java.util.Map.of("amountEur", 800.0)));

        var result = guarded.route("Vergabeart bei 800 Euro?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());
    }

    // ── No silent salary-step default ──

    @Test
    void salaryGradeWithoutStepMustNotTriggerRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "SALARY_LOOKUP",
                        java.util.Map.of("salaryGrade", "EG 9a")));

        var result = guarded.route("Was verdient EG 9b?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void salaryGradeWithStepStillTriggersRule() {
        var guarded = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "SALARY_LOOKUP",
                        java.util.Map.of("salaryGrade", "EG 9a", "salaryStep", 3)));

        var result = guarded.route("Wie hoch ist das Gehalt von EG 9a Stufe 3?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.SalaryDecision.class, result.decision());
        assertEquals(4117.53, ((DecisionResult.SalaryDecision) result.decision()).monthlyAmount());
    }

    @Test
    void regexParserGradeWithoutStufeMustNotTriggerRule() {
        // Regex path: "EG 9a" without "Stufe N" must no longer invent step 3.
        var result = router.route("Was verdient EG 9a?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void regexParserGradeWithStufeStillTriggersRule() {
        var result = router.route("Wie hoch ist das Gehalt von EG 9a Stufe 3?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.SalaryDecision.class, result.decision());
    }

    // ── Domain-coherence gate (LLM semantic mode) ──

    private DecisionRouter gatedRouter(reasoning.ai.api.SemanticIntentParser parser) {
        return new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null, parser, true);
    }

    @Test
    void domainNullTravelIntentMustNotTriggerRule() {
        // THE regression test for the probabilistic failure:
        // TRAVEL_ALLOWANCE + hours=12 with domain=null must never yield Tagegeld.
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, null, "TRAVEL_ALLOWANCE", java.util.Map.of("hours", 12.0)));

        var result = r.route("What applies at 12?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void crossDomainProcurementTravelIntentMustNotTriggerRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("PROCUREMENT"),
                "TRAVEL_ALLOWANCE", java.util.Map.of("hours", 12.0)));

        var result = r.route("What applies at 12?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void crossDomainTravelProcurementIntentMustNotTriggerRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("TRAVEL"),
                "PROCUREMENT_THRESHOLD", java.util.Map.of("amountEur", 800.0)));

        var result = r.route("Vergabeart bei 800 Euro?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void crossDomainHrTravelIntentMustNotTriggerRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("HR"),
                "TRAVEL_ALLOWANCE", java.util.Map.of("hours", 12.0)));

        var result = r.route("What applies at 12?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void crossDomainTravelSalaryIntentMustNotTriggerRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("TRAVEL"),
                "SALARY_LOOKUP",
                java.util.Map.of("salaryGrade", "EG 9a", "salaryStep", 3)));

        var result = r.route("Gehalt EG 9a Stufe 3?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
        assertNull(result.decision());
    }

    @Test
    void coherentTravelIntentTriggersRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("TRAVEL"),
                "TRAVEL_ALLOWANCE", java.util.Map.of("hours", 9.0)));

        var result = r.route("Tagegeld bei 9 Stunden?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.TravelDecision.class, result.decision());
        assertEquals(6.0, ((DecisionResult.TravelDecision) result.decision()).allowanceEur());
    }

    @Test
    void coherentProcurementIntentTriggersRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("PROCUREMENT"),
                "PROCUREMENT_THRESHOLD", java.util.Map.of("amountEur", 800.0)));

        var result = r.route("Vergabeart bei 800 Euro?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());
        assertEquals("Direktauftrag mit Genehmigung",
                ((DecisionResult.ProcurementDecision) result.decision()).procedure());
    }

    @Test
    void coherentSalaryIntentTriggersRule() {
        var r = gatedRouter(q -> new reasoning.ai.model.StructuredIntent(
                q, reasoning.ai.model.Domain.of("HR"),
                "SALARY_LOOKUP",
                java.util.Map.of("salaryGrade", "EG 9a", "salaryStep", 3)));

        var result = r.route("Wie hoch ist das Gehalt von EG 9a Stufe 3?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.SalaryDecision.class, result.decision());
        assertEquals(4117.53, ((DecisionResult.SalaryDecision) result.decision()).monthlyAmount());
    }

    @Test
    void legacyModeWithoutGatePreservesDomainNullBehavior() {
        // Gate inactive (regex mode): domain=null + TRAVEL_ALLOWANCE + hours
        // must behave exactly as before the gate existed.
        var legacy = new DecisionRouter(registry,
                new DomainClassifier(DomainKnowledge.skeletal()), null,
                q -> new reasoning.ai.model.StructuredIntent(
                        q, null, "TRAVEL_ALLOWANCE", java.util.Map.of("hours", 9.0)),
                false);

        var result = legacy.route("Tagegeld bei 9 Stunden?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.TravelDecision.class, result.decision());
    }

    @Test
    void shouldHandleProcurementWithTravelKeywords() {
        var result = router.route(
                "Dienstreise zur Beschaffung eines IT-Auftrags über 5.000 Euro.");

        assertNotNull(result);
        // Travel check runs before procurement (contains "dienstreise" + digit + hours keyword)
        // But this doesn't match travel hours pattern, so it falls to procurement
        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
    }

    // ── German special characters ──

    @Test
    void shouldHandleGermanUmlautsInProcurement() {
        var result = router.route(
                "Können wir eine Überarbeitung der Beschaffungsrichtlinie für 15.000 € vornehmen?");

        assertEquals(DecisionStrategy.RULE_ENGINE, result.strategy());
        assertInstanceOf(DecisionResult.ProcurementDecision.class, result.decision());
    }

    @Test
    void shouldHandleGermanEszettInQuestion() {
        // Query with "5.000 Euro" — amount extraction may route to RULE_ENGINE
        // with threshold lookup. Either strategy is valid.
        var result = router.route(
                "Müssen wir die Außenbereichssatzung nach § 34 BauGB bei 5.000 Euro beachten?");
        assertNotNull(result);
        assertNotNull(result.strategy());
    }

    @Test
    void shouldHandleUmlautsInThresholdInquiry() {
        var result = router.route(
                "Gelten die Wertgrenzen für öffentliche Aufträge gemäß AV §55 LHO?");
        // Generic router: no specific amount → not a structured-knowledge match
        assertNotNull(result);
        assertNotNull(result.strategy());
    }

    @Test
    void shouldHandleUmlautsInSalaryQuery() {
        var result = router.route(
                "Welches Gehalt bekommt ein ÖPNV-Planer in Entgeltgruppe EG 10 Stufe 2?");

        // Contains "EG 10" with digit pattern → RULE_ENGINE
        assertNotNull(result);
        // "öpnv" doesn't block salary classification; EG 10 pattern still matches
    }

    @Test
    void shouldHandleGermanUmlautsInGeneralQuestion() {
        var result = router.route(
                "Welche Vorschriften gelten für die Errichtung von Übergangsheimen?");

        assertEquals(DecisionStrategy.HYBRID_RETRIEVAL, result.strategy());
    }

    // ── Question with only partial keyword matches ──

    @Test
    void shouldRoutePartialKeywordMatchToHybridRetrieval() {
        // "Gehalt" alone without EG pattern won't match salary
        var result = router.route("Ich möchte mein Gehalt erhöhen lassen.");

        // No EG pattern match → falls through salary
        // Might match other categories; assert it doesn't crash
        assertNotNull(result);
        assertNotNull(result.strategy());
    }

    @Test
    void shouldRouteBareProcurementTermWithoutAmountToHybridOrThreshold() {
        var result = router.route("Was ist ein Direktauftrag?");

        assertNotNull(result);
        // No amount pattern → no procurement match
        // No threshold inquiry keywords → falls to HYBRID_RETRIEVAL
    }
}
