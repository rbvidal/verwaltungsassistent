package verwaltungsassistent.web.planning;

import verwaltungsassistent.web.planning.persistence.EffortEstimateEntity;
import verwaltungsassistent.web.planning.persistence.EffortObservationEntity;
import verwaltungsassistent.web.planning.persistence.JpaEffortEstimateRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2A: empirisches Lernmodell — kumulativer Median (nie nur die letzten
 * fünf), P75/P90, Lern-Intervall, Ausreißer-Robustheit, Komplexitäts-Feedback
 * getrennt vom Median, Effektivschätzung mit YAML-Fallback.
 */
@ExtendWith(MockitoExtension.class)
class EffortLearningServiceTest {

    @Mock
    private JpaEffortObservationRepository observationRepository;
    @Mock
    private JpaEffortEstimateRepository estimateRepository;

    private EffortLearningService service;

    private final java.util.Map<String, EffortEstimateEntity> savedEstimates = new java.util.LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        // Kleiner In-Memory-Fake für das Aggregat: recalculate() speichert in
        // savedEstimates; aggregate()/effectiveEstimateMinutes() lesen daraus.
        org.mockito.Mockito.lenient().when(estimateRepository.save(any()))
                .thenAnswer(inv -> {
                    EffortEstimateEntity e = inv.getArgument(0);
                    savedEstimates.put(e.getCategory(), e);
                    return e;
                });
        org.mockito.Mockito.lenient().when(estimateRepository.findByCategory(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(savedEstimates.get(inv.getArgument(0))));
        service = new EffortLearningService(observationRepository, estimateRepository,
                new ProcessingTimeConfig());
    }

    private void observe(String caseId, String category, int minutes, long countAfter) {
        when(observationRepository.findByCaseId(UUID.fromString(caseId)))
                .thenReturn(Optional.empty());
        when(observationRepository.countByCategory(category)).thenReturn(countAfter);
        service.recordObservation(caseId, category, minutes);
    }

    private void stubObservations(String category, int... minutes) {
        List<EffortObservationEntity> list = new ArrayList<>();
        for (int m : minutes) {
            EffortObservationEntity e = new EffortObservationEntity(UUID.randomUUID());
            e.setCategory(category);
            e.setObservedActiveMinutes(m);
            e.setCompletedAt(Instant.now());
            list.add(e);
        }
        when(observationRepository.findByCategoryOrderByCompletedAt(category)).thenReturn(list);
    }

    // ── Lern-Intervall: 0/4 → Baseline, 5 → erster empirischer Median ──

    @Test
    void zeroOrFourObservations_useYamlBaseline() {
        assertEquals(30, service.effectiveEstimateMinutes("gewerbeanmeldung"),
                "0 observations → YAML baseline");
        // 4 Beobachtungen: Statistik noch nicht fällig (5-Intervall).
        observe(UUID.randomUUID().toString(), "gewerbeanmeldung", 20, 1);
        observe(UUID.randomUUID().toString(), "gewerbeanmeldung", 25, 2);
        observe(UUID.randomUUID().toString(), "gewerbeanmeldung", 22, 3);
        observe(UUID.randomUUID().toString(), "gewerbeanmeldung", 28, 4);
        verify(estimateRepository, never()).save(any());
        assertEquals(30, service.effectiveEstimateMinutes("gewerbeanmeldung"));
    }

    @Test
    void fifthObservation_triggersEmpiricalMedian_overAllFive() {
        stubObservations("gewerbeanmeldung", 20, 22, 25, 28, 180); // Ausreißer
        observe(UUID.randomUUID().toString(), "gewerbeanmeldung", 180, 5);

        EffortEstimateEntity estimate = service.aggregate("gewerbeanmeldung").orElseThrow();
        assertEquals(5, estimate.getSampleCount());
        assertEquals(25, estimate.getMedianMinutes(),
                "the median of all five is robust against the 180 outlier");
        assertEquals("EMPIRICAL", estimate.getSource());
        assertNull(estimate.getPreviousMedianMinutes(), "first computation has no previous median");
        assertEquals(25, service.effectiveEstimateMinutes("gewerbeanmeldung"),
                "once computed, the empirical median replaces the YAML baseline");
    }

    // ── Kumulativ: 10 → Median aller 10, 15 → Median aller 15 ──

    @Test
    void tenthObservation_medianOfAllTen() {
        stubObservations("ummeldung", 10, 12, 13, 14, 15, 16, 17, 18, 19, 21);
        observe(UUID.randomUUID().toString(), "ummeldung", 21, 10);

        EffortEstimateEntity estimate = service.aggregate("ummeldung").orElseThrow();
        assertEquals(10, estimate.getSampleCount());
        assertEquals(15, estimate.getMedianMinutes(),
                "median of all 10 (nearest-rank)");
    }

    @Test
    void fifteenthObservation_medianOfAllFifteen() {
        stubObservations("wohngeld", 40, 41, 42, 43, 44, 45, 45, 46, 47, 48, 49, 50, 51, 52, 200);
        observe(UUID.randomUUID().toString(), "wohngeld", 200, 15);

        EffortEstimateEntity estimate = service.aggregate("wohngeld").orElseThrow();
        assertEquals(15, estimate.getSampleCount());
        assertEquals(46, estimate.getMedianMinutes(),
                "median of all 15 (nearest-rank), outlier ignored");
    }

