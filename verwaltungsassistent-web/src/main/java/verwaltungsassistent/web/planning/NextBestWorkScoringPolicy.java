package verwaltungsassistent.web.planning;

import org.springframework.stereotype.Component;

/**
 * Isolierte, austauschbare Bewertungspolitik für Next-Best-Work (Phase 2B).
 *
 * <p>Modell:</p>
 * <pre>
 * candidateScore = urgencyValue × opportunityFactor
 * opportunityFactor = 1 + completionValue + continuity − contextPenalty
 * </pre>
 *
 * <ul>
 *   <li><b>urgenValue</b> = priority_score (Phase-1.5, rein Wichtigkeit/Dringlichkeit).</li>
 *   <li><b>completionValue</b> (max 0.45) = Wert der Fertigstellung (Restaufwand,
 *       Abschluss jetzt erreichbar, fast fertig, Bürger wartet, nächster Schritt klar).</li>
 *   <li><b>continuity</b> (max 0.15) = bereits begonnen / aktiv bearbeitet von der Mitarbeiterin.</li>
 *   <li><b>contextPenalty</b> (max 0.35) = anderes Fachgebiet als die aktuelle Arbeit,
 *       nie angefasst, Wechsel weg von aktiver Arbeit.</li>
 *   <li><b>Kapazität</b> = weich: kannFinishToday (Restaufwand ≤ Restkapazität) und eine
 *       kleine, benannte Strafe nur bei fast erschöpfter Kapazität + langem Vorgang.
 *       Keine harte Exklusion.</li>
 * </ul>
 *
 * <p><b>Kritische Frist:</b> Frist heute/überfällig (deadlineDays ≤ 0) bildet die
 * EINE legitime Schutz-Bande über allen normalen Kandidaten — begründet durch
 * die Frist-Semantik, nicht durch die Prioritätsklasse. Innerhalb der Bande
 * gilt dieselbe Formel.</p>
 *
 * <p>Alle Koeffizienten sind benannt und dokumentiert — MVP-Hypothese, der
 * empirischen Validierung vorbehalten. Der Zahlenwert wird der Mitarbeiterin
 * nie angezeigt.</p>
 */
@Component
public class NextBestWorkScoringPolicy {

    // ── Benannte MVP-Koeffizienten (isoliert; Anpassung ohne Engine-Redesign) ──
    public static final double OPPORTUNITY_MIN = 0.6;
    public static final double OPPORTUNITY_MAX = 1.6;
    public static final double COMPLETION_VALUE_MAX = 0.45;
    public static final double COMPLETION_EFFORT_COEFFICIENT = 0.10;
    public static final double COMPLETION_FINISH_NOW = 0.15;
    public static final double COMPLETION_NEAR_COMPLETE = 0.10;
    public static final double COMPLETION_CITIZEN_WAITING = 0.05;
    public static final double COMPLETION_CLEAR_ACTION = 0.05;
    public static final double CONTINUITY_STARTED = 0.10;
    public static final double CONTINUITY_BEING_WORKED = 0.05;
    public static final double CONTEXT_DIFFERENT_DOMAIN = 0.15;
    public static final double CONTEXT_NEVER_TOUCHED = 0.10;
    public static final double CONTEXT_SWITCHING_FROM_ACTIVE = 0.10;
    public static final double CONTEXT_MAX = 0.35;
    public static final int DAILY_CAPACITY_MINUTES = 480;
    public static final int CAPACITY_TIGHT_MINUTES = 30;
    public static final int LONG_TASK_THRESHOLD_MINUTES = 60;
    public static final double CAPACITY_TIGHT_LONG_TASK_PENALTY = 0.10;

    /** Strukturierte Signale eines Kandidaten (ausschließlich aus persistierten Fakten). */
    public record CandidateSignals(
            int remainingEffortMinutes,
            boolean canFinishNow,
            boolean nearComplete,
            boolean citizenWaiting,
            boolean clearAction,
            boolean startedByMe,
            boolean beingWorkedByMe,
            boolean differentDomain,
            boolean neverTouched,
            boolean switchingFromActiveWork,
            boolean criticalDeadline) {}

    /** Numerisches Bewertungsergebnis (nur intern/Trace — nie der Mitarbeiterin zeigen). */
    public record PolicyResult(
            double urgency, double completionValue, double continuity,
            double contextPenalty, double opportunityFactor, double score,
            int remainingCapacity, boolean canFinishToday) {}

    /** Bewertet einen Kandidaten deterministisch. */
    public PolicyResult evaluate(int priorityScore, CandidateSignals s, int workloadMinutes) {
        double completionValue = 0;
        if (s.canFinishNow()) {
            completionValue += COMPLETION_FINISH_NOW;
        }
        if (s.nearComplete()) {
            completionValue += COMPLETION_NEAR_COMPLETE;
        }
        if (s.citizenWaiting()) {
            completionValue += COMPLETION_CITIZEN_WAITING;
        }
        if (s.clearAction()) {
            completionValue += COMPLETION_CLEAR_ACTION;
        }
        completionValue += COMPLETION_EFFORT_COEFFICIENT
                * (30.0 / (30.0 + s.remainingEffortMinutes()));
        completionValue = Math.min(COMPLETION_VALUE_MAX, completionValue);

        double continuity = (s.startedByMe() ? CONTINUITY_STARTED : 0)
                + (s.beingWorkedByMe() ? CONTINUITY_BEING_WORKED : 0);

        double contextPenalty = 0;
        if (s.differentDomain()) {
            contextPenalty += CONTEXT_DIFFERENT_DOMAIN;
        }
        if (s.neverTouched()) {
            contextPenalty += CONTEXT_NEVER_TOUCHED;
        }
        if (s.switchingFromActiveWork()) {
            contextPenalty += CONTEXT_SWITCHING_FROM_ACTIVE;
        }
        contextPenalty = Math.min(CONTEXT_MAX, contextPenalty);

        double opportunity = 1 + completionValue + continuity - contextPenalty;

        int remainingCapacity = Math.max(0, DAILY_CAPACITY_MINUTES - workloadMinutes);
        boolean canFinishToday = s.remainingEffortMinutes() <= remainingCapacity;
        if (remainingCapacity < CAPACITY_TIGHT_MINUTES
                && s.remainingEffortMinutes() > LONG_TASK_THRESHOLD_MINUTES) {
            opportunity -= CAPACITY_TIGHT_LONG_TASK_PENALTY;
        }
        opportunity = Math.max(OPPORTUNITY_MIN, Math.min(OPPORTUNITY_MAX, opportunity));

        double score = priorityScore * opportunity;
        return new PolicyResult(priorityScore, round2(completionValue), round2(continuity),
                round2(contextPenalty), round2(opportunity), round2(score),
                remainingCapacity, canFinishToday);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
