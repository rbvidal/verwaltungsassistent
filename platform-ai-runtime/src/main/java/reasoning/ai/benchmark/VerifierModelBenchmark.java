package reasoning.ai.benchmark;

import reasoning.ai.model.ClaimVerification.Verdict;
import java.util.ArrayList;
import java.util.List;

/**
 * Controlled test corpus for claim/evidence verification benchmarking.
 * Domain-neutral — no municipal terminology, BRKG, TV-L, or German authority names.
 *
 * <p>Categories: entailment, contradiction, unknown, numerical,
 * paraphrase, irrelevant topical similarity — each in English and German.
 */
public final class VerifierModelBenchmark {

    private VerifierModelBenchmark() {}

    public record Case(String id, String category, String language,
                       String evidence, String claim, Verdict expected) {}

    public static List<Case> all() {
        List<Case> cases = new ArrayList<>();
        cases.addAll(entailment());
        cases.addAll(contradiction());
        cases.addAll(unknown());
        cases.addAll(numerical());
        cases.addAll(paraphrase());
        cases.addAll(irrelevant());
        cases.addAll(german());
        return List.copyOf(cases);
    }

    // ── A. Entailment ──
    static List<Case> entailment() {
        return List.of(
            new Case("ENT-01", "entailment", "EN",
                "Employees must use VPN when accessing the municipal network remotely.",
                "Employees must use VPN for remote access.",
                Verdict.ENTAILED),
            new Case("ENT-02", "entailment", "EN",
                "All purchase orders above 500 EUR require manager approval.",
                "Purchase orders above 500 EUR need manager approval.",
                Verdict.ENTAILED),
            new Case("ENT-03", "entailment", "EN",
                "The office is open Monday through Friday from 8:00 to 18:00.",
                "The office is open on weekdays from 8:00 to 18:00.",
                Verdict.ENTAILED)
        );
    }

    // ── B. Contradiction ──
    static List<Case> contradiction() {
        return List.of(
            new Case("CON-01", "contradiction", "EN",
                "Employees must use VPN when accessing the municipal network remotely.",
                "Employees do not need VPN for remote access.",
                Verdict.CONTRADICTED),
            new Case("CON-02", "contradiction", "EN",
                "Server rooms require badge access at all times.",
                "Server rooms are accessible without a badge.",
                Verdict.CONTRADICTED),
            new Case("CON-03", "contradiction", "EN",
                "Overtime must be approved in advance by a supervisor.",
                "Overtime can be worked without prior approval.",
                Verdict.CONTRADICTED)
        );
    }

    // ── C. Unknown ──
    static List<Case> unknown() {
        return List.of(
            new Case("UNK-01", "unknown", "EN",
                "Employees may work remotely two days per week.",
                "Employees must use two-factor authentication.",
                Verdict.UNKNOWN),
            new Case("UNK-02", "unknown", "EN",
                "The cafeteria serves lunch between 11:30 and 14:00.",
                "The cafeteria offers vegetarian options.",
                Verdict.UNKNOWN),
            new Case("UNK-03", "unknown", "EN",
                "Annual leave must be requested at least two weeks in advance.",
                "Sick leave requires a doctor's note after three days.",
                Verdict.UNKNOWN)
        );
    }

    // ── D. Numerical contradiction ──
    static List<Case> numerical() {
        return List.of(
            new Case("NUM-01", "numerical_contradiction", "EN",
                "The allowance is 24 euros.",
                "The allowance is 12 euros.",
                Verdict.CONTRADICTED),
            new Case("NUM-02", "numerical_entailment", "EN",
                "The allowance for a full day is 24 euros.",
                "The full-day allowance is 24 euros.",
                Verdict.ENTAILED),
            new Case("NUM-03", "numerical_contradiction", "EN",
                "The maximum reimbursement is 150 euros per night.",
                "The maximum reimbursement is 200 euros per night.",
                Verdict.CONTRADICTED),
            new Case("NUM-04", "numerical_entailment", "EN",
                "The annual training budget per employee is 2,000 euros.",
                "Each employee has a training budget of 2,000 euros per year.",
                Verdict.ENTAILED)
        );
    }

    // ── F. Paraphrase (materially different wording, same meaning) ──
    static List<Case> paraphrase() {
        return List.of(
            new Case("PAR-01", "paraphrase", "EN",
                "Staff members are obligated to secure their workstations before leaving the premises.",
                "Employees must lock their computers when they leave the office.",
                Verdict.ENTAILED),
            new Case("PAR-02", "paraphrase", "EN",
                "The organization provides 30 days of paid annual leave to all full-time personnel.",
                "Full-time workers receive 30 paid vacation days per year.",
                Verdict.ENTAILED)
        );
    }

    // ── G. Irrelevant topical similarity ──
    static List<Case> irrelevant() {
        return List.of(
            new Case("IRR-01", "irrelevant", "EN",
                "The IT department manages all software license renewals.",
                "The IT department provides technical support for all software issues.",
                Verdict.UNKNOWN),
            new Case("IRR-02", "irrelevant", "EN",
                "All expense reports must be submitted within 30 days of travel.",
                "Travel advances are available for trips longer than 5 days.",
                Verdict.UNKNOWN)
        );
    }

