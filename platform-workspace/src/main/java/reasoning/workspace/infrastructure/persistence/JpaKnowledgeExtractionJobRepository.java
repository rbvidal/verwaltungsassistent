package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.KnowledgeExtractionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JPA repository for {@link KnowledgeExtractionJobEntity}. */
public interface JpaKnowledgeExtractionJobRepository extends JpaRepository<KnowledgeExtractionJobEntity, UUID> {

    List<KnowledgeExtractionJobEntity> findTop5ByStatusOrderByCreatedAtAsc(String status);

    Optional<KnowledgeExtractionJobEntity> findByDocumentIdAndDocumentVersion(UUID documentId, int version);
}
