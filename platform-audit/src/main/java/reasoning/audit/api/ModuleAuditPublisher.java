package reasoning.audit.api;
import reasoning.common.audit.AuditEventType;
import reasoning.common.audit.AuditSubject;

import com.fasterxml.jackson.databind.JsonNode;

/** Base interface for module-specific audit publishers. */
public interface ModuleAuditPublisher {
    /** Emits an audit event with the given type, subject, and JSON metadata. */
    void emit(AuditEventType eventType, AuditSubject subject, JsonNode metadata);
}