    // ── P75/P90 ──

    @Test
    void p75AndP90_areCalculatedCorrectly() {
        stubObservations("reisepass", 15, 17, 18, 19, 21);
        observe(UUID.randomUUID().toString(), "reisepass", 21, 5);

        EffortEstimateEntity estimate = service.aggregate("reisepass").orElseThrow();
        assertEquals(19, estimate.getP75Minutes(), "nearest-rank P75 of 5 = 4th value");
        assertEquals(21, estimate.getP90Minutes(), "nearest-rank P90 of 5 = 5th value");
    }

    // ── Statistik nur im Intervall ──

    @Test
    void statistics_onlyUpdatedAtInterval() {
        // 5 Beobachtungen → erste Statistik mit previousMedian null.
        stubObservations("baugenehmigung", 50, 55, 60, 65, 70);
        observe(UUID.randomUUID().toString(), "baugenehmigung", 70, 5);
        EffortEstimateEntity first = service.aggregate("baugenehmigung").orElseThrow();
        assertEquals(60, first.getMedianMinutes());
        assertNull(first.getPreviousMedianMinutes());

        // 6–9: keine Neuberechnung, alte Statistik bleibt.
        org.mockito.Mockito.clearInvocations(estimateRepository);
        when(observationRepository.findByCaseId(any())).thenReturn(Optional.empty());
        when(observationRepository.countByCategory("baugenehmigung")).thenReturn(9L);
        service.recordObservation(UUID.randomUUID().toString(), "baugenehmigung", 71);
        verify(estimateRepository, never()).save(any());

        // 10: Neuberechnung über alle 10, previousMedian wird geführt.
        stubObservations("baugenehmigung", 50, 55, 58, 60, 62, 65, 66, 70, 71, 74);
        when(observationRepository.findByCaseId(any())).thenReturn(Optional.empty());
        when(observationRepository.countByCategory("baugenehmigung")).thenReturn(10L);
        service.recordObservation(UUID.randomUUID().toString(), "baugenehmigung", 74);

        EffortEstimateEntity updated = service.aggregate("baugenehmigung").orElseThrow();
        assertEquals(62, updated.getMedianMinutes(), "nearest-rank median of all 10 (5th value)");
        assertEquals(60, updated.getPreviousMedianMinutes(),
                "the previous median is retained for audit");
        assertEquals(10, updated.getSampleCount());
    }

    // ── Beobachtung / Feedback ──

    @Test
    void recordObservation_zeroMinutes_createsNothing() {
        service.recordObservation(UUID.randomUUID().toString(), "gewerbeanmeldung", 0);
        verify(observationRepository, never()).save(any());
    }

    @Test
    void duplicateCompletion_updatesTheSingleObservation() {
        String caseId = UUID.randomUUID().toString();
        EffortObservationEntity existing = new EffortObservationEntity(UUID.fromString(caseId));
        existing.setObservedActiveMinutes(15);
        when(observationRepository.findByCaseId(UUID.fromString(caseId)))
                .thenReturn(Optional.of(existing));
        when(observationRepository.countByCategory("ummeldung")).thenReturn(1L);

        service.recordObservation(caseId, "ummeldung", 18);

        assertEquals(18, existing.getObservedActiveMinutes(),
                "the single per-case observation is updated, not duplicated");
    }

    @Test
    void complexityFeedback_isStored_andDoesNotTouchTheMedian() {
        stubObservations("wohngeld", 40, 41, 42, 43, 44);
        observe(UUID.randomUUID().toString(), "wohngeld", 44, 5);
        EffortEstimateEntity before = service.aggregate("wohngeld").orElseThrow();

        String caseId = UUID.randomUUID().toString();
        EffortObservationEntity observation = new EffortObservationEntity(UUID.fromString(caseId));
        observation.setObservedActiveMinutes(42);
        when(observationRepository.findByCaseId(UUID.fromString(caseId)))
                .thenReturn(Optional.of(observation));

        service.setComplexityFeedback(caseId, 8);

        assertEquals(8, observation.getComplexityFeedback(), "feedback is stored on the observation");
        EffortEstimateEntity after = service.aggregate("wohngeld").orElseThrow();
        assertEquals(before.getMedianMinutes(), after.getMedianMinutes(),
                "subjective complexity never feeds the median");
    }

    @Test
    void complexityFeedback_outOfRangeOrNull_isIgnored() {
        String caseId = UUID.randomUUID().toString();
        service.setComplexityFeedback(caseId, 11);
        service.setComplexityFeedback(caseId, 0);
        service.setComplexityFeedback(caseId, null);
        verify(observationRepository, never()).save(any());

        // Gültiger Wert wird gespeichert.
        EffortObservationEntity observation = new EffortObservationEntity(UUID.fromString(caseId));
        when(observationRepository.findByCaseId(UUID.fromString(caseId)))
                .thenReturn(Optional.of(observation));
        service.setComplexityFeedback(caseId, 5);
        assertEquals(5, observation.getComplexityFeedback());
    }

    // ── Fallback-Kategorie ──

    @Test
    void unknownCategory_fallsBackToAllgemein() {
        assertEquals(30, new ProcessingTimeConfig().baselineMinutes("völlig unbekannt"),
                "unknown categories use the allgemein baseline");
    }
}
