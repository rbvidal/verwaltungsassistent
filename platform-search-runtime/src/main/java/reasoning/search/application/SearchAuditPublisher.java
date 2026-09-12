package reasoning.search.application;

import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditMetadata;
import reasoning.common.audit.AuditSubject;
import reasoning.audit.api.SearchAuditEvents;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Publishes search-related audit events through the audit module. */
@Component
public class SearchAuditPublisher {

    private final SearchAuditEvents auditEvents;

    public SearchAuditPublisher(SearchAuditEvents auditEvents) {
        this.auditEvents = auditEvents;
    }

    /** Emits a search audit event with actor, tenant, event type, entity ID, and metadata. */
    public void emit(String actorId, String tenantId, AuditEventType eventType, String entityId, Map<String, String> metadata) {
        auditEvents.emit(
                eventType,
                new AuditSubject(actorId, tenantId, "RETRIEVAL", entityId),
                AuditMetadata.from(metadata));
    }
}
