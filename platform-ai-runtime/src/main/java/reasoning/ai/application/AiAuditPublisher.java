package reasoning.ai.application;

import reasoning.audit.api.AiAuditEvents;
import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditMetadata;
import reasoning.common.audit.AuditSubject;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Publishes AI audit events through the platform audit module. */
@Component
public class AiAuditPublisher {

    private final AiAuditEvents auditEvents;

    public AiAuditPublisher(AiAuditEvents auditEvents) {
        this.auditEvents = auditEvents;
    }

    /** Emits an AI audit event with actor, tenant, event type, entity ID, and metadata. */
    public void emit(String actorId, String tenantId, AuditEventType eventType, String entityId, Map<String, String> metadata) {
        auditEvents.emit(
                eventType,
                new AuditSubject(actorId, tenantId, "AI_INFERENCE", entityId),
                AuditMetadata.from(metadata));
    }
}
