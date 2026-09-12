package reasoning.ai.benchmark;

import reasoning.ai.model.DecisionStrategy;

import java.util.ArrayList;
import java.util.List;

import static reasoning.ai.model.DecisionStrategy.HYBRID_RETRIEVAL;
import static reasoning.ai.model.DecisionStrategy.RULE_ENGINE;

/**
 * Curated benchmark dataset with semantic concept validation.
 * Each question specifies required concepts (must appear) and forbidden concepts
 * (must NOT appear) to prevent false positives from generic or off-topic answers.
 */
public final class BenchmarkDataset {

    private BenchmarkDataset() {}

    public static List<BenchmarkQuestion> all() {
        List<BenchmarkQuestion> q = new ArrayList<>();
        q.addAll(procurement());
        q.addAll(travel());
        q.addAll(salary());
        q.addAll(building());
        q.addAll(mixedRetrieval());
        return List.copyOf(q);
    }

    // ═══════════════════════════════════════════════════
    // PROCUREMENT — 12 questions
    // ═══════════════════════════════════════════════════

    private static List<BenchmarkQuestion> procurement() {
        return List.of(
            q("PROC-001", "Procurement",
                "Kann ich einen IT-Auftrag über 8.000 Euro freihändig vergeben?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Direktauftrag", "Vergabevermerk"),
                List.of("Ausschreibung", "EU-weit"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Direktauftrag", "AV", "LHO", "8.000", "Vergabevermerk"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB", "BauO", "Gehalt")),

            q("PROC-002", "Procurement",
                "Kann ich einen IT-Auftrag über 18.000 Euro freihändig vergeben?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Beschränkte Ausschreibung"),
                List.of("Direktauftrag"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Beschränkte Ausschreibung", "AV", "LHO", "18.000"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB", "Gehalt")),

