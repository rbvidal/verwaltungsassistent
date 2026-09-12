package reasoning.search.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

import java.util.List;

/** Spring Data repository for {@link DocumentChunkEntity} with specification-based query and bulk delete by document. */
public interface JpaDocumentChunkRepository extends JpaRepository<DocumentChunkEntity, UUID>, JpaSpecificationExecutor<DocumentChunkEntity> {

    /** Deletes all chunks belonging to a document and returns the count of deleted rows. */
    @Modifying
    @Query("delete from DocumentChunkEntity c where c.documentId = :documentId")
    int deleteByDocumentId(UUID documentId);

    /** Finds all chunks for a document ordered by chunk index. */
    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndex(UUID documentId);

    /** Counts the chunks indexed for a document. */
    long countByDocumentId(UUID documentId);
}
