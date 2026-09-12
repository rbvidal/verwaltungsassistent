package reasoning.audit.infrastructure;

import reasoning.audit.api.AiAuditEvents;
import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditService;
import reasoning.audit.api.AuditSource;
import reasoning.common.audit.AuditSubject;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Default implementation of {@link AiAuditEvents} sourcing from the "ai" module. */
@Component
public class DefaultAiAuditEvents implements AiAuditEvents {

    private final AuditService auditService;

    public DefaultAiAuditEvents(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void emit(AuditEventType eventType, AuditSubject subject, JsonNode metadata) {
        auditService.emit(eventType, subject, AuditSource.of("ai", metadata));
    }
}
