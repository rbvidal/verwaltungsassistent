package reasoning.audit.api;
import reasoning.common.audit.AuditEventType;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Immutable representation of a single audit event with actor, entity, request context, and metadata. */
public record AuditEvent(
        UUID id,
        Instant timestamp,
        String actorId,
        String tenantId,
        AuditEventType eventType,
        String entityType,
        String entityId,
        String sourceModule,
        String correlationId,
        String requestId,
        String requestPath,
        String httpMethod,
        String ipAddress,
        JsonNode metadata
) {
}