            q("PROC-003", "Procurement",
                "IT-Dienstleistung über 35.000 Euro — welche Vergabeart?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Beschränkte Ausschreibung", "Ex-post"),
                List.of("Direktauftrag"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Beschränkte Ausschreibung", "AV", "LHO", "35.000"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt")),

            q("PROC-004", "Procurement",
                "Bauauftrag über 90.000 Euro — Direktauftrag möglich?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Beschränkte Ausschreibung"),
                List.of("Direktauftrag"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Beschränkte Ausschreibung", "AV", "LHO", "90.000", "Bau"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt")),

            q("PROC-005", "Procurement",
                "Beschaffung von Büromaterial über 4.000 Euro — was ist zu beachten?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Direktauftrag", "Vergabevermerk", "Angebote"),
                List.of("Ausschreibung", "Genehmigung"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Direktauftrag", "AV", "LHO", "4.000", "Vergabevermerk"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB", "Gehalt")),

            q("PROC-006", "Procurement",
                "Softwarelizenz für 800 Euro beschaffen — welche Vergabeart?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Direktauftrag", "Genehmigung"),
                List.of("Beschränkte Ausschreibung"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Direktauftrag", "AV", "LHO", "800", "Genehmigung"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt")),

            q("PROC-007", "Procurement",
                "Cloud-Abo für 250 Euro — brauche ich eine Ausschreibung?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Kein formelles", "Keine Genehmigung"),
                List.of("Ausschreibung", "Direktauftrag"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("AV", "LHO", "250"),
                List.of("BRKG", "TV-L", "Tagegeld", "Ausschreibung", "Gehalt")),

            q("PROC-008", "Procurement",
                "Ist eine Beschaffung über 800 Euro ohne vorherige Genehmigung zulässig?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Direktauftrag", "Genehmigung"),
                List.of("Kein formelles Verfahren"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Direktauftrag", "AV", "LHO", "800", "Genehmigung"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt")),

            q("PROC-009", "Procurement",
                "Welche Wertgrenzen gelten in Berlin für Direktaufträge nach AV §55 LHO?",
                HYBRID_RETRIEVAL, 0.60, 0.80,
                List.of("Wertgrenzen", "Direktauftrag", "AV", "LHO"),
                List.of(),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Wertgrenze", "Berlin", "LHO", "Direktauftrag"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB", "Gehalt")),

            q("PROC-010", "Procurement",
                "Auftrag über 150.000 Euro für IT-Dienstleistungen — welches Verfahren?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Öffentliche Ausschreibung", "EU-weit", "EU-Schwellenwerte"),
                List.of("Direktauftrag", "Beschränkte"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Öffentliche Ausschreibung", "AV", "LHO", "150.000", "EU"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt")),

            q("PROC-011", "Procurement",
                "Vergabe von Bauleistungen über 15.000 Euro nach VOB/A",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Direktauftrag"),
                List.of("Ausschreibung"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Direktauftrag", "AV", "LHO", "15.000", "Bau"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt")),

            q("PROC-012", "Procurement",
                "Hardware-Beschaffung 1.200 Euro — was muss ich beachten?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Direktauftrag", "Vergabevermerk", "Angebote"),
                List.of("Ausschreibung"),
                "AV zu Paragraph 55 LHO Berlin", true, false,
                List.of("Direktauftrag", "AV", "LHO", "1.200", "Vergabevermerk"),
                List.of("BRKG", "TV-L", "Tagegeld", "Gehalt"))
        );
    }

    // ═══════════════════════════════════════════════════
    // TRAVEL — 10 questions
    // ═══════════════════════════════════════════════════

    private static List<BenchmarkQuestion> travel() {
        return List.of(
            q("TRAV-001", "Travel",
                "Wie hoch ist das Tagegeld bei einer 12-stündigen Dienstreise im Inland?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Tagegeld", "BRKG"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("Tagegeld", "BRKG", "12", "Dienstreise"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "BauGB", "Ausschreibung")),

            q("TRAV-002", "Travel",
                "Welche Verpflegungspauschale gilt bei einer 24-stündigen Dienstreise?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Tagegeld", "BRKG", "24"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("Verpflegungspauschale", "BRKG", "24"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-003", "Travel",
                "Wie hoch ist die Kilometerpauschale bei einer 8-stündigen Dienstreise nach BRKG?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("BRKG", "Tagegeld"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Dienstreise", "8"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-004", "Travel",
                "12-stündige Dienstreise mit Übernachtung — welche Pauschalen gelten?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("BRKG", "Übernachtung"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Übernachtung", "12"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-005", "Travel",
                "Tagegeld Dienstreise 8 Stunden Inland",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Tagegeld", "BRKG"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("Tagegeld", "BRKG", "8", "Dienstreise"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-006", "Travel",
                "24-stündige Dienstreise nach Brüssel — welcher Verpflegungssatz gilt?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Tagegeld", "BRKG"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Brüssel", "24"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-007", "Travel",
                "Übernachtungspauschale Inland ohne Beleg bei 24-stündiger Dienstreise — wie hoch?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Übernachtung", "BRKG", "pauschal"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Übernachtung", "24", "pauschal"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-008", "Travel",
                "An- und Abreisetag bei 14-stündiger Dienstreise mit Übernachtung — Tagegeld?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("Tagegeld", "BRKG", "An- und Abreisetag"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Abreisetag", "14", "Dienstreise"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-009", "Travel",
                "Kilometerpauschale für sonstiges KFZ bei 8-stündiger Dienstreise nach BRKG?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("BRKG", "Tagegeld"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Kilometerpauschale", "8"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung")),

            q("TRAV-010", "Travel",
                "Reisekostensätze bei einer 12-stündigen Dienstreise im Inland?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("BRKG", "Tagegeld"),
                List.of(),
                "Bundesreisekostengesetz (BRKG)", true, false,
                List.of("BRKG", "Reisekosten", "12", "Inland"),
                List.of("AV", "LHO", "TV-L", "Gehalt", "Ausschreibung"))
        );
    }

    // ═══════════════════════════════════════════════════
    // SALARY — 8 questions
    // ═══════════════════════════════════════════════════

    private static List<BenchmarkQuestion> salary() {
        return List.of(
            q("SAL-001", "Salary",
                "Wie hoch ist das Gehalt in EG 9 Stufe 3 nach TV-L 2025?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Entgelttabelle", "Entgelt"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 9", "Stufe 3", "2025"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-002", "Salary",
                "Welches Entgelt erhalte ich in EG 11 Stufe 3 TV-L?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Entgelttabelle", "Entgelt"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 11", "Stufe 3"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-003", "Salary",
                "EG 13 Stufe 3 TV-L — wie hoch ist das monatliche Brutto?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Entgelttabelle", "Entgelt"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 13", "Stufe 3", "Brutto"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-004", "Salary",
                "Vergütung EG 9a Stufe 1 TV-L 2025",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Entgelttabelle", "Entgelt"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 9a", "Stufe 1", "2025"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-005", "Salary",
                "Wie hoch ist die Gehaltserhöhung ab Februar 2025 für EG 9 Stufe 3?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Erhöhung", "Gehaltserhöhung"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 9", "Stufe 3", "2025", "Erhöhung"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-006", "Salary",
                "EG 10 Stufe 2 TV-L — Monatsbetrag?",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Entgelttabelle", "Entgelt"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 10", "Stufe 2"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-007", "Salary",
                "EG 8 Stufe 3 TV-L Gehalt 2025",
                RULE_ENGINE, 0.95, 0.99,
                List.of("TV-L", "Entgelttabelle", "Entgelt"),
                List.of(),
                "TV-L Entgelttabellen 2025", true, false,
                List.of("TV-L", "EG 8", "Stufe 3", "2025"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB")),

            q("SAL-008", "Salary",
                "Welche Entgeltgruppe habe ich als Verwaltungsfachwirt im TV-L?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("TV-L", "Verwaltungsfachwirt"),
                List.of(),
                null, false, true,
                List.of("TV-L", "Verwaltungsfachwirt", "Entgeltgruppe"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB"))
        );
    }

    // ═══════════════════════════════════════════════════
    // BUILDING — 6 questions
    // ═══════════════════════════════════════════════════

    private static List<BenchmarkQuestion> building() {
        return List.of(
            q("BUILD-001", "Building",
                "Welches Baugenehmigungsverfahren gilt für ein Einfamilienhaus in Berlin?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Baugenehmigung"),
                List.of(),
                null, false, true,
                List.of("Baugenehmigung", "Einfamilienhaus", "Berlin"),
                List.of("AV", "LHO", "BRKG", "TV-L", "Tagegeld")),

            q("BUILD-002", "Building",
                "Welche Abstandsflächen sind nach BauO Bln Paragraph 6 einzuhalten?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Abstandsflächen", "BauO"),
                List.of(),
                null, false, true,
                List.of("Abstandsfläche", "BauO", "Paragraph 6"),
                List.of("AV", "LHO", "BRKG", "TV-L", "Tagegeld")),

            q("BUILD-003", "Building",
                "Ist ein Carport genehmigungsfrei in Berlin?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Carport", "genehmigungsfrei"),
                List.of(),
                null, false, true,
                List.of("Carport", "genehmigungsfrei", "Berlin"),
                List.of("AV", "LHO", "BRKG", "TV-L", "Tagegeld")),

            q("BUILD-004", "Building",
                "Welche Bauvorlagen muss ich für einen Bauantrag einreichen?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Bauvorlagen", "Bauantrag"),
                List.of(),
                null, false, true,
                List.of("Bauvorlage", "Bauantrag", "einreichen"),
                List.of("AV", "LHO", "BRKG", "TV-L", "Tagegeld")),

            q("BUILD-005", "Building",
                "Wann ist eine Nutzungsänderung genehmigungspflichtig nach BauO Bln?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Nutzungsänderung", "BauO"),
                List.of(),
                null, false, true,
                List.of("Nutzungsänderung", "BauO", "genehmigungspflichtig"),
                List.of("AV", "LHO", "BRKG", "TV-L", "Tagegeld")),

            q("BUILD-006", "Building",
                "Welche Brandschutzanforderungen gelten für ein Wohngebäude mittlerer Höhe?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Brandschutz", "Wohngebäude"),
                List.of(),
                null, false, true,
                List.of("Brandschutz", "Wohngebäude", "Höhe"),
                List.of("AV", "LHO", "BRKG", "TV-L", "Tagegeld"))
        );
    }

    // ═══════════════════════════════════════════════════
    // MIXED RETRIEVAL — 4 questions
    // ═══════════════════════════════════════════════════

    private static List<BenchmarkQuestion> mixedRetrieval() {
        return List.of(
            q("RETR-001", "Retrieval",
                "Welche umweltbezogenen Kriterien muss ich nach BerlAVG bei einer Vergabe berücksichtigen?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("umwelt", "BerlAVG"),
                List.of(),
                null, false, true,
                List.of("BerlAVG", "umwelt", "Vergabe"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB")),

            q("RETR-002", "Retrieval",
                "Wie dokumentiere ich einen Vergabevermerk ordnungsgemäß?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Vergabevermerk", "Dokumentation"),
                List.of(),
                null, false, true,
                List.of("Vergabevermerk", "dokumentieren", "ordnungsgemäß"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB", "Gehalt")),

            q("RETR-003", "Retrieval",
                "Welche Fristen gelten für ein offenes Verfahren nach VgV?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Fristen", "VgV"),
                List.of(),
                null, false, true,
                List.of("VgV", "Frist", "offenes Verfahren"),
                List.of("BRKG", "TV-L", "Tagegeld", "BauGB", "Gehalt")),

            q("RETR-004", "Retrieval",
                "Kann ich meinen Resturlaub ins nächste Jahr übertragen nach TV-L?",
                HYBRID_RETRIEVAL, 0.15, 0.65,
                List.of("Resturlaub", "TV-L", "übertragen"),
                List.of(),
                null, false, true,
                List.of("TV-L", "Resturlaub", "übertragen", "Jahr"),
                List.of("AV", "LHO", "BRKG", "Ausschreibung", "BauGB"))
        );
    }

    // ── helpers ──

    private static BenchmarkQuestion q(String id, String category, String question,
            DecisionStrategy strategy, double minConf, double maxConf,
            List<String> keywords, List<String> forbidden,
            String regulation, boolean grounded, boolean retrieval,
            List<String> mustContain, List<String> mustNotContain) {
        return new BenchmarkQuestion(id, category, question, strategy,
                minConf, maxConf, keywords, forbidden, regulation, grounded, retrieval,
                mustContain, mustNotContain);
    }
}
