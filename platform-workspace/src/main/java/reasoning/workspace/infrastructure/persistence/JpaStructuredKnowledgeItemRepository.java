package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.StructuredKnowledgeItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** JPA repository for {@link StructuredKnowledgeItemEntity}. */
public interface JpaStructuredKnowledgeItemRepository extends JpaRepository<StructuredKnowledgeItemEntity, UUID> {

    List<StructuredKnowledgeItemEntity> findByStatusAndKindAndDomain(String status, String kind, String domain);

    List<StructuredKnowledgeItemEntity> findByStatus(String status);

    List<StructuredKnowledgeItemEntity> findBySourceDocumentIdAndSourceDocumentVersion(UUID documentId, int version);

    List<StructuredKnowledgeItemEntity> findByStatusAndKindAndDomainAndKey(String status, String kind, String domain, String key);
}
