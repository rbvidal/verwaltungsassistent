package verwaltungsassistent.web.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2B: Validierung der isolierten Bewertungspolitik gegen die
 * genehmigten Szenarien (§12). Die Koeffizienten sind eine MVP-Hypothese —
 * diese Tests beweisen Determiniertheit, Trennung, Grenzen und erwartete
 * Szenarien, nicht die subjektive Präferenz realer Mitarbeiterinnen.
 */
class NextBestWorkScoringPolicyTest {

    private final NextBestWorkScoringPolicy policy = new NextBestWorkScoringPolicy();

    private NextBestWorkScoringPolicy.CandidateSignals signals(int remaining, boolean canFinish, boolean nearComplete,
                                                               boolean started, boolean beingWorked,
                                                               boolean differentDomain, boolean neverTouched,
                                                               boolean switching, boolean critical) {
        return new NextBestWorkScoringPolicy.CandidateSignals(
                remaining, canFinish, nearComplete, true, true,
                started, beingWorked, differentDomain, neverTouched, switching, critical);
    }

    private double score(int urgency, NextBestWorkScoringPolicy.CandidateSignals s, int workload) {
        return policy.evaluate(urgency, s, workload).score();
    }

    // ── Szenario 1: Sehr hoch/4h/unangetastet/Switch vs. Hoch/10min/begonnen ──

    @Test
    void scenario1_shortStartedCase_canOutrankHugeUrgentCase() {
        var huge = signals(240, false, false, false, false, true, true, true, false);
        var shortCase = signals(10, true, true, true, true, false, false, false, false);

        double hugeScore = score(90, huge, 0);
        double shortScore = score(70, shortCase, 0);

        assertTrue(shortScore > hugeScore,
                "Hoch/10 min/begonnen may be the better next work than Sehr hoch/4h/unangetastet ("
                        + shortScore + " vs " + hugeScore + ")");
    }

    // ── Szenario 2: Sehr hoch/1h vs. Hoch/10min/begonnen ──

    @Test
    void scenario2_oneHourWithoutCriticalDeadline_isOutrankedByShortStartedCase() {
        var oneHour = signals(60, false, false, false, false, true, true, true, false);
        var shortCase = signals(10, true, true, true, true, false, false, false, false);

        assertTrue(score(70, shortCase, 0) > score(90, oneHour, 0),
                "without a critical deadline, the 10-min started case wins (explainable)");
    }

    @Test
    void scenario2_criticalBand_isAComparatorProperty_notAScoreBonus() {
        // Die Frist-heute-Bande lebt bewusst im Rang-Vergleich des
        // NextBestWorkService (kritisch → zuerst), nicht in der Punktzahl —
        // sonst wären Scores zwischen Fällen nicht mehr vergleichbar. Das
        // Szenario "Frist heute schützt den dringenden Fall" wird daher auf
        // Engine-Ebene validiert (NextBestWorkServiceTest.
        // criticalDeadline_bandProtectsTheUrgentCase), nicht hier.
        var critical = signals(60, false, false, false, false, true, true, true, true);
        var shortCase = signals(10, true, true, true, true, false, false, false, false);

        assertTrue(score(70, shortCase, 0) > score(95, critical, 0),
                "the score alone never protects the critical case — the comparator band does");
    }

    // ── Szenario 3: kritische Frist vs. triviale Kurzaufgabe ──

    @Test
    void scenario3_criticalDeadline_isNotDisplacedByTrivialTask() {
        var critical = signals(240, false, false, false, false, true, true, true, true);
        var trivial = signals(10, true, true, false, false, false, true, false, false);

        double criticalScore = score(95, critical, 0);
        double trivialScore = score(20, trivial, 0);
        assertTrue(criticalScore > trivialScore,
                "an overdue/today deadline must not be displaced by a trivial short task");
    }

    // ── Szenario 5: gleiche Dringlichkeit, kürzerer Restaufwand gewinnt ──

    @Test
    void scenario5_equalUrgency_shorterRemainingWins() {
        var shortCase = signals(10, true, true, false, false, false, true, false, false);
        var longCase = signals(180, false, false, false, false, false, true, false, false);

        assertTrue(score(70, shortCase, 0) > score(70, longCase, 0),
                "equal urgency → shorter remaining effort generally wins");
    }

    // ── Szenario 6: gleiche Dringlichkeit + Aufwand, begonnen gewinnt ──

