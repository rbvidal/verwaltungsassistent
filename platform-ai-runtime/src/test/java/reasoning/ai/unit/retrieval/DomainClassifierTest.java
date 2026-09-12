package reasoning.ai.unit.retrieval;

import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.DomainClassifier.DomainResult;
import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.DomainKnowledge.TermWeight;
import reasoning.ai.model.Domain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for domain classification. Procurement must never
 * return GENERAL. HR must never classify as PROCUREMENT, etc.
 *
 * <p>Uses an explicit minimal DomainKnowledge built inline — no
 * dependency on YAML or the old hardcoded defaults.
 */
class DomainClassifierTest {

    private DomainClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DomainClassifier(testKnowledge());
    }

    /** Minimal domain knowledge with only the terms these tests need. */
    private static DomainKnowledge testKnowledge() {
        Map<Domain, List<TermWeight>> map = new LinkedHashMap<>();

        map.put(Domain.of("PROCUREMENT"), concat(
                tw(10.0, "beschaffung", "vergabeverfahren", "vergabe", "vergabevermerk",
                        "direktauftrag", "direktaufträge", "ausschreibung",
                        "beschränkte ausschreibung", "verhandlungsvergabe"),
                tw(8.0, "vergab", "beschaffen", "rahmenvertrag", "lieferung", "einkauf",
                        "lieferant", "auftragswert", "schwellenwert", "wertgrenzen"),
                tw(5.0, "auftrag", "freihändig", "freihaendig", "angebot", "vertrag",
                        "ausschreiben", "vergabestelle", "beschaffungsordnung",
                        "computer", "laptop", "printer", "monitor",
                        "software", "hardware", "license", "licence",
                        "euro", "€", "amount", "value")
        ));

        map.put(Domain.of("BUILDING"), concat(
                tw(10.0, "baugenehmigung", "baugenehmigungsverfahren", "bauantrag"),
                tw(8.0, "abstandsfläche", "abstandsflächen", "abstandsflaeche",
                        "abstandsflaechen", "bebauungsplan", "baulast",
                        "einfamilienhaus", "carport", "garage"),
                tw(5.0, "bau", "erschließung", "nutzungsänderung", "vorbescheid")
        ));

        map.put(Domain.of("HR"), concat(
                tw(10.0, "tv-l", "entgeltgruppe", "tarifvertrag", "urlvo"),
                tw(8.0, "urlaub", "erholungsurlaub", "sonderurlaub", "arbeitszeit",
                        "homeoffice", "mobiles arbeiten", "mobile arbeit", "teilzeit",
                        "kündigung", "kuendigung", "personalrat"),
                tw(5.0, "gehalt", "entgelt", "vergütung", "verguetung", "eg ", "stufe")
        ));

        map.put(Domain.of("TRAVEL"), concat(
                tw(10.0, "dienstreise", "reisekosten", "tagegeld",
                        "verpflegungspauschale", "übernachtungspauschale",
                        "uebernachtungspauschale", "brkg"),
                tw(8.0, "kilometerpauschale", "reisetag", "abwesenheit",
                        "dienstreiseantrag", "reisekostenabrechnung"),
                tw(5.0, "hotel", "übernachtung", "uebernachtung", "kilometer",
                        "reise", "fahrtkosten", "bahn", "flug")
        ));

        return new DomainKnowledge(map,
                Set.of("bau", "reise", "hotel", "bahn", "flug"),
                0.85, 0.15, 0.20);
    }

    @SafeVarargs
    private static List<TermWeight> concat(List<TermWeight>... lists) {
        List<TermWeight> result = new ArrayList<>();
        for (List<TermWeight> l : lists) result.addAll(l);
        return result;
    }

    private static List<TermWeight> tw(double weight, String... phrases) {
        List<TermWeight> list = new ArrayList<>();
        for (String p : phrases) list.add(new TermWeight(p, weight));
        return list;
    }

    // ── Procurement ──

    @Test
    void shouldClassifyProcurementQuestion() {
        DomainResult r = classifier.classify(
                "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?");
        assertEquals(Domain.of("PROCUREMENT"), r.primary());
        assertTrue(r.isStrong(), "Procurement should be strong");
        assertTrue(r.primaryConfidence() > 0.5);
    }

    @Test
    void shouldClassifyBeschaffungAsProcurement() {
        DomainResult r = classifier.classify(
                "Ist eine Beschaffung über 800 Euro ohne vorherige Genehmigung zulässig?");
        assertEquals(Domain.of("PROCUREMENT"), r.primary());
    }

    @Test
    void shouldClassifyVergabeAsProcurement() {
        DomainResult r = classifier.classify("Welche Wertgrenzen gelten für Direktaufträge?");
        assertEquals(Domain.of("PROCUREMENT"), r.primary());
    }

    @Test
    void shouldClassifyAusschreibungAsProcurement() {
        DomainResult r = classifier.classify("Wie schreibe ich eine Lieferung öffentlich aus?");
        assertEquals(Domain.of("PROCUREMENT"), r.primary());
    }

    @Test
    void procurementMustNotBeGeneral() {
        // All of these must be PROCUREMENT, not GENERAL
        String[] queries = {
            "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?",
            "Ist eine Beschaffung über 800 Euro ohne vorherige Genehmigung zulässig?",
            "Welche Wertgrenzen gelten für Direktaufträge?",
            "Wie dokumentiere ich einen Vergabevermerk?",
            "Ab welchem Auftragswert muss eine EU-weite Ausschreibung erfolgen?"
        };
        for (String q : queries) {
            DomainResult r = classifier.classify(q);
            assertNotEquals(Domain.GENERAL, r.primary(),
                    "Query '" + q + "' must not be GENERAL");
        }
    }

    // ── HR ──

    @Test
    void shouldClassifySalaryAsHR() {
        DomainResult r = classifier.classify(
                "Wie hoch ist EG 9 Stufe 3 ab Februar 2025?");
        assertEquals(Domain.of("HR"), r.primary());
    }

    @Test
    void shouldClassifyTVLAsHR() {
        DomainResult r = classifier.classify(
                "Wie hoch ist die Gehaltserhöhung ab Februar 2025 für EG 9 Stufe 3?");
        assertEquals(Domain.of("HR"), r.primary());
    }

    @Test
    void shouldClassifyUrlaubAsHR() {
        DomainResult r = classifier.classify(
                "Kann ich meinen Resturlaub ins nächste Jahr übertragen?");
        assertEquals(Domain.of("HR"), r.primary());
    }

    // ── Travel ──

    @Test
    void shouldClassifyTravelExpenseAsTravel() {
        DomainResult r = classifier.classify(
                "Wie hoch ist die Verpflegungspauschale bei einer 12-stündigen Dienstreise?");
        assertEquals(Domain.of("TRAVEL"), r.primary());
    }

    @Test
    void shouldClassifyReisekostenAsTravel() {
        DomainResult r = classifier.classify(
                "Wie hoch ist das Tagegeld bei einer Dienstreise nach Brüssel?");
        assertEquals(Domain.of("TRAVEL"), r.primary());
    }

    // ── Building ──

    @Test
    void shouldClassifyBuildingPermitAsBuilding() {
        DomainResult r = classifier.classify(
                "Welches Baugenehmigungsverfahren gilt für ein Einfamilienhaus?");
        assertEquals(Domain.of("BUILDING"), r.primary());
    }

    @Test
    void shouldClassifyAbstandsflaecheAsBuilding() {
        DomainResult r = classifier.classify(
                "Welche Abstandsflächen gelten in Berlin?");
        assertEquals(Domain.of("BUILDING"), r.primary());
    }

    // ── Edge cases ──

    @Test
    void genericQuestionShouldBeGeneral() {
        DomainResult r = classifier.classify("Hallo");
        assertEquals(Domain.GENERAL, r.primary());
        assertFalse(r.isStrong());
    }

    @Test
    void shouldProvideSecondaryDomain() {
        DomainResult r = classifier.classify(
                "Dienstreise nach Berlin — wie buche ich ein Hotel und beantrage Urlaub?");
        assertEquals(Domain.of("TRAVEL"), r.primary());
        assertTrue(r.secondary() == Domain.of("HR") || r.secondary() == null);
    }

    // ── Architectural: no Java hardcoded knowledge ──

    @Test
    void shouldRecognizeNewTermWithoutJavaChange() {
        // Adding a domain term requires only configuration, not Java code changes.
        // Build a fresh DomainKnowledge with ONE novel term and verify classification.
        Map<Domain, List<TermWeight>> map = new LinkedHashMap<>();
        map.put(Domain.of("HR"), List.of(new TermWeight("neues-hr-stichwort-2026", 10.0)));
        map.put(Domain.of("PROCUREMENT"), List.of());
        map.put(Domain.of("BUILDING"), List.of());
        map.put(Domain.of("TRAVEL"), List.of());
        DomainKnowledge dk = new DomainKnowledge(map, Set.of(), 0.85, 0.15, 0.20);
        DomainClassifier fresh = new DomainClassifier(dk);

        DomainResult r = fresh.classify("Das neues-hr-stichwort-2026 gilt für alle Beschäftigten");
        assertEquals(Domain.of("HR"), r.primary(),
                "New term added via config only must be recognized without Java changes");
    }

    @Test
    void classifierDoesNotDependOnOldHardcodedDefaults() {
        // Prove that DomainKnowledge.skeletal() — with zero terms — does not
        // retain any domain classification capability from the old defaults().
        DomainClassifier skeletal = new DomainClassifier(DomainKnowledge.skeletal());

        DomainResult r = skeletal.classify("Beschaffung von Computern über 10.000 Euro");
        assertEquals(Domain.GENERAL, r.primary(),
                "Without configured terms, classifier must return GENERAL");
    }
}
