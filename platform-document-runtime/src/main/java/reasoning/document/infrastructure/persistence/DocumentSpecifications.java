package reasoning.document.infrastructure.persistence;

import reasoning.document.api.DocumentFilter;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

/** Factory for building JPA specifications from {@link DocumentFilter} criteria. */
public final class DocumentSpecifications {

    private DocumentSpecifications() {
    }

    /** Builds a combined specification from all filter fields. */
    public static Specification<DocumentEntity> from(DocumentFilter filter) {
        return Specification.where(status(filter))
                .and(type(filter))
                .and(category(filter))
                .and(tag(filter))
                .and(tenant(filter))
                .and(createdFrom(filter))
                .and(createdTo(filter));
    }

    private static Specification<DocumentEntity> status(DocumentFilter filter) {
        return (root, query, builder) -> filter.status() == null ? null : builder.equal(root.get("status"), filter.status());
    }

    private static Specification<DocumentEntity> type(DocumentFilter filter) {
        return (root, query, builder) -> filter.type() == null ? null : builder.equal(root.get("type"), filter.type());
    }

    private static Specification<DocumentEntity> category(DocumentFilter filter) {
        return (root, query, builder) -> hasText(filter.category()) ? builder.equal(root.get("category"), filter.category().trim()) : null;
    }

    private static Specification<DocumentEntity> tag(DocumentFilter filter) {
        return (root, query, builder) -> {
            if (!hasText(filter.tag())) {
                return null;
            }
            if (query != null) {
                query.distinct(true);
            }
            return builder.equal(root.joinSet("tags", JoinType.INNER), filter.tag().trim().toLowerCase());
        };
    }

    private static Specification<DocumentEntity> tenant(DocumentFilter filter) {
        return (root, query, builder) -> hasText(filter.tenantId()) ? builder.equal(root.get("tenantId"), filter.tenantId().trim()) : null;
    }

    private static Specification<DocumentEntity> createdFrom(DocumentFilter filter) {
        return (root, query, builder) -> filter.createdFrom() == null ? null : builder.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom());
    }

    private static Specification<DocumentEntity> createdTo(DocumentFilter filter) {
        return (root, query, builder) -> filter.createdTo() == null ? null : builder.lessThanOrEqualTo(root.get("createdAt"), filter.createdTo());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
