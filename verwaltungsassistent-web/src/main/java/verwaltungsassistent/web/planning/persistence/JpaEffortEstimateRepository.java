package verwaltungsassistent.web.planning.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Kumulierte empirische Statistik je Fallart (auditierbar, nur im Intervall aktualisiert). */
@Transactional
public interface JpaEffortEstimateRepository extends JpaRepository<EffortEstimateEntity, String> {

    Optional<EffortEstimateEntity> findByCategory(String category);
}
