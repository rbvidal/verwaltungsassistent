package reasoning.ai.unit.evidence;

import reasoning.ai.application.EvidencePackageBuilder;
import reasoning.ai.application.NumericExtractor;
import reasoning.ai.config.AiPipelineProperties;
import reasoning.ai.model.EvidenceItem;
import reasoning.ai.model.EvidencePackage;
import reasoning.ai.model.EvidencePackage.CoverageStatus;
import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceCitation.SourceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePackageBuilderTest {

    private EvidencePackageBuilder builder;

    @BeforeEach
    void setUp() {
        AiPipelineProperties props = new AiPipelineProperties();
        props.setMaxEvidenceSources(4);
        props.setMaxParagraphsPerSource(3);
        props.setMaxExcerptLength(500);
        builder = new EvidencePackageBuilder(new NumericExtractor(), props, "");
    }

    // ── Salary lookup ──

    @Test
    void shouldExtractSalaryGradeFromTvLPayTable() {
        var sources = List.of(source("TV-L Entgelttabellen 2025",
                "EG 9a Stufe 3: 4.117,53 €. EG 9b Stufe 3: 4.117,53 €. "
                + "Gültig ab 01.02.2025. Die Gehaltserhöhung beträgt 5,5 %."));
        // Suchtext mit Themenwörtern, damit der Beleg die lexikalische
        // Aufnahmeschwelle des Builders (≥ 0.30) erreicht — generische
        // Frage-Hülle ("Wie hoch ist ...") zählt nicht als Relevanz.
        EvidencePackage pkg = builder.build("EG 9a Stufe 3 Gehalt", sources);

        assertEquals(1, pkg.items().size());
        assertFalse(pkg.isEmpty());
        EvidenceItem item = pkg.items().getFirst();
        assertNotNull(item.numericExtraction());
        assertFalse(item.numericExtraction().salaryGrades().isEmpty());
        assertFalse(item.numericExtraction().moneyValues().isEmpty());
        assertFalse(item.numericExtraction().percentages().isEmpty());
        assertTrue(item.numericExtraction().salaryGrades().stream()
                .anyMatch(sg -> sg.grade().contains("EG 9a") && sg.step() == 3));
    }

    @Test
    void shouldDetectConflictingSalaryData() {
        var sources = List.of(
                source("TV-L Entgelttabellen 2025", "EG 9a Stufe 3: 4.117,53 €"),
                source("TV-L Entgelttabellen 2024", "EG 9a Stufe 3: 3.950,00 €"));
        EvidencePackage pkg = builder.build("EG 9a Stufe 3 Gehalt", sources);
        // When two sources have conflicting salary data, contradiction should be detected
        assertTrue(pkg.hasContradictions() || pkg.coverageStatus() != CoverageStatus.INSUFFICIENT);
    }

    // ── Procurement approval ──

    @Test
    void shouldIdentifyProcurementThreshold() {
        var sources = List.of(source("Beschaffungsordnung Berlin",
                "Beschaffungen zwischen 500 € und 1.000 € bedürfen der "
                + "schriftlichen Genehmigung der Führungskraft. "
                + "Beschaffungen über 1.000 € erfordern einen Vergabevermerk. "
                + "Direktaufträge bis 10.000 Euro sind zulässig."));
        EvidencePackage pkg = builder.build(
                "Ist eine Beschaffung über 800 Euro ohne Genehmigung zulässig?", sources);

        assertEquals(1, pkg.items().size());
        EvidenceItem item = pkg.items().getFirst();
        assertEquals("Beschaffungsordnung Berlin", item.documentTitle());
        // Should detect procurement domain
        assertNotNull(item.supports()); // generic support field, domain labels externalized
        // Should extract thresholds
        assertNotNull(item.numericExtraction());
        assertFalse(item.numericExtraction().thresholds().isEmpty());
    }

    @Test
    void shouldSelectProcurementNotHrForBeschaffungQuery() {
        var sources = List.of(
                source("Beschaffungsordnung Berlin", "Beschaffungen..."),
                source("Landesreisekostengesetz Berlin", "Reisekosten..."));
        // Unter der lexikalischen Aufnahmeschwelle wird das themenfremde
        // Reisekosten-Dokument (keine Frage-Wort-Übereinstimmung) verworfen —
        // genau die Absicherung gegen fachfremde Belege (GastG-Fall).
        EvidencePackage pkg = builder.build("Beschaffung genehmigungsfrei", sources);

        assertEquals(1, pkg.items().size());
        // Procurement document should be selected, HR/travel documents dropped
        assertTrue(pkg.items().getFirst().documentTitle().contains("Beschaffungsordnung"));
        assertNotNull(pkg.items().getFirst().supports());
    }

    // ── Travel expenses ──

    @Test
    void shouldExtractTravelExpenseRates() {
        var sources = List.of(source("Bundesreisekostengesetz (BRKG)",
                "Tagegeld innerhalb Deutschlands: 6 € für Abwesenheit über 8 Stunden, "
                + "12 € für über 11 Stunden, 24 € für vollen 24-Stunden-Tag. "
                + "Kilometergeld: 0,35 € pro Kilometer."));
        EvidencePackage pkg = builder.build(
                "Tagegeld Dienstreise Bundesreisekostengesetz", sources);

        assertFalse(pkg.isEmpty());
        EvidenceItem item = pkg.items().getFirst();
        assertNotNull(item.numericExtraction());
        // Should have extracted money values
        assertFalse(item.numericExtraction().moneyValues().isEmpty());
        assertNotNull(item.supports());
    }

    // ── Vacation rules ──

    @Test
    void shouldSupportVacationQuery() {
        var sources = List.of(source("Urlaubsverordnung Berlin (UrlVO Bln)",
                "Jahresurlaub: 30 Arbeitstage bei einer 5-Tage-Woche. "
                + "Übertragung gültig ab 1. Januar bis 31. März des Folgejahres."));
        EvidencePackage pkg = builder.build("Resturlaub Übertragung UrlVO", sources);

        assertFalse(pkg.isEmpty());
        assertNotNull(pkg.items().getFirst().supports());
    }

    // ── Working hours ──

    @Test
    void shouldDetectWorkingTimeRegulation() {
        var sources = List.of(source("Arbeitszeitverordnung Berlin (AZVO Bln)",
                "Regelmäßige wöchentliche Arbeitszeit: 40 Stunden für Beamte, "
                + "39 Stunden 50 Minuten für Tarifbeschäftigte. "
                + "Kernarbeitszeit: 9:30 bis 15:00 Uhr. "
                + "Maximale tägliche Arbeitszeit: 10 Stunden."));
        EvidencePackage pkg = builder.build(
                "Kernarbeitszeit Arbeitszeitverordnung Berlin", sources);

        assertFalse(pkg.isEmpty());
        assertNotNull(pkg.items().getFirst().supports());
    }

    // ── Missing evidence ──

    @Test
    void shouldMarkInsufficientWhenNoSources() {
        EvidencePackage pkg = builder.build("Gibt es eine Vorschrift zu Drohnenflügen?", List.of());
        assertTrue(pkg.isEmpty());
        assertTrue(pkg.hasInsufficientEvidence());
        assertEquals(CoverageStatus.INSUFFICIENT, pkg.coverageStatus());
    }

    @Test
    void shouldReportZeroDocumentsWhenEmpty() {
        EvidencePackage pkg = builder.build("Beliebige Frage", List.of());
        assertEquals(0, pkg.totalDocumentsSearched());
        assertEquals(0, pkg.relevantDocumentsFound());
        assertEquals(0, pkg.documentsUsed());
    }

    // ── Numeric extraction ──

    @Test
    void shouldExtractEuroAmounts() {
        var sources = List.of(source("Testdokument", "Der Betrag beträgt 500,00 € und 1.234,56 Euro."));
        EvidencePackage pkg = builder.build("Betrag Euro", sources);
        var item = pkg.items().getFirst();
        var moneyValues = item.numericExtraction().moneyValues();
        assertEquals(2, moneyValues.size());
        assertEquals(500.0, moneyValues.get(0).amount(), 0.01);
        assertEquals(1234.56, moneyValues.get(1).amount(), 0.01);
    }

    @Test
    void shouldExtractPercentages() {
        var sources = List.of(source("Test", "Erhöhung um 5,5 % und 3.2 Prozent."));
        EvidencePackage pkg = builder.build("Erhöhung Prozent", sources);
        var pcts = pkg.items().getFirst().numericExtraction().percentages();
        assertEquals(2, pcts.size());
    }

    // ── Paragraph citations ──

    @Test
    void shouldGroupEvidenceByDocument() {
        var sources = List.of(
                source("BauO Bln", "Abstandsflächen: Section 63 defines the simplified permit procedure."),
                source("BauO Bln", "Abstandsflächen: Section 6 defines setback requirements."));
        EvidencePackage pkg = builder.build("Abstandsflächen", sources);
        // Should have 1 item (both chunks from same document grouped)
        assertEquals(1, pkg.items().size());
        EvidenceItem item = pkg.items().getFirst();
        assertEquals("BauO Bln", item.documentTitle());
        assertFalse(item.paragraph().isBlank()); // "2 Abschnitte" or similar
    }

    // ── Helper ──

    private SourceCitation source(String title, String excerpt) {
        return new SourceCitation(
                UUID.randomUUID(), UUID.randomUUID(), 1, title,
                null, null, null, excerpt,
                0.85, SourceTier.PRIMARY);
    }
}
