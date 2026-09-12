package reasoning.search.infrastructure.persistence;

import reasoning.search.model.MetadataFilter;
import reasoning.search.model.SearchFilter;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

/** Factory for building JPA specifications from {@link SearchFilter} criteria, with text search support. */
public final class DocumentChunkSpecifications {

    private DocumentChunkSpecifications() {
    }

    /** Builds a combined specification from all filter fields. */
    public static Specification<DocumentChunkEntity> from(SearchFilter filter) {
        return Specification.where(documentIds(filter))
                .and(documentType(filter))
                .and(category(filter))
                .and(tag(filter))
                .and(source(filter))
                .and(tenant(filter))
                .and(createdFrom(filter))
                .and(createdTo(filter))
                .and(metadata(filter));
    }

    private static Specification<DocumentChunkEntity> documentIds(SearchFilter filter) {
        return (root, query, builder) -> filter == null || filter.documentIds().isEmpty() ? null : root.get("documentId").in(filter.documentIds());
    }

    private static Specification<DocumentChunkEntity> documentType(SearchFilter filter) {
        return (root, query, builder) -> filter == null || filter.documentType() == null ? null : builder.equal(root.get("documentType"), filter.documentType());
    }

    private static Specification<DocumentChunkEntity> category(SearchFilter filter) {
        return (root, query, builder) -> filter == null || !hasText(filter.category()) ? null : builder.equal(root.get("category"), filter.category().trim());
    }

    private static Specification<DocumentChunkEntity> tag(SearchFilter filter) {
        return (root, query, builder) -> {
            if (filter == null || !hasText(filter.tag())) {
                return null;
            }
            if (query != null) {
                query.distinct(true);
            }
            return builder.equal(root.joinSet("tags", JoinType.INNER), filter.tag().trim().toLowerCase());
        };
    }

    private static Specification<DocumentChunkEntity> source(SearchFilter filter) {
        return (root, query, builder) -> filter == null || !hasText(filter.source()) ? null : builder.equal(root.get("source"), filter.source().trim());
    }

    private static Specification<DocumentChunkEntity> tenant(SearchFilter filter) {
        return (root, query, builder) -> filter == null || !hasText(filter.tenantId()) ? null : builder.equal(root.get("tenantId"), filter.tenantId().trim());
    }

    private static Specification<DocumentChunkEntity> createdFrom(SearchFilter filter) {
        return (root, query, builder) -> filter == null || filter.createdFrom() == null ? null : builder.greaterThanOrEqualTo(root.get("documentCreatedAt"), filter.createdFrom());
    }

    private static Specification<DocumentChunkEntity> createdTo(SearchFilter filter) {
        return (root, query, builder) -> filter == null || filter.createdTo() == null ? null : builder.lessThanOrEqualTo(root.get("documentCreatedAt"), filter.createdTo());
    }

    private static Specification<DocumentChunkEntity> metadata(SearchFilter filter) {
        return (root, query, builder) -> {
            if (filter == null || filter.metadata().isEmpty()) {
                return null;
            }
            if (query != null) {
                query.distinct(true);
            }
            var predicate = builder.conjunction();
            for (MetadataFilter metadataFilter : filter.metadata()) {
                var join = root.joinSet("attributes", JoinType.INNER);
                predicate = builder.and(predicate,
                        builder.equal(join.get("key"), metadataFilter.key()),
                        builder.equal(join.get("value"), metadataFilter.value()));
            }
            return predicate;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
