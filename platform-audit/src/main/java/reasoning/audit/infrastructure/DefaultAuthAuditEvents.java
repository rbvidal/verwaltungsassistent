package reasoning.audit.infrastructure;

import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditService;
import reasoning.audit.api.AuditSource;
import reasoning.common.audit.AuditSubject;
import reasoning.audit.api.AuthAuditEvents;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Default implementation of {@link AuthAuditEvents} sourcing from the "auth" module. */
@Component
public class DefaultAuthAuditEvents implements AuthAuditEvents {

    private final AuditService auditService;

    public DefaultAuthAuditEvents(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void emit(AuditEventType eventType, AuditSubject subject, JsonNode metadata) {
        auditService.emit(eventType, subject, AuditSource.of("auth", metadata));
    }
}
