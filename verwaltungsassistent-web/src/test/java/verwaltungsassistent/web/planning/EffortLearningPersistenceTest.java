package verwaltungsassistent.web.planning;

import verwaltungsassistent.web.planning.persistence.EffortEstimateEntity;
import verwaltungsassistent.web.planning.persistence.EffortObservationEntity;
import verwaltungsassistent.web.planning.persistence.JpaEffortEstimateRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2A: Persistenz-Roundtrip der empirischen Bearbeitungszeiten
 * (echte PostgreSQL-Repositories): Beobachtung je Fall, Feedback,
 * Statistik-Aggregat mit Audit-Metadaten.
 */
@SpringBootTest
@ActiveProfiles("dev")
class EffortLearningPersistenceTest {

    @Autowired
    private JpaEffortObservationRepository observationRepository;
    @Autowired
    private JpaEffortEstimateRepository estimateRepository;

    @Test
    void observationAndEstimate_roundTrip() {
        UUID caseId = UUID.randomUUID();
        String category = "testkategorie-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            EffortObservationEntity observation = new EffortObservationEntity(caseId);
            observation.setCategory(category);
            observation.setObservedActiveMinutes(36);
            observation.setCompletedAt(java.time.Instant.now());
            observationRepository.save(observation);

            Optional<EffortObservationEntity> loaded = observationRepository.findByCaseId(caseId);
            assertTrue(loaded.isPresent());
            assertEquals(36, loaded.get().getObservedActiveMinutes());
            assertEquals(1L, observationRepository.countByCategory(category));

            // Feedback landet auf derselben Beobachtung.
            loaded.get().setComplexityFeedback(8);
            observationRepository.save(loaded.get());
            assertEquals(8, observationRepository.findByCaseId(caseId).orElseThrow()
                    .getComplexityFeedback());

            // Statistik-Aggregat mit Audit-Metadaten.
            EffortEstimateEntity estimate = new EffortEstimateEntity(category);
            estimate.setSampleCount(1);
            estimate.setMedianMinutes(36);
            estimate.setP75Minutes(36);
            estimate.setP90Minutes(36);
            estimate.setPreviousMedianMinutes(null);
            estimate.setLastUpdated(java.time.Instant.now());
            estimate.setSource("EMPIRICAL");
            estimateRepository.save(estimate);

            EffortEstimateEntity reloaded = estimateRepository.findByCategory(category).orElseThrow();
            assertEquals(36, reloaded.getMedianMinutes());
            assertEquals("EMPIRICAL", reloaded.getSource());
            assertEquals(1, reloaded.getSampleCount());
        } finally {
            estimateRepository.deleteById(category);
            observationRepository.deleteByCaseId(caseId);
        }
    }
}
