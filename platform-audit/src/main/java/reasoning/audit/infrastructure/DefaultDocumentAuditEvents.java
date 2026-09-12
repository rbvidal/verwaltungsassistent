package reasoning.audit.infrastructure;

import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditService;
import reasoning.audit.api.AuditSource;
import reasoning.common.audit.AuditSubject;
import reasoning.audit.api.DocumentAuditEvents;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Default implementation of {@link DocumentAuditEvents} sourcing from the "document" module. */
@Component
public class DefaultDocumentAuditEvents implements DocumentAuditEvents {

    private final AuditService auditService;

    public DefaultDocumentAuditEvents(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void emit(AuditEventType eventType, AuditSubject subject, JsonNode metadata) {
        auditService.emit(eventType, subject, AuditSource.of("document", metadata));
    }
}
