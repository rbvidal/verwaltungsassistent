package reasoning.workspace.application;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import reasoning.workspace.api.StructuredKnowledgeItemEntity;
import reasoning.workspace.infrastructure.persistence.JpaStructuredKnowledgeItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA-backed {@link StructuredKnowledgeStore}. Maps the domain record to the
 * persistence entity and back; status transitions go through the repository
 * so the lifecycle is the single source of truth.
 */
@Component
public class JpaStructuredKnowledgeStore implements StructuredKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(JpaStructuredKnowledgeStore.class);

    private final JpaStructuredKnowledgeItemRepository repository;

    public JpaStructuredKnowledgeStore(JpaStructuredKnowledgeItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<StructuredKnowledgeItem> findActive(String kind, String domain) {
        return repository.findByStatusAndKindAndDomain(
                        StructuredKnowledgeStatus.ACTIVE.name(), kind, domain).stream()
                .map(JpaStructuredKnowledgeStore::toModel)
                .toList();
    }

    @Override
    public List<StructuredKnowledgeItem> findBySource(UUID documentId, int version) {
        return repository.findBySourceDocumentIdAndSourceDocumentVersion(documentId, version).stream()
                .map(JpaStructuredKnowledgeStore::toModel)
                .toList();
    }

    @Override
    public List<StructuredKnowledgeItem> findByStatus(StructuredKnowledgeStatus status) {
        return repository.findByStatus(status.name()).stream()
                .map(JpaStructuredKnowledgeStore::toModel)
                .toList();
    }

    @Override
    public Optional<StructuredKnowledgeItem> findById(UUID id) {
        return repository.findById(id).map(JpaStructuredKnowledgeStore::toModel);
    }

    @Override
    public StructuredKnowledgeItem save(StructuredKnowledgeItem item) {
        return toModel(repository.save(toEntity(item)));
    }

    @Override
    public List<StructuredKnowledgeItem> saveAll(List<StructuredKnowledgeItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return repository.saveAll(items.stream().map(JpaStructuredKnowledgeStore::toEntity).toList())
                .stream().map(JpaStructuredKnowledgeStore::toModel).toList();
    }

    @Override
    public void markStatus(UUID id, StructuredKnowledgeStatus status) {
        repository.findById(id).ifPresent(entity -> {
            entity.setStatus(status.name());
            repository.save(entity);
            log.info("Structured knowledge item {} → {}", id, status);
        });
    }

    @Override
    public int supersedeBySource(UUID documentId, int version, String extractedBy) {
        List<StructuredKnowledgeItemEntity> active = repository
                .findBySourceDocumentIdAndSourceDocumentVersion(documentId, version).stream()
                .filter(e -> StructuredKnowledgeStatus.ACTIVE.name().equals(e.getStatus()))
                .toList();
        for (StructuredKnowledgeItemEntity entity : active) {
            entity.setStatus(StructuredKnowledgeStatus.SUPERSEDED.name());
            repository.save(entity);
        }
        if (!active.isEmpty()) {
            log.info("Superseded {} ACTIVE structured knowledge item(s) from document {}@v{}",
                    active.size(), documentId, version);
        }
        return active.size();
    }

    // ── Mapping ──

    static StructuredKnowledgeItem toModel(StructuredKnowledgeItemEntity e) {
        return new StructuredKnowledgeItem(
                e.getId(),
                StructuredKnowledgeKind.valueOf(e.getKind()),
                e.getDomain(),
                e.getKey(),
                e.getPayloadJson(),
                StructuredKnowledgeStatus.valueOf(e.getStatus()),
                e.getEffectiveFrom(),
                e.getEffectiveUntil(),
                e.getSourceDocumentId(),
                e.getSourceDocumentVersion(),
                e.getSourcePage(),
                e.getSourceChunkIndex(),
                e.getSourceExcerpt(),
                e.getExtractionConfidence(),
                e.getExtractedBy(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getSupersededById());
    }

    static StructuredKnowledgeItemEntity toEntity(StructuredKnowledgeItem i) {
        return new StructuredKnowledgeItemEntity(
                i.id() != null ? i.id() : UUID.randomUUID(),
                i.kind().name(),
                i.domain(),
                i.key(),
                i.payloadJson(),
                i.status() != null ? i.status().name() : StructuredKnowledgeStatus.CANDIDATE.name(),
                i.effectiveFrom(),
                i.effectiveUntil(),
                i.sourceDocumentId(),
                i.sourceDocumentVersion(),
                i.sourcePage(),
                i.sourceChunkIndex(),
                i.sourceExcerpt(),
                i.extractionConfidence(),
                i.extractedBy(),
                i.supersededById());
    }
}
