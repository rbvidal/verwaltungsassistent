package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministisches, erklärbares Prioritätsmodell (Phase 1 der intelligenten
 * Mitarbeiter-Arbeitsplanung). Keine LLM-Bewertung: Die Punktzahl misst
 * AUSSCHLIESSLICH Bedeutung/Dringlichkeit aus strukturierten Faktoren:
 *
 * <ul>
 *   <li>Wartezeit (0–30): älteste offene Bürger-E-Mail bzw. Alter des Falls</li>
 *   <li>Frist (0–25): nächstes DEADLINE-Ereignis der Timeline (überfällig = max)</li>
 *   <li>Bürgerwirkung (0–15): Fallart-Heuristik (Wohngeld/Gewerbe hoch)</li>
 *   <li>Mitarbeiter-Dringlichkeit (0–5): explizit markierte Dringlichkeit (geoPriority)</li>
 * </ul>
 *
 * <p>Effizienz- und Kontinuitätssignale (Bearbeitungsreife der Analyse,
 * Phasenfortschritt) fließen BEWUSST NICHT in die Punktzahl ein — "leicht
 * abschließbar" ist kein Maß für "wichtig". Sie werden vollständig in der
 * Faktoren-Struktur (preparedScore/progressScore) festgehalten, damit der
 * Next-Best-Work-Score in Phase 2 sie für Effizienz/Kontinuität nutzen kann.</p>
 *
 * <p>Jeder beitragende Faktor liefert eine deutsche Begründungszeile; die
 * gespeicherte Faktoren-Struktur beantwortet dauerhaft „Warum hat dieser Fall
 * diese Priorität?“. Phase 2 kann weitere Faktoren (Aufwand, Kontextwechsel,
 * Kapazität) ergänzen, ohne das Modell zu ersetzen.</p>
 */
@Service
public class PriorityCalculationService {

    /** Prioritätsklasse — deutsche Anzeige über die View, nie Rohwerte an die Nutzerin. */
    public enum PriorityClass {
        SEHR_HOCH("Sehr hoch"),
        HOCH("Hoch"),
        MITTEL("Mittel"),
        NIEDRIG("Niedrig");

        private final String label;

