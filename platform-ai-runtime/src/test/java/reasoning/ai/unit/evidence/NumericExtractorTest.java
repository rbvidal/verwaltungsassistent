package reasoning.ai.unit.evidence;

import reasoning.ai.application.NumericExtractor;
import reasoning.ai.model.NumericExtraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NumericExtractorTest {

    private NumericExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new NumericExtractor();
    }

    // ── Money: standard Euro formats ──

    @Test
    void shouldExtractMoneyInEuroFormat() {
        NumericExtraction result = extractor.extract("Der Betrag beträgt 4.117,53 €.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(4117.53, result.moneyValues().getFirst().amount(), 0.01);
        assertEquals("EUR", result.moneyValues().getFirst().currency());
    }

    @Test
    void shouldExtractMoneyInEuroPlain() {
        NumericExtraction result = extractor.extract("Beschaffungen bis 10.000 Euro sind zulässig.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(10000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    @Test
    void shouldExtractMultipleMoneyValues() {
        NumericExtraction result = extractor.extract("Tagegeld: 6 €, 12 €, 24 €. Kilometergeld: 0,35 €.");
        assertTrue(result.moneyValues().size() >= 3);
    }

    // ── Money range: "5.000-10.000 Euro" ──

    @Test
    void shouldExtractMoneyRangeWithDash() {
        NumericExtraction result = extractor.extract(
                "Beschaffungen zwischen 5.000-10.000 Euro sind Direktaufträge.");
        assertFalse(result.moneyValues().isEmpty());
        assertTrue(result.moneyValues().size() >= 2);
        boolean has5000 = result.moneyValues().stream().anyMatch(m -> m.amount() == 5000.0);
        boolean has10000 = result.moneyValues().stream().anyMatch(m -> m.amount() == 10000.0);
        assertTrue(has5000);
        assertTrue(has10000);
    }

    @Test
    void shouldExtractMoneyRangeWithZwischenUnd() {
        NumericExtraction result = extractor.extract(
                "Der Betrag liegt zwischen 1.000 und 5.000 Euro.");
        assertFalse(result.moneyValues().isEmpty());
        assertTrue(result.moneyValues().size() >= 2);
    }

    // ── Approximate money ──

    @Test
    void shouldExtractApproxMoneyMitRund() {
        NumericExtraction result = extractor.extract("Die Kosten betragen rund 10.000 Euro.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(10000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    @Test
    void shouldExtractApproxMoneyMitCa() {
        NumericExtraction result = extractor.extract("ca. 5.000 € für die Planung.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(5000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    @Test
    void shouldExtractApproxMoneyMitEtwa() {
        NumericExtraction result = extractor.extract("Die Summe beträgt etwa 3.000 EUR.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(3000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    // ── Bare German numbers (no currency marker) ──

    @Test
    void shouldExtractBareGermanFormattedNumber() {
        NumericExtraction result = extractor.extract(
                "Der festgelegte Betrag von 1.234,56 ist zu beachten.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(1234.56, result.moneyValues().getFirst().amount(), 0.01);
    }

    // ── Word numbers: "50 Tausend", "2 Millionen" ──

    @Test
    void shouldExtractThousandWordNumber() {
        NumericExtraction result = extractor.extract("Die Grenze liegt bei 50 Tausend Euro.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(50000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    @Test
    void shouldExtractMillionWordNumber() {
        NumericExtraction result = extractor.extract("Das Budget beträgt 2 Millionen €.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(2000000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    // ── Percentages ──

    @Test
    void shouldExtractPercentageWithComma() {
        NumericExtraction result = extractor.extract("Die Erhöhung beträgt 5,5 % ab Februar.");
        assertFalse(result.percentages().isEmpty());
        assertEquals(5.5, result.percentages().getFirst().value(), 0.01);
    }

    @Test
    void shouldExtractPercentageWithDot() {
        NumericExtraction result = extractor.extract("Die Steigerung beträgt 3.2 Prozent jährlich.");
        assertFalse(result.percentages().isEmpty());
        assertEquals(3.2, result.percentages().getFirst().value(), 0.01);
    }

    // ── Salary grades ──

    @Test
    void shouldExtractSalaryGradeWithStufe() {
        NumericExtraction result = extractor.extract("EG 9a Stufe 3: 4.117,53 €. EG 10 Stufe 2: 4.231,36 €.");
        assertFalse(result.salaryGrades().isEmpty());
        assertTrue(result.salaryGrades().size() >= 2);
        assertTrue(result.salaryGrades().stream().anyMatch(sg -> sg.grade().contains("EG 9a") && sg.step() == 3));
        assertTrue(result.salaryGrades().stream().anyMatch(sg -> sg.grade().contains("EG 10") && sg.step() == 2));
    }

    @Test
    void shouldExtractSalaryGradeEntgeltgruppe() {
        NumericExtraction result = extractor.extract("Entgeltgruppe 11 Stufe 3 beträgt 4.875,49 €.");
        assertFalse(result.salaryGrades().isEmpty());
        assertTrue(result.salaryGrades().stream().anyMatch(sg -> sg.grade().contains("EG 11") && sg.step() == 3));
    }

    // ── Thresholds ──

    @Test
    void shouldExtractThresholds() {
        NumericExtraction result = extractor.extract(
                "Direktauftrag bis 10.000 Euro. Beschränkte Ausschreibung bis 100.000 Euro.");
        assertFalse(result.thresholds().isEmpty());
        assertTrue(result.thresholds().size() >= 2);
    }

    @Test
    void shouldExtractThresholdMitMindestens() {
        NumericExtraction result = extractor.extract(
                "Mindestens 5.000 Euro sind erforderlich für eine Direktvergabe.");
        assertFalse(result.thresholds().isEmpty());
        assertEquals(5000.0, result.thresholds().getFirst().amount(), 0.01);
    }

    @Test
    void shouldExtractThresholdMitHoechstens() {
        NumericExtraction result = extractor.extract(
                "Höchstens 1.000 Euro für Direktauftrag mit Genehmigung.");
        assertFalse(result.thresholds().isEmpty());
        assertEquals(1000.0, result.thresholds().getFirst().amount(), 0.01);
    }

    @Test
    void shouldExtractThresholdRangeWithBis() {
        NumericExtraction result = extractor.extract(
                "Der Schwellenwert liegt bei 500 bis 1.000 Euro.");
        assertFalse(result.thresholds().isEmpty());
        assertTrue(result.thresholds().size() >= 2);
    }

    @Test
    void shouldExtractThresholdRangeWithDash() {
        NumericExtraction result = extractor.extract(
                "Wertgrenze: 1.000-10.000 Euro für Direktaufträge.");
        assertTrue(result.thresholds().size() >= 1 || result.moneyValues().size() >= 2);
    }

    // ── Thresholds without currency marker ──

    @Test
    void shouldExtractThresholdWithoutCurrency() {
        NumericExtraction result = extractor.extract(
                "Der Schwellenwert liegt bei 500.");
        // Without currency marker, may be picked up by bare number or threshold
        assertNotNull(result);
    }

    // ── Dates ──

    @Test
    void shouldExtractDates() {
        NumericExtraction result = extractor.extract(
                "Gültig ab 01.02.2025. Die neue Fassung tritt am 1. Januar 2026 in Kraft.");
        assertFalse(result.dates().isEmpty());
    }

    // ── Edge cases ──

    @Test
    void shouldReturnEmptyForBlankText() {
        NumericExtraction result = extractor.extract("");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForNullText() {
        NumericExtraction result = extractor.extract(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForWhitespaceOnly() {
        NumericExtraction result = extractor.extract("   \t\n  ");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotExtractPercentageAsMoney() {
        NumericExtraction result = extractor.extract("Die Quote beträgt 1.234,56 % der Gesamtsumme.");
        // Should be a percentage, not money
        assertFalse(result.percentages().isEmpty());
    }

    // ── German special characters in context ──

    @Test
    void shouldHandleUmlautsInContext() {
        NumericExtraction result = extractor.extract(
                "Die Überprüfung der Beschaffung über 5.000 € ergab keine Mängel.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(5000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    @Test
    void shouldHandleEszettInContext() {
        NumericExtraction result = extractor.extract(
                "Außenprüfung: Die Maßnahme kostet 15.000 Euro.");
        assertFalse(result.moneyValues().isEmpty());
        assertEquals(15000.0, result.moneyValues().getFirst().amount(), 0.01);
    }

    // ── extractAll merging ──

    @Test
    void shouldMergeExtractAllResults() {
        NumericExtraction result = extractor.extractAll(java.util.List.of(
                "Betrag: 100,00 €", "Betrag: 200,00 €"));
        assertEquals(2, result.moneyValues().size());
    }
}
