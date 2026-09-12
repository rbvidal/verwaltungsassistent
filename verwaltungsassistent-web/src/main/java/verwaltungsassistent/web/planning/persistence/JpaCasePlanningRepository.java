package verwaltungsassistent.web.planning.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistenter Planungsstand pro Fall (ein Eintrag je Fall). */
@Transactional
public interface JpaCasePlanningRepository extends JpaRepository<CasePlanningEntity, UUID> {

    Optional<CasePlanningEntity> findByCaseId(UUID caseId);

    List<CasePlanningEntity> findByCaseIdIn(Collection<UUID> caseIds);

    /** Phase 2 (globaler Arbeitspool): alle als bearbeitbar eingestuften Fälle. */
    List<CasePlanningEntity> findByWorkableTrue();

    void deleteByCaseId(UUID caseId);
}
