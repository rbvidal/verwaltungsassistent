package reasoning.audit.api;
import reasoning.common.audit.AuditEventType;
import reasoning.common.audit.AuditSubject;

/** Service interface for emitting and querying audit events. */
public interface AuditService {
    /** Emits a pre-constructed audit event. */
    void emit(AuditEvent event);

    /** Emits an audit event from its type, subject, and source components. */
    void emit(AuditEventType eventType, AuditSubject subject, AuditSource source);

    /** Queries audit events with filtering and pagination. */
    AuditEventPage query(AuditQuery query);
}
