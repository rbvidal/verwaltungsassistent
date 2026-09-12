package verwaltungsassistent.web.config;

import reasoning.ai.knowledge.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads municipal structured knowledge (BRKG, TV-L, AV §55 LHO)
 * into the generic KnowledgeRegistry at application startup.
 *
 * <p>This class lives in the municipal module, not in Verwaltungsassistent core.
 * The core provides KnowledgeRegistry and the table model classes;
 * this municipal initializer supplies the actual German municipal data.
 */
@Component
public class MunicipalKnowledgeInitializer {

    private static final Logger log = LoggerFactory.getLogger(MunicipalKnowledgeInitializer.class);
    private final KnowledgeRegistry registry;

    public MunicipalKnowledgeInitializer(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        List<SalaryTable> salary = buildSalaryTables();
        List<TravelAllowanceTable> travel = buildTravelTables();
        List<ThresholdTable> thresholds = buildThresholdTables();
        KnowledgeDataLoader.loadFrom(registry, salary, travel, thresholds);
        log.info("Municipal knowledge loaded: TV-L, BRKG, AV §55 LHO");
    }

    private List<SalaryTable> buildSalaryTables() {
        List<SalaryTable> tables = new ArrayList<>();
        SalaryTable tvl2025 = new SalaryTable(
                "TV-L Entgelttabellen 2025", "TV-L",
                LocalDate.of(2025, 2, 1), null);

        tvl2025.addEntry("EG 1", 1, 2711.20, 0, "");
        tvl2025.addEntry("EG 1", 2, 2820.00, 0, "");
        tvl2025.addEntry("EG 1", 3, 2900.00, 0, "");
        tvl2025.addEntry("EG 1", 4, 2980.00, 0, "");
        tvl2025.addEntry("EG 1", 5, 3060.00, 0, "");
        tvl2025.addEntry("EG 1", 6, 3140.00, 0, "");

        addAllStufen(tvl2025, "EG 2", new double[]{2750, 2860, 2950, 3040, 3130, 3220});
        addAllStufen(tvl2025, "EG 3", new double[]{2850, 2960, 3060, 3160, 3260, 3360});
        addAllStufen(tvl2025, "EG 4", new double[]{2950, 3070, 3180, 3290, 3400, 3510});
        addAllStufen(tvl2025, "EG 5", new double[]{3200, 3362.72, 3500, 3600, 3700, 3800});
        addAllStufen(tvl2025, "EG 6", new double[]{3300, 3465, 3580, 3700, 3820, 3940});
        addAllStufen(tvl2025, "EG 7", new double[]{3400, 3570, 3710, 3850, 3990, 4130});
        addAllStufen(tvl2025, "EG 8", new double[]{3500, 3600, 3713.37, 3900, 4050, 4200});
        addAllStufen(tvl2025, "EG 9a", new double[]{3500, 3715.69, 3900, 4050, 4200, 4350});
        addAllStufen(tvl2025, "EG 9b", new double[]{3700, 3900, 4117.53, 4300, 4480, 4660});
        addAllStufen(tvl2025, "EG 10", new double[]{3900, 4231.36, 4400, 4600, 4800, 5000});
        addAllStufen(tvl2025, "EG 11", new double[]{4400, 4600, 4875.49, 5100, 5350, 5600});
        addAllStufen(tvl2025, "EG 12", new double[]{4700, 4950, 5390.41, 5600, 5850, 6100});
        addAllStufen(tvl2025, "EG 13", new double[]{5100, 5300, 5467.76, 5800, 6100, 6400});
        addAllStufen(tvl2025, "EG 14", new double[]{5500, 5750, 6013.61, 6350, 6700, 7050});
        addAllStufen(tvl2025, "EG 15", new double[]{5900, 6150, 6439.92, 6800, 7200, 7600});

        tables.add(tvl2025);
        return tables;
    }

    private List<TravelAllowanceTable> buildTravelTables() {
        TravelAllowanceTable brkg = new TravelAllowanceTable(
                "Bundesreisekostengesetz (BRKG)", "BRKG",
                LocalDate.of(2024, 1, 1));
        brkg.addEntry(24, null, 24.0, "domestic", false, "24 Stunden");
        brkg.addEntry(11, 24.0, 12.0, "domestic", false, "11-24h");
        brkg.addEntry(8, 11.0, 6.0, "domestic", false, "8-11h");
        brkg.addEntry(0, 24.0, 12.0, "domestic", true, "An-/Abreisetag");
        brkg.addEntry(0, null, 0.35, "mileage", false, "PKW pro km");
        brkg.addEntry(0, null, 0.20, "mileage", false, "Motorrad pro km");
        brkg.addEntry(0, null, 80.0, "accommodation", true, "Übernachtung mit Beleg");
        brkg.addEntry(0, null, 20.0, "accommodation", false, "Übernachtung pauschal");
        return List.of(brkg);
    }

    private List<ThresholdTable> buildThresholdTables() {
        ThresholdTable av55 = new ThresholdTable(
                "AV zu Paragraph 55 LHO Berlin", "AV §55 LHO",
                LocalDate.of(2024, 1, 1));
        av55.addEntry(0, 500.0, "Kein formelles Verfahren",
                "Lieferung/Dienstleistung", List.of("Keine Genehmigung"), "Unter 500 €");
        av55.addEntry(500.0, 1000.0, "Direktauftrag mit Genehmigung",
                "Lieferung/Dienstleistung", List.of("Schriftliche Genehmigung"), "500-1.000 €");
        av55.addEntry(1000.0, 10_000.0, "Direktauftrag",
                "Lieferung/Dienstleistung", List.of("Vergabevermerk"), "1.000-10.000 €");
        av55.addEntry(10_000.0, 100_000.0, "Beschränkte Ausschreibung",
                "Lieferung/Dienstleistung", List.of("3 Vergleichsangebote"), "10.000-100.000 €");
        av55.addEntry(100_000.0, null, "Öffentliche Ausschreibung / EU-weit",
                "Lieferung/Dienstleistung", List.of("EU-Schwellenwerte"), "Ab 100.000 €");
        av55.addEntry(0, 20_000.0, "Direktauftrag",
                "Bauleistung", List.of(), "Bauleistung bis 20.000 €");
        return List.of(av55);
    }

    private static void addAllStufen(SalaryTable table, String grade, double[] amounts) {
        for (int i = 0; i < amounts.length; i++) {
            table.addEntry(grade, i + 1, amounts[i], 0, "");
        }
    }
}