    // ── H. German examples (same categories, German language) ──
    static List<Case> german() {
        return List.of(
            // Entailment
            new Case("DE-ENT-01", "entailment", "DE",
                "Mitarbeiter müssen bei Fernzugriff auf das Verwaltungsnetz ein VPN nutzen.",
                "Mitarbeiter müssen für den Fernzugriff ein VPN verwenden.",
                Verdict.ENTAILED),
            new Case("DE-ENT-02", "entailment", "DE",
                "Dienstreiseanträge sind spätestens eine Woche vor Reisebeginn einzureichen.",
                "Dienstreiseanträge müssen eine Woche vor der Reise eingereicht werden.",
                Verdict.ENTAILED),
            // Contradiction
            new Case("DE-CON-01", "contradiction", "DE",
                "Die Nutzung privater Geräte im Verwaltungsnetz ist nicht gestattet.",
                "Private Geräte dürfen im Verwaltungsnetz verwendet werden.",
                Verdict.CONTRADICTED),
            new Case("DE-CON-02", "contradiction", "DE",
                "Überstunden müssen vorab durch die Führungskraft genehmigt werden.",
                "Überstunden können ohne vorherige Genehmigung geleistet werden.",
                Verdict.CONTRADICTED),
            // Unknown
            new Case("DE-UNK-01", "unknown", "DE",
                "Die Kantine bietet Mittagessen zwischen 11:30 und 14:00 Uhr an.",
                "Die Kantine bietet vegetarische Gerichte an.",
                Verdict.UNKNOWN),
            new Case("DE-UNK-02", "unknown", "DE",
                "Urlaubsanträge müssen spätestens zwei Wochen vorher eingereicht werden.",
                "Krankmeldungen sind ab dem dritten Tag mit einem Attest vorzulegen.",
                Verdict.UNKNOWN),
            // Numerical
            new Case("DE-NUM-01", "numerical_contradiction", "DE",
                "Das Tagegeld beträgt 24 Euro.",
                "Das Tagegeld beträgt 12 Euro.",
                Verdict.CONTRADICTED),
            new Case("DE-NUM-02", "numerical_entailment", "DE",
                "Das volle Tagegeld für einen ganzen Tag beträgt 24 Euro.",
                "Das Tagegeld für einen vollen Tag beträgt 24 Euro.",
                Verdict.ENTAILED),
            // Paraphrase
            new Case("DE-PAR-01", "paraphrase", "DE",
                "Bedienstete sind verpflichtet, ihren Arbeitsplatz vor Verlassen des Gebäudes zu sichern.",
                "Mitarbeiter müssen ihren Arbeitsplatz beim Verlassen abschließen.",
                Verdict.ENTAILED),
            // Irrelevant
            new Case("DE-IRR-01", "irrelevant", "DE",
                "Die IT-Abteilung verwaltet alle Softwarelizenzen.",
                "Die IT-Abteilung bietet technischen Support für alle Softwareprobleme.",
                Verdict.UNKNOWN)
        );
    }

    // ── Multi-evidence cases ──

    /**
     * A multi-evidence case: one claim verified against several evidence excerpts.
     * Each evidence item has its own expected verdict.
     */
    public record MultiEvidenceCase(String id, String category, String language,
                                     String claim, List<String> evidenceExcerpts,
                                     List<Verdict> expectedVerdicts) {}

    public static List<MultiEvidenceCase> multiEvidenceCases() {
        return List.of(
            // Case A: one supporting item among neutral items
            new MultiEvidenceCase("MULTI-A", "one_supporting", "EN",
                "Employees must use VPN for remote access.",
                List.of(
                    "Employees must use VPN when accessing the municipal network remotely.",
                    "The IT department issues laptops to all staff members.",
                    "The office cafeteria serves lunch between 11:30 and 14:00."
                ),
                List.of(Verdict.ENTAILED, Verdict.UNKNOWN, Verdict.UNKNOWN)),
            // Case B: one contradiction among mixed evidence
            new MultiEvidenceCase("MULTI-B", "one_contradiction", "EN",
                "Employees must use VPN for remote access.",
                List.of(
                    "Employees must use VPN when accessing the municipal network remotely.",
                    "Employees do not need VPN for remote access — direct connections are permitted.",
                    "The office is open Monday through Friday."
                ),
                List.of(Verdict.ENTAILED, Verdict.CONTRADICTED, Verdict.UNKNOWN)),
            // Case C: no supporting evidence
            new MultiEvidenceCase("MULTI-C", "no_support", "EN",
                "Employees must use two-factor authentication for all systems.",
                List.of(
                    "Employees must use VPN when accessing the municipal network remotely.",
                    "Passwords must be changed every 90 days.",
                    "The office is open Monday through Friday."
                ),
                List.of(Verdict.UNKNOWN, Verdict.UNKNOWN, Verdict.UNKNOWN)),
            // Case D: multiple supporting documents
            new MultiEvidenceCase("MULTI-D", "multiple_supporting", "EN",
                "The full-day travel allowance is 24 euros.",
                List.of(
                    "The allowance for a full day is 24 euros according to BRKG.",
                    "Full-day travel reimbursement is set at 24 euros.",
                    "The office is open Monday through Friday."
                ),
                List.of(Verdict.ENTAILED, Verdict.ENTAILED, Verdict.UNKNOWN)),
            // Case E: conflicting documents
            new MultiEvidenceCase("MULTI-E", "conflicting", "EN",
                "The travel allowance is 24 euros.",
                List.of(
                    "The allowance for a full day is 24 euros according to the current regulation.",
                    "The daily travel allowance has been reduced to 12 euros effective January 1st."
                ),
                List.of(Verdict.ENTAILED, Verdict.CONTRADICTED)),
            // German multi-evidence case
            new MultiEvidenceCase("MULTI-DE", "conflicting", "DE",
                "Das Tagegeld beträgt 24 Euro.",
                List.of(
                    "Das volle Tagegeld beträgt 24 Euro gemäß der aktuellen Regelung.",
                    "Das Tagegeld wurde zum 1. Januar auf 12 Euro reduziert."
                ),
                List.of(Verdict.ENTAILED, Verdict.CONTRADICTED))
        );
    }
}
