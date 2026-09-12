package verwaltungsassistent.web.planning;

import reasoning.common.model.WorkspacePhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2B: Restaufwand-Schätzung — empirischer Kategorie-Median (Phase 2A)
 * bzw. YAML-Baseline als Grundlage, bereinigte aktive Arbeit, Phasen-
 * Progression, Unterlagen-/Analyse-Korrekturen. Pausierte Zeit fließt nie ein
 * (nur accumulatedActiveMinutes zählt).
 */
class EffortEstimatorTest {

    private EffortLearningService effortLearningService;
    private EffortEstimator estimator;

    @BeforeEach
    void setUp() {
        effortLearningService = mock(EffortLearningService.class);
        estimator = new EffortEstimator(effortLearningService, new ProcessingTimeConfig());
    }

    private void withEmpirical(String category, int minutes) {
        when(effortLearningService.effectiveEstimateMinutes(category)).thenReturn(minutes);
    }

    @Test
    void totalEffort_usesEmpiricalMedian() {
        withEmpirical("gewerbeanmeldung", 32);
        assertEquals(32, estimator.totalEffortMinutes("gewerbeanmeldung"),
                "empirical median replaces the YAML baseline");
    }

    @Test
    void totalEffort_withoutEmpiricalData_usesYamlBaseline() {
        // Der YAML-Fallback lebt in EffortLearningService.effectiveEstimateMinutes
        // (empirischer Median, sonst Baseline) — hier die volle Kette real.
        var realLearning = new EffortLearningService(
                mock(verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository.class),
                mock(verwaltungsassistent.web.planning.persistence.JpaEffortEstimateRepository.class),
                new ProcessingTimeConfig());
        var realEstimator = new EffortEstimator(realLearning, new ProcessingTimeConfig());
        assertEquals(30, realEstimator.totalEffortMinutes("gewerbeanmeldung"),
                "no empirical estimate → YAML baseline");
    }

    @Test
    void remaining_untouchedCase_usesPhaseProgression() {
        withEmpirical("gewerbeanmeldung", 30);
        assertEquals(27, estimator.remainingEffortMinutes("gewerbeanmeldung", WorkspacePhase.SETUP, 0, false, 0, 0),
                "SETUP: 10 % erledigt → 27 min restlich");
        assertEquals(15, estimator.remainingEffortMinutes("gewerbeanmeldung", WorkspacePhase.ANALYSIS, 0, false, 0, 0),
                "ANALYSIS: 50 % erledigt → 15 min restlich");
        assertEquals(6, estimator.remainingEffortMinutes("gewerbeanmeldung", WorkspacePhase.REVIEW, 0, false, 0, 0),
                "REVIEW: 80 % erledigt → 6 min restlich");
    }

    @Test
    void remaining_subtractsAccumulatedActiveWork() {
        withEmpirical("gewerbeanmeldung", 30);
        assertEquals(5, estimator.remainingEffortMinutes("gewerbeanmeldung", WorkspacePhase.SETUP, 25, false, 0, 0),
                "25 active minutes consumed → 5 remaining");
        assertEquals(1, estimator.remainingEffortMinutes("gewerbeanmeldung", WorkspacePhase.SETUP, 40, false, 0, 0),
                "more active time than the estimate → floor of 1");
    }

    @Test
    void pausedTime_isNeverIncluded() {
        // Die Schätzung erhält ausschließlich accumulatedActiveMinutes —
        // pausierte Intervalle wurden dort (Phase 2A) bereits herausgerechnet.
        // INGESTION hat 25 % Phasen-Fortschritt: 0 aktiv → 15 * 0,75 = 11.
        withEmpirical("ummeldung", 15);
        assertEquals(11, estimator.remainingEffortMinutes("ummeldung", WorkspacePhase.INGESTION, 0, false, 0, 0));
        assertEquals(11, estimator.remainingEffortMinutes("ummeldung", WorkspacePhase.INGESTION, 4, false, 0, 0),
                "4 active minutes consumed: 15 - 4");
        assertEquals(5, estimator.remainingEffortMinutes("ummeldung", WorkspacePhase.INGESTION, 10, false, 0, 0));
        assertEquals(1, estimator.remainingEffortMinutes("ummeldung", WorkspacePhase.INGESTION, 20, false, 0, 0),
                "more active time than the estimate → floor of 1");
    }

    @Test
    void missingDocuments_raiseRemainingEffort_bounded() {
        withEmpirical("wohngeld", 45);
        int without = estimator.remainingEffortMinutes("wohngeld", WorkspacePhase.ANALYSIS, 0, false, 0, 0);
        int withMissing = estimator.remainingEffortMinutes("wohngeld", WorkspacePhase.ANALYSIS, 0, false, 0, 2);
        assertTrue(withMissing > without, "missing documents raise the remaining effort");
        assertEquals((int) Math.round(without * 1.25), withMissing);
    }

    @Test
    void preparedAnalysis_lowersRemainingEffort_bounded() {
        withEmpirical("wohngeld", 45);
        int unprepared = estimator.remainingEffortMinutes("wohngeld", WorkspacePhase.ANALYSIS, 0, false, 0, 0);
        int prepared = estimator.remainingEffortMinutes("wohngeld", WorkspacePhase.ANALYSIS, 0, true, 4, 0);
        assertTrue(prepared < unprepared, "a grounded, evidence-bearing analysis lowers the remaining effort");
        assertEquals((int) Math.round(unprepared * 0.8), prepared);
    }

    @Test
    void remaining_isNeverZero_orNegative() {
        withEmpirical("allgemein", 30);
        for (int active : new int[]{0, 10, 25, 40, 90}) {
            assertTrue(estimator.remainingEffortMinutes("allgemein", WorkspacePhase.SETUP, active, false, 0, 0) >= 1);
        }
    }
}
