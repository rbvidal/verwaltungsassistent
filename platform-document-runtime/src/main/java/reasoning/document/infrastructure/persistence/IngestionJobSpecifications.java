package reasoning.document.infrastructure.persistence;

import reasoning.document.api.IngestionJobFilter;
import org.springframework.data.jpa.domain.Specification;

/** Factory for building JPA specifications from {@link IngestionJobFilter} criteria. */
public final class IngestionJobSpecifications {

    private IngestionJobSpecifications() {
    }

    /** Builds a combined specification from the filter's document ID, status, and tenant fields. */
    public static Specification<IngestionJobEntity> from(IngestionJobFilter filter) {
        return Specification.where(documentId(filter)).and(status(filter)).and(tenant(filter));
    }

    private static Specification<IngestionJobEntity> documentId(IngestionJobFilter filter) {
        return (root, query, builder) -> filter.documentId() == null ? null : builder.equal(root.get("documentId"), filter.documentId());
    }

    private static Specification<IngestionJobEntity> status(IngestionJobFilter filter) {
        return (root, query, builder) -> filter.status() == null ? null : builder.equal(root.get("status"), filter.status());
    }

    private static Specification<IngestionJobEntity> tenant(IngestionJobFilter filter) {
        return (root, query, builder) -> hasText(filter.tenantId()) ? builder.equal(root.get("tenantId"), filter.tenantId().trim()) : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
