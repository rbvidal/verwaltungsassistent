package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministisches Prioritätsmodell: Faktoren, Klassengrenzen, Begründungen.
 * Reine Rechenlogik — keine Persistenz, kein LLM.
 */
class PriorityCalculationServiceTest {

    private final PriorityCalculationService service = new PriorityCalculationService();

    private CaseFacts facts(long waitingDays, Integer deadlineDays, String category,
                            String analysisStatus, boolean analysisStale, Boolean grounded,
                            int evidenceCount, WorkspacePhase phase, String statedUrgency) {
        return new CaseFacts(
                "case-1", "Fall Wohngeld", "Beschreibung", category,
                WorkspaceStatus.ACTIVE, phase, "Analyse", java.time.Instant.now().minusSeconds(waitingDays * 86400),
                1, 0, 0, 1, true,
                analysisStatus, analysisStatus.equals("COMPLETED") ? 3 : null, analysisStale,
                grounded, evidenceCount, List.of(),
                waitingDays, "Bürger wartet seit " + waitingDays + " Tagen",
                deadlineDays, deadlineDays != null ? "01.01.2026" : null, statedUrgency, false, null);
    }

    @Test
    void waitingTime_scoresByBands() {
        assertEquals(0, waitingScoreOf(2));
        assertEquals(10, waitingScoreOf(4));
        assertEquals(20, waitingScoreOf(7));
        assertEquals(30, waitingScoreOf(12));
    }

    /** Punktbeitrag der Wartezeit = Differenz zur 0-Tage-Basis. */
    private int waitingScoreOf(long days) {
        int base = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score();
        int withWaiting = service.calculate(facts(days, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score();
        return withWaiting - base;
    }

    @Test
    void waitingReason_mentionsDaysAndEmail() {
        var result = service.calculate(facts(6, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, ""));
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Bürger wartet seit 6 Tagen")),
                "reason names the citizen waiting time: " + result.reasons());
    }

    @Test
    void deadline_scoresOverdueHighest() {
        assertEquals(25, service.calculate(facts(0, -1, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score()
                - baseScore("Allgemein"));
        assertEquals(25, service.calculate(facts(0, 1, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score()
                - baseScore("Allgemein"));
        assertEquals(18, service.calculate(facts(0, 3, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score()
                - baseScore("Allgemein"));
        assertEquals(12, service.calculate(facts(0, 6, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score()
                - baseScore("Allgemein"));
        assertEquals(0, service.calculate(facts(0, 20, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score()
                - baseScore("Allgemein"));
    }

    private int baseScore(String category) {
        return service.calculate(facts(0, null, category, "", false, null, 0, WorkspacePhase.SETUP, "")).score();
    }

    @Test
    void deadlineReason_overdueIsExplicit() {
        var result = service.calculate(facts(0, -2, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, ""));
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Frist überschritten")),
                "overdue deadline is explained: " + result.reasons());
    }

    @Test
    void citizenImpact_byCategory() {
        assertEquals(15, impactOf("Wohngeld"));
        assertEquals(15, impactOf("Gewerbeanmeldung"));
        assertEquals(10, impactOf("Ummeldung"));
        assertEquals(10, impactOf("Baugenehmigung"));
        assertEquals(5, impactOf("Allgemein"));
    }

    private int impactOf(String category) {
        var result = service.calculate(facts(0, null, category, "", false, null, 0, WorkspacePhase.SETUP, ""));
        return (int) result.factors().get("citizenImpact");
    }

    /**
     * Phase 1.5-Kontrakt: Bearbeitungsreife ist NUR ein aufgezeichneter
     * Effizienz-Faktor für Phase 2 — sie erhöht die Priorität nicht.
     */
    @Test
    void prepared_groundedWithEvidence_isRecordedButNotScored() {
        var withAnalysis = service.calculate(facts(5, null, "Allgemein", "COMPLETED", false, true, 4, WorkspacePhase.SETUP, ""));
        var withoutAnalysis = service.calculate(facts(5, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, ""));

        assertEquals(15, (int) withAnalysis.factors().get("preparedScore"),
                "preparedness remains available for Phase 2 (efficiency)");
        assertEquals(withAnalysis.score(), withoutAnalysis.score(),
                "two otherwise identical cases must not differ in priority merely because one has a completed analysis");
        assertFalse(withAnalysis.reasons().stream().anyMatch(r -> r.contains("quellenbelegt")),
                "readiness is not a priority reason: " + withAnalysis.reasons());
    }

    @Test
    void prepared_staleAnalysis_isRecordedAsZero_butDoesNotChangeScore() {
        var stale = service.calculate(facts(0, null, "Allgemein", "COMPLETED", true, true, 4, WorkspacePhase.SETUP, ""));
        var fresh = service.calculate(facts(0, null, "Allgemein", "COMPLETED", false, true, 4, WorkspacePhase.SETUP, ""));

        assertEquals(stale.score(), fresh.score(),
                "analysis staleness affects readiness, not priority");
        assertTrue((boolean) stale.factors().get("analysisStale"));
        assertEquals(0, (int) stale.factors().get("preparedScore"));
        assertEquals(15, (int) fresh.factors().get("preparedScore"));
    }

    @Test
    void prepared_withoutAnalysis_isZero() {
        var result = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, ""));
        assertEquals(0, (int) result.factors().get("preparedScore"));
    }

    /**
     * Phase 1.5-Kontrakt: Phasenfortschritt (Kontinuität) ist ebenfalls nur
     * aufgezeichnet — er erhöht die Priorität nicht.
     */
    @Test
    void progress_isRecordedButDoesNotRaisePriority() {
        int setup = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score();
        int review = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.REVIEW, "")).score();
        int complete = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.COMPLETE, "")).score();

        assertEquals(setup, review, "continuity/progress must not raise priority");
        assertEquals(setup, complete);
        assertEquals(3, service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, ""))
                .factors().get("progressScore"));
        assertEquals(12, service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.REVIEW, ""))
                .factors().get("progressScore"));
    }

