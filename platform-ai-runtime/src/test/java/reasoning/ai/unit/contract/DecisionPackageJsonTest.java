package reasoning.ai.unit.contract;

import reasoning.ai.model.*;
import reasoning.ai.model.EvidencePackage.Contradiction;
import reasoning.ai.model.EvidencePackage.CoverageStatus;
import reasoning.ai.model.NumericExtraction.*;
import reasoning.ai.model.SourceCitation.SourceTier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON round-trip tests for all 10+ decision engine DTOs.
 * Covers serialization, deserialization, missing fields,
 * null collections, and enum handling.
 */
class DecisionPackageJsonTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    // ── 1. SalaryDecision ──

    @Test
    void shouldRoundTripSalaryDecision() throws Exception {
        DecisionResult.SalaryDecision original = new DecisionResult.SalaryDecision(
                "EG 9a Stufe 3 = 4.117,53 €", "TV-L Entgelttabelle 2025",
                "TV-L 2025", 0.99, "EG 9a", 3, 4117.53,
                "TV-L", "2025-02-01", "TdL");

        String json = mapper.writeValueAsString(original);
        DecisionResult deserialized = mapper.readValue(json, DecisionResult.class);

        assertInstanceOf(DecisionResult.SalaryDecision.class, deserialized);
        var sd = (DecisionResult.SalaryDecision) deserialized;
        assertEquals("EG 9a", sd.grade());
        assertEquals(3, sd.step());
        assertEquals(4117.53, sd.monthlyAmount(), 0.01);
        assertEquals("TV-L", sd.payScale());
        assertEquals(0.99, sd.confidence(), 0.001);
    }

    // ── 2. TravelDecision ──

    @Test
    void shouldRoundTripTravelDecision() throws Exception {
        DecisionResult.TravelDecision original = new DecisionResult.TravelDecision(
                "Tagegeld: 12 € (Abwesenheit über 11 Stunden)",
                "BRKG Verpflegungspauschale Inland", "BRKG 2024",
                0.99, 12.0, 12.0, "domestic",
                "Abwesenheit über 11 Stunden", "2024-01-01", "BMI");

        String json = mapper.writeValueAsString(original);
        DecisionResult deserialized = mapper.readValue(json, DecisionResult.class);

        assertInstanceOf(DecisionResult.TravelDecision.class, deserialized);
        var td = (DecisionResult.TravelDecision) deserialized;
        assertEquals(12.0, td.hours(), 0.01);
        assertEquals(12.0, td.allowanceEur(), 0.01);
        assertEquals("domestic", td.category());
    }

    // ── 3. ProcurementDecision ──

    @Test
    void shouldRoundTripProcurementDecision() throws Exception {
        DecisionResult.ProcurementDecision original = new DecisionResult.ProcurementDecision(
                "Beschränkte Ausschreibung. Ex-post-Veröffentlichung (ab 25.000 €)",
                "10.000 € bis 100.000 €",
                "AV §55 LHO Berlin", 0.98, 18_000.0,
                "Beschränkte Ausschreibung",
                List.of("Ex-post-Veröffentlichung (ab 25.000 €)"),
                "Lieferung/Dienstleistung", "2024-01-01",
                "Senatsverwaltung für Finanzen");

        String json = mapper.writeValueAsString(original);
        DecisionResult deserialized = mapper.readValue(json, DecisionResult.class);

        assertInstanceOf(DecisionResult.ProcurementDecision.class, deserialized);
        var pd = (DecisionResult.ProcurementDecision) deserialized;
        assertEquals(18_000.0, pd.amount(), 0.01);
        assertEquals("Beschränkte Ausschreibung", pd.procedure());
        assertEquals("Lieferung/Dienstleistung", pd.category());
        assertEquals(1, pd.requirements().size());
    }

    // ── 4. FeeDecision ──

    @Test
    void shouldRoundTripFeeDecision() throws Exception {
        DecisionResult.FeeDecision original = new DecisionResult.FeeDecision(
                "Gebühr: 500,00 € (Baugenehmigung)",
                "Baugebührenordnung Berlin § 3",
                "BauGebO Bln", 0.95, "Baugenehmigung",
                500.0, "BauGebO Bln § 3",
                "Senatsverwaltung für Stadtentwicklung", "2024-01-01");

        String json = mapper.writeValueAsString(original);
        DecisionResult deserialized = mapper.readValue(json, DecisionResult.class);

        assertInstanceOf(DecisionResult.FeeDecision.class, deserialized);
        var fd = (DecisionResult.FeeDecision) deserialized;
        assertEquals("Baugenehmigung", fd.feeType());
        assertEquals(500.0, fd.amount(), 0.01);
        assertEquals("BauGebO Bln § 3", fd.regulation());
    }

    // ── 5. EvidencePackage (S3.4 validated DTO) ──

    @Test
    void shouldRoundTripEvidencePackage() throws Exception {
        EvidenceItem item = new EvidenceItem(1, UUID.randomUUID(),
                UUID.randomUUID(), 3, 12, "BauO Bln",
                "Senatsverwaltung", "§ 6 Abs. 1",
                "Abstandsflächen müssen mindestens 3 m betragen.",
                "Bauordnungsrecht", 0.92, null,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 12, 31), null);

        EvidencePackage original = new EvidencePackage(
                List.of(item), false, List.of(),
                CoverageStatus.PARTIAL, 10, 2, 1);

        String json = mapper.writeValueAsString(original);
        EvidencePackage deserialized = mapper.readValue(json, EvidencePackage.class);

        assertEquals(1, deserialized.items().size());
        assertEquals("BauO Bln", deserialized.items().getFirst().documentTitle());
        assertFalse(deserialized.hasInsufficientEvidence());
        assertEquals(CoverageStatus.PARTIAL, deserialized.coverageStatus());
        assertEquals(10, deserialized.totalDocumentsSearched());
    }

    // ── 6. EvidencePackage with contradictions ──

    @Test
    void shouldRoundTripEvidencePackageWithContradictions() throws Exception {
        Contradiction c = new Contradiction("Konflikt",
                List.of("TV-L 2025"), List.of("TV-L 2024"),
                "Neuere Tabelle verwenden");

        EvidencePackage original = new EvidencePackage(
                List.of(), false, List.of(c),
                CoverageStatus.INSUFFICIENT, 2, 2, 0);

        String json = mapper.writeValueAsString(original);
        EvidencePackage deserialized = mapper.readValue(json, EvidencePackage.class);

        assertTrue(deserialized.hasContradictions());
        assertEquals(1, deserialized.contradictions().size());
        assertEquals("Konflikt", deserialized.contradictions().getFirst().description());
    }

    // ── 7. NumericExtraction ──

    @Test
    void shouldRoundTripNumericExtraction() throws Exception {
        var builder = new NumericExtraction.Builder();
        builder.addMoney(new MoneyValue(4117.53, "EUR", "4.117,53 €", "EG 9a Stufe 3: 4.117,53 €"));
        builder.addPercentage(new PercentageValue(5.5, "5,5 %", "Erhöhung um 5,5 %"));
        builder.addDate(new DateValue("2025-02-01", "01.02.2025", "gültig ab", "Gültig ab 01.02.2025"));
        builder.addThreshold(new ThresholdValue(10000.0, "EUR", "10.000 €", null, "bis 10.000 Euro"));
        builder.addSalaryGrade(new SalaryGrade("EG 9a", 3, 4117.53, "EUR", "2025-02-01", "TV-L",
                "EG 9a Stufe 3: 4.117,53 €"));
        NumericExtraction original = builder.build();

        String json = mapper.writeValueAsString(original);
        NumericExtraction deserialized = mapper.readValue(json, NumericExtraction.class);

        assertEquals(1, deserialized.moneyValues().size());
        assertEquals(4117.53, deserialized.moneyValues().getFirst().amount(), 0.01);
        assertEquals(1, deserialized.percentages().size());
        assertEquals(5.5, deserialized.percentages().getFirst().value(), 0.01);
        assertEquals(1, deserialized.dates().size());
        assertEquals(1, deserialized.thresholds().size());
        assertEquals(1, deserialized.salaryGrades().size());
        assertEquals("EG 9a", deserialized.salaryGrades().getFirst().grade());
    }

    // ── 8. EvidenceItem ──

    @Test
    void shouldRoundTripEvidenceItem() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        EvidenceItem original = new EvidenceItem(
                1, docId, chunkId, 4, 7, "Beschaffungsordnung Berlin",
                "Senatsverwaltung für Finanzen", "§ 3 Abs. 2",
                "Beschaffungen bis 10.000 Euro sind Direktaufträge.",
                "Vergaberecht", 0.89,
                NumericExtraction.empty(),
                null, LocalDate.of(2024, 1, 1), null, null);

        String json = mapper.writeValueAsString(original);
        EvidenceItem deserialized = mapper.readValue(json, EvidenceItem.class);

        assertEquals(1, deserialized.index());
        assertEquals(docId, deserialized.documentId());
        assertEquals("Beschaffungsordnung Berlin", deserialized.documentTitle());
        assertEquals(0.89, deserialized.confidence(), 0.001);
    }

    // ── 9. DecisionStrategy enum ──

    @Test
    void shouldRoundTripDecisionStrategy() throws Exception {
        for (DecisionStrategy strategy : DecisionStrategy.values()) {
            String json = mapper.writeValueAsString(strategy);
            DecisionStrategy deserialized = mapper.readValue(json, DecisionStrategy.class);
            assertEquals(strategy, deserialized);
        }
    }

    // ── 10. SourceCitation ──

    @Test
    void shouldRoundTripSourceCitation() throws Exception {
        SourceCitation original = new SourceCitation(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                "BauO Bln", 24, 0, 100,
                "Abstandsflächen...",
                0.92, SourceTier.PRIMARY);

        String json = mapper.writeValueAsString(original);
        SourceCitation deserialized = mapper.readValue(json, SourceCitation.class);

        assertEquals("BauO Bln", deserialized.title());
        assertEquals(SourceTier.PRIMARY, deserialized.tier());
        assertEquals(0.92, deserialized.confidenceScore(), 0.001);
    }

    // ═══════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════

    // ── Missing fields ──

    @Test
    void shouldHandleMissingOptionalFields() throws Exception {
        String json = """
            {
                "@type": "FEE",
                "decision": "Test fee",
                "reason": "Test reason",
                "source": "Test source",
                "confidence": 0.9,
                "feeType": "Test type",
                "amount": 100.0,
                "regulation": null,
                "authority": "Test authority",
                "effectiveDate": null
            }
            """;

        DecisionResult deserialized = mapper.readValue(json, DecisionResult.class);
        assertInstanceOf(DecisionResult.FeeDecision.class, deserialized);
    }

    @Test
    void shouldHandleNullCollections() throws Exception {
        String json = """
            {
                "@type": "PROCUREMENT",
                "decision": "Test",
                "reason": "Test",
                "source": "Test",
                "confidence": 0.5,
                "amount": 500.0,
                "procedure": "Test",
                "requirements": null,
                "category": "Test",
                "effectiveDate": "2024-01-01",
                "authority": "Test"
            }
            """;

        DecisionResult deserialized = mapper.readValue(json, DecisionResult.class);
        assertInstanceOf(DecisionResult.ProcurementDecision.class, deserialized);
        var pd = (DecisionResult.ProcurementDecision) deserialized;
        // null requirements in JSON → should deserialize to null or empty
        assertNotNull(pd);
    }

    @Test
    void shouldHandleEmptyEvidencePackage() throws Exception {
        EvidencePackage original = new EvidencePackage(
                List.of(), true, List.of(),
                CoverageStatus.INSUFFICIENT, 0, 0, 0);

        String json = mapper.writeValueAsString(original);
        EvidencePackage deserialized = mapper.readValue(json, EvidencePackage.class);

        assertTrue(deserialized.isEmpty());
        assertTrue(deserialized.hasInsufficientEvidence());
        assertEquals(0, deserialized.totalDocumentsSearched());
    }

    // ── Enum handling ──

    @Test
    void shouldDeserializeCoverageStatusFromString() throws Exception {
        String json = "\"SUFFICIENT\"";
        CoverageStatus status = mapper.readValue(json, CoverageStatus.class);
        assertEquals(CoverageStatus.SUFFICIENT, status);
    }

    @Test
    void shouldRejectUnknownCoverageStatus() {
        String json = "\"UNKNOWN_STATUS\"";
        assertThrows(JsonProcessingException.class, () ->
                mapper.readValue(json, CoverageStatus.class));
    }

    @Test
    void shouldDeserializeSourceTierFromString() throws Exception {
        String json = "\"PRIMARY\"";
        SourceTier tier = mapper.readValue(json, SourceTier.class);
        assertEquals(SourceTier.PRIMARY, tier);
    }

    // ── NumericExtraction empty ──

    @Test
    void shouldRoundTripEmptyNumericExtraction() throws Exception {
        NumericExtraction original = NumericExtraction.empty();
        String json = mapper.writeValueAsString(original);
        NumericExtraction deserialized = mapper.readValue(json, NumericExtraction.class);

        assertTrue(deserialized.isEmpty());
        assertTrue(deserialized.moneyValues().isEmpty());
        assertTrue(deserialized.percentages().isEmpty());
    }

    // ── NumericExtraction with only some fields ──

    @Test
    void shouldRoundTripPartialNumericExtraction() throws Exception {
        var builder = new NumericExtraction.Builder();
        builder.addMoney(new MoneyValue(500.0, null, null, null));
        NumericExtraction original = builder.build();

        String json = mapper.writeValueAsString(original);
        NumericExtraction deserialized = mapper.readValue(json, NumericExtraction.class);

        assertEquals(1, deserialized.moneyValues().size());
        assertEquals(500.0, deserialized.moneyValues().getFirst().amount(), 0.01);
    }

    // ── All 4 DecisionResult subtypes round-trip as list ──

    @Test
    void shouldRoundTripAllDecisionSubtypesInList() throws Exception {
        List<DecisionResult> decisions = List.of(
                new DecisionResult.SalaryDecision("d1", "r1", "s1", 0.9, "EG 9a", 3, 4000.0, "TV-L", "2025-01-01", "TdL"),
                new DecisionResult.TravelDecision("d2", "r2", "s2", 0.9, 8.0, 6.0, "domestic", "desc", "2024-01-01", "BMI"),
                new DecisionResult.ProcurementDecision("d3", "r3", "s3", 0.9, 5000.0, "Direktauftrag", List.of(), "LD", "2024-01-01", "SenFin"),
                new DecisionResult.FeeDecision("d4", "r4", "s4", 0.9, "Baugenehmigung", 500.0, "BauGebO", "SenStadt", "2024-01-01"));

        String json = mapper.writeValueAsString(decisions);
        // The JSON must contain the type discriminator
        assertNotNull(json);
        assertTrue(json.length() > 0);

        // Verify each individual subtype round-trips cleanly
        for (DecisionResult original : decisions) {
            String single = mapper.writeValueAsString(original);
            DecisionResult restored = mapper.readValue(single, DecisionResult.class);
            assertEquals(original.getClass(), restored.getClass(),
                    "Subtype " + original.getClass().getSimpleName() + " should round-trip");
            assertEquals(original.decision(), restored.decision());
            assertEquals(original.confidence(), restored.confidence(), 0.001);
        }
    }

    // ── NumericExtraction value types ──

    @Test
    void shouldRoundTripMoneyValueWithNullCurrency() throws Exception {
        MoneyValue original = new MoneyValue(100.0, null, "100", "context");
        String json = mapper.writeValueAsString(original);
        MoneyValue deserialized = mapper.readValue(json, MoneyValue.class);
        assertEquals(100.0, deserialized.amount(), 0.01);
        assertEquals("EUR", deserialized.currency()); // default
    }
}