    @Test
    void scenario6_equalUrgencyAndEffort_startedCaseWins() {
        var started = signals(30, false, false, true, false, false, false, false, false);
        var untouched = signals(30, false, false, false, false, false, true, false, false);

        assertTrue(score(70, started, 0) > score(70, untouched, 0),
                "equal urgency and effort → continuity decides");
    }

    // ── Szenario 7: triviale Niedrig-Aufgabe besiegt nie echte Dringlichkeit ──

    @Test
    void scenario7_trivialTask_neverBeatsGenuineUrgency() {
        // Trivial (20) mit maximaler Chance (1.6) vs. Hoch (70) mit minimaler (0.6).
        var trivial = signals(10, true, true, true, true, false, false, false, false);
        var urgentWorst = signals(240, false, false, false, false, true, true, true, false);

        assertTrue(score(20, trivial, 0) < score(70, urgentWorst, 0),
                "a trivial low-priority task must not systematically defeat genuinely urgent work");
    }

    // ── Grenzen ──

    @Test
    void opportunityFactor_isBounded() {
        // Maximal: Restaufwand 0 → Completion 0.45 + Continuity 0.15 → Decke 1.6.
        var perfect = signals(0, true, true, true, true, false, false, false, false);
        // Minimal: extrem hoher Restaufwand + volle Kontext-Strafe → nah an 0.6.
        var worst = signals(10000, false, false, false, false, true, true, true, false);

        assertEquals(1.6, policy.evaluate(70, perfect, 0).opportunityFactor(), 0.0001);
        double worstOpp = policy.evaluate(70, worst, 0).opportunityFactor();
        assertTrue(worstOpp >= 0.6 && worstOpp <= 1.6,
                "opportunity stays within [0.6, 1.6]: " + worstOpp);
    }

    @Test
    void completionValueAndContinuity_areBounded() {
        var maxed = signals(5, true, true, true, true, false, false, false, false);
        var result = policy.evaluate(70, maxed, 0);

        assertTrue(result.completionValue() <= 0.45);
        assertEquals(0.15, result.continuity(), 0.001);
        assertEquals(0.0, result.contextPenalty(), 0.001);
    }

    @Test
    void contextPenalty_isBoundedAt035() {
        var switching = signals(60, false, false, false, false, true, true, true, false);
        var result = policy.evaluate(70, switching, 0);
        assertEquals(0.35, result.contextPenalty(), 0.001);
    }

    // ── Kapazität (weich) ──

    @Test
    void capacity_canFinishToday_andSoftPenalty() {
        // 480 - 460 workload = 20 min Restkapazität; langer Vorgang (120) ist
        // weiterhin Kandidat, aber mit heute-nicht-abschließbar + kleiner Strafe.
        var longTask = signals(120, false, false, false, false, false, true, false, false);
        var result = policy.evaluate(70, longTask, 460);

        assertFalse(result.canFinishToday(), "120 min > 20 min remaining capacity");
        assertTrue(result.opportunityFactor() < 1.0, "tight capacity adds a bounded penalty");

        var shortTask = signals(15, true, true, false, false, false, true, false, false);
        var shortResult = policy.evaluate(70, shortTask, 460);
        assertTrue(shortResult.canFinishToday(), "15 min fits into 20 min capacity");
    }

    @Test
    void capacity_isSoft_andNeverFeedsContextPenalty() {
        // Gleiche Signale bei 360 vs. 0 Minuten Restkapazität: nur
        // canFinishToday kippt, die Kontext-Strafe (0.10 = neverTouched)
        // bleibt identisch — Kapazität ist weich und kein Kontext-Faktor.
        var task = signals(60, false, false, false, false, false, true, false, false);
        var withRoom = policy.evaluate(70, task, 120);
        var full = policy.evaluate(70, task, 480);

        assertTrue(withRoom.canFinishToday(), "60 min fit into 360 min remaining capacity");
        assertFalse(full.canFinishToday(), "no remaining capacity");
        assertEquals(full.contextPenalty(), withRoom.contextPenalty(), 0.001,
                "capacity never changes the context penalty");
    }

    @Test
    void capacity_doesNotHardExclude() {
        // Restkapazität 0: der 2h-Vorgang bleibt bewertbar (weiche Kapazität).
        var task = signals(120, false, false, false, false, false, true, false, false);
        var result = policy.evaluate(95, task, 480);
        assertTrue(result.score() > 0, "capacity is soft, never a hard exclusion");
        assertFalse(result.canFinishToday());
    }
}