    @Test
    void efficiencySignals_remainAvailableForPhase2() {
        var result = service.calculate(facts(5, 2, "Wohngeld", "COMPLETED", false, true, 4, WorkspacePhase.REVIEW, ""));

        assertTrue(result.factors().containsKey("preparedScore"));
        assertTrue(result.factors().containsKey("progressScore"));
        assertTrue(result.factors().containsKey("grounded"));
        assertTrue(result.factors().containsKey("evidenceCount"));
        assertTrue(result.factors().containsKey("analysisVersion"));
        assertTrue(result.factors().containsKey("phase"));
        assertTrue(result.factors().containsKey("missingDocs"));
    }

    @Test
    void statedUrgency_boostsScore() {
        int neutral = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "")).score();
        int dringend = service.calculate(facts(0, null, "Allgemein", "", false, null, 0, WorkspacePhase.SETUP, "dringend")).score();
        assertTrue(dringend > neutral, "employee-stated urgency must raise the priority");
    }

    @Test
    void score_isClampedTo100() {
        var result = service.calculate(facts(30, -5, "Wohngeld", "COMPLETED", false, true, 6, WorkspacePhase.REVIEW, "dringend"));
        assertTrue(result.score() <= 100);
        assertEquals(PriorityCalculationService.PriorityClass.SEHR_HOCH, result.priorityClass());
    }

    @Test
    void classBoundaries() {
        assertEquals(PriorityCalculationService.PriorityClass.SEHR_HOCH, service.classify(70));
        assertEquals(PriorityCalculationService.PriorityClass.HOCH, service.classify(50));
        assertEquals(PriorityCalculationService.PriorityClass.MITTEL, service.classify(30));
        assertEquals(PriorityCalculationService.PriorityClass.NIEDRIG, service.classify(10));
    }

    @Test
    void summary_containsTopReasons() {
        var result = service.calculate(facts(6, 2, "Wohngeld", "", false, null, 0, WorkspacePhase.SETUP, ""));
        assertFalse(result.summary().isBlank());
        assertTrue(result.reasons().size() >= 2, "several reasons are retained: " + result.reasons());
    }

    @Test
    void emailPriorityClass_byWaitingDays() {
        assertEquals(PriorityCalculationService.PriorityClass.SEHR_HOCH, service.emailPriorityClass(8));
        assertEquals(PriorityCalculationService.PriorityClass.HOCH, service.emailPriorityClass(4));
        assertEquals(PriorityCalculationService.PriorityClass.MITTEL, service.emailPriorityClass(2));
        assertEquals(PriorityCalculationService.PriorityClass.NIEDRIG, service.emailPriorityClass(0));
    }
}
