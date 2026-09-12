package verwaltungsassistent.web.planning.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Empirische aktive Bearbeitungszeit-Beobachtungen abgeschlossener Fälle. */
@Transactional
public interface JpaEffortObservationRepository extends JpaRepository<EffortObservationEntity, UUID> {

    Optional<EffortObservationEntity> findByCaseId(UUID caseId);

    long countByCategory(String category);

    List<EffortObservationEntity> findByCategoryOrderByCompletedAt(String category);

    void deleteByCaseId(UUID caseId);
}
