package reasoning.audit.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Identifies the source module and associated metadata for an audit event. */
public record AuditSource(
        String module,
        JsonNode metadata
) {
    /** Creates an audit source with the given module name and metadata, defaulting to empty metadata if null. */
    public static AuditSource of(String module, JsonNode metadata) {
        return new AuditSource(module, metadata == null ? AuditMetadata.empty() : metadata);
    }
}
