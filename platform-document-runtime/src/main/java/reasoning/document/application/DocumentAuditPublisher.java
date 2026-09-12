package reasoning.document.application;

import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditMetadata;
import reasoning.common.audit.AuditSubject;
import reasoning.audit.api.DocumentAuditEvents;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Publishes document-related audit events through the audit module. */
@Component
public class DocumentAuditPublisher {

    private final DocumentAuditEvents auditEvents;

    public DocumentAuditPublisher(DocumentAuditEvents auditEvents) {
        this.auditEvents = auditEvents;
    }

    /** Emits a document audit event with actor, tenant, event type, document ID, and metadata. */
    public void emit(String actorId, String tenantId, AuditEventType eventType, UUID documentId, Map<String, String> metadata) {
        auditEvents.emit(
                eventType,
                new AuditSubject(actorId, tenantId, "DOCUMENT", documentId.toString()),
                AuditMetadata.from(metadata));
    }
}
