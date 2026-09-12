package reasoning.audit.infrastructure;

import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditService;
import reasoning.audit.api.AuditSource;
import reasoning.common.audit.AuditSubject;
import reasoning.audit.api.WorkflowAuditEvents;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Default implementation of {@link WorkflowAuditEvents} sourcing from the "workflow" module. */
@Component
public class DefaultWorkflowAuditEvents implements WorkflowAuditEvents {

    private final AuditService auditService;

    public DefaultWorkflowAuditEvents(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void emit(AuditEventType eventType, AuditSubject subject, JsonNode metadata) {
        auditService.emit(eventType, subject, AuditSource.of("workflow", metadata));
    }
}
