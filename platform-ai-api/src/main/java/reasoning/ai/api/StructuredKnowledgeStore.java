package reasoning.ai.api;

import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for generic structured knowledge. The deterministic
 * engine and registry depend on this interface — never on the JPA layer.
 * Implementations live in the persistence-bearing module.
 */
public interface StructuredKnowledgeStore {

    /** All ACTIVE items of a kind within a domain. */
    List<StructuredKnowledgeItem> findActive(String kind, String domain);

    /** All items of any status extracted from a specific document version. */
    List<StructuredKnowledgeItem> findBySource(UUID documentId, int version);

    /** All items in a given status (e.g. CANDIDATE for review). */
    List<StructuredKnowledgeItem> findByStatus(StructuredKnowledgeStatus status);

    Optional<StructuredKnowledgeItem> findById(UUID id);

    StructuredKnowledgeItem save(StructuredKnowledgeItem item);

    List<StructuredKnowledgeItem> saveAll(List<StructuredKnowledgeItem> items);

    void markStatus(UUID id, StructuredKnowledgeStatus status);

    /**
     * Marks every ACTIVE item extracted from the given document version as
     * SUPERSEDED (a newer version has been extracted). Returns the count of
     * superseded items. History is retained, never deleted.
     */
    int supersedeBySource(UUID documentId, int version, String extractedBy);
}
