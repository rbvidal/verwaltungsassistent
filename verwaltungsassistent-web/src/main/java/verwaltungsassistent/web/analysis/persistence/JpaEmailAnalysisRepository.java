package verwaltungsassistent.web.analysis.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Repository for persisted citizen e-mail analyses, scoped to the owning user. */
public interface JpaEmailAnalysisRepository extends JpaRepository<EmailAnalysisEntity, UUID> {

    List<EmailAnalysisEntity> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    List<EmailAnalysisEntity> findByUserEmailAndQuestionTextOrderByCreatedAtDesc(
            String userEmail, String questionText);
}