        PriorityClass(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Erklärtes Prioritätsergebnis: Punktwert, Klasse, Zusammenfassung, Faktoren, Gründe. */
    public record PriorityResult(int score, PriorityClass priorityClass, String summary,
                                 List<String> reasons, Map<String, Object> factors) {}

    /** Berechnet die Priorität ausschließlich aus den strukturierten Fall-Fakten. */
    public PriorityResult calculate(CaseFacts facts) {
        Map<String, Object> factors = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();

        int waiting = waitingScore(facts, reasons, factors);
        int deadline = deadlineScore(facts, reasons, factors);
        int impact = impactScore(facts, reasons, factors);
        int stated = statedUrgencyScore(facts, reasons, factors);
        // Effizienz-/Kontinuitätssignale: nur aufgezeichnet, nicht bewertet.
        preparedFactor(facts, factors);
        progressFactor(facts, factors);

        int score = Math.min(100, waiting + deadline + impact + stated);
        PriorityClass priorityClass = classify(score);
        String summary = reasons.isEmpty() ? "Keine besonderen Faktoren." : String.join(" · ", reasons.subList(0, Math.min(3, reasons.size())));

        factors.put("score", score);
        factors.put("class", priorityClass.name());
        factors.put("reasons", reasons);
        factors.put("summary", summary);
        // Fehlende Unterlagen sind ein Blockade-Faktor (Bearbeitbarkeit) und
        // werden zur Erklärung mitgeführt — sie erhöhen bewusst NICHT die
        // Punktzahl, sondern halten den Fall im persönlichen Rückstand.
        factors.put("missingDocs", facts.missingDocs());
        return new PriorityResult(score, priorityClass, summary, List.copyOf(reasons), factors);
    }

    /** Wartezeit in Tagen → Punktzahl (0–30). */
    int waitingScore(CaseFacts facts, List<String> reasons, Map<String, Object> factors) {
        long days = facts.waitingDays();
        factors.put("waitingDays", days);
        factors.put("waitingSource", facts.waitingReason());
        int score = days >= 3 && days <= 5 ? 10 : days >= 6 && days <= 10 ? 20 : days > 10 ? 30 : 0;
        if (score > 0) {
            reasons.add(facts.waitingReason());
        }
        return score;
    }

    /** Tage bis zur nächsten Frist → Punktzahl (0–25); überfällig = 25. */
    int deadlineScore(CaseFacts facts, List<String> reasons, Map<String, Object> factors) {
        Integer days = facts.deadlineDays();
        factors.put("deadlineDays", days);
        if (days == null) {
            return 0;
        }
        int score = days < 0 || days <= 1 ? 25 : days <= 3 ? 18 : days <= 7 ? 12 : days <= 14 ? 6 : 0;
        if (score > 0) {
            reasons.add(days < 0
                    ? "Frist überschritten (Deadline-Ereignis am " + facts.deadlineDateLabel() + ")"
                    : "Frist in " + days + " Tag" + (days == 1 ? "" : "en") + " (Deadline-Ereignis am " + facts.deadlineDateLabel() + ")");
        }
        return score;
    }

    /** Fallart-Heuristik für die Bürgerwirkung (0–15). */
    int impactScore(CaseFacts facts, List<String> reasons, Map<String, Object> factors) {
        String category = facts.category() != null ? facts.category().toLowerCase(Locale.GERMANY) : "";
        int score;
        if (category.contains("wohngeld") || category.contains("gewerbe") || category.contains("sozial")) {
            score = 15;
        } else if (category.contains("ummeldung") || category.contains("bau") || category.contains("reisepass")
                || category.contains("ausweis") || category.contains("geovorgang") || category.contains("umzug")) {
            score = 10;
        } else {
            score = 5;
        }
        factors.put("citizenImpact", score);
        factors.put("citizenImpactReason", "Fallart \"" + facts.category() + "\" – Bürgerwirkung");
        if (score >= 10) {
            reasons.add("Fallart \"" + facts.category() + "\" – hohe Bürgerwirkung");
        }
        return score;
    }

    /**
     * Bearbeitungsreife — NUR aufgezeichnet, NICHT bewertet: "leicht
     * abschließbar" ist ein Effizienzsignal für den Next-Best-Work-Score
     * (Phase 2), kein Dringlichkeitsmaß. Die Bürgerin wartet unabhängig
     * davon, ob die Analyse schon abgeschlossen ist.
     */
    void preparedFactor(CaseFacts facts, Map<String, Object> factors) {
        factors.put("analysisVersion", facts.analysisVersion());
        factors.put("analysisStale", facts.analysisStale());
        factors.put("grounded", facts.grounded());
        factors.put("evidenceCount", facts.evidenceCount());
        if (facts.analysisStale() || facts.grounded() == null) {
            factors.put("preparedScore", 0);
            return;
        }
        int score = facts.grounded() && facts.evidenceCount() != null && facts.evidenceCount() > 0 ? 15
                : facts.grounded() ? 10
                : facts.analysisStatus().equals("COMPLETED") ? 5 : 0;
        factors.put("preparedScore", score);
    }

    /**
     * Phasenfortschritt — NUR aufgezeichnet, NICHT bewertet: Kontinuitäts-
     * signal (sunk/context value) für den Next-Best-Work-Score (Phase 2),
     * kein Dringlichkeitsmaß.
     */
    void progressFactor(CaseFacts facts, Map<String, Object> factors) {
        WorkspacePhase phase = facts.phase() != null ? facts.phase() : WorkspacePhase.SETUP;
        int score = switch (phase) {
            case SETUP -> 3;
            case INGESTION -> 6;
            case ANALYSIS -> 9;
            case REVIEW -> 12;
            case COMPLETE -> 15;
        };
        factors.put("progressScore", score);
        factors.put("phase", phase.name());
    }

    /** Explizit markierte Dringlichkeit durch den Mitarbeiter (geoPriority) — 0–5. */
    int statedUrgencyScore(CaseFacts facts, List<String> reasons, Map<String, Object> factors) {
        String u = facts.statedUrgency() != null ? facts.statedUrgency().toLowerCase(Locale.GERMANY) : "";
        int score = (u.contains("sofort") || u.contains("dringend") || u.contains("sehr hoch")) ? 5
                : u.contains("hoch") ? 3 : 0;
        factors.put("statedUrgency", u.isBlank() ? null : u);
        if (score > 0) {
            reasons.add("Dringlichkeit vom Mitarbeiter markiert (\"" + facts.statedUrgency() + "\")");
        }
        return score;
    }

    /** Klassengrenzen: ≥70 Sehr hoch · ≥50 Hoch · ≥30 Mittel · sonst Niedrig. */
    public PriorityClass classify(int score) {
        if (score >= 70) {
            return PriorityClass.SEHR_HOCH;
        }
        if (score >= 50) {
            return PriorityClass.HOCH;
        }
        if (score >= 30) {
            return PriorityClass.MITTEL;
        }
        return PriorityClass.NIEDRIG;
    }

    /** Prioritätsklasse der Wartezeit einer E-Mail (Anzeige in der E-Mail-Warteschlange). */
    public PriorityClass emailPriorityClass(long daysWaiting) {
        if (daysWaiting > 7) {
            return PriorityClass.SEHR_HOCH;
        }
        if (daysWaiting >= 4) {
            return PriorityClass.HOCH;
        }
        if (daysWaiting >= 2) {
            return PriorityClass.MITTEL;
        }
        return PriorityClass.NIEDRIG;
    }

    /** Farb-Variante einer Prioritätsklasse (eigene semantische Klassen):
     *  Niedrig muted blue-grey · Mittel amber · Hoch rot · Kritisch kräftigeres
     *  Rot. Die generischen badge--neutral/info/warning/error-Varianten bleiben
     *  ihren nicht-prioritären Bedeutungen vorbehalten. */
    public String variant(PriorityClass priorityClass) {
        return switch (priorityClass) {
            case SEHR_HOCH -> "priority-critical";
            case HOCH -> "priority-high";
            case MITTEL -> "priority-medium";
            case NIEDRIG -> "priority-low";
        };
    }
}
