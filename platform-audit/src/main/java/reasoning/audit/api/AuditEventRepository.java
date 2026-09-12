package reasoning.audit.api;

/** Repository SPI for appending and querying audit events. */
public interface AuditEventRepository {
    /** Persists a single audit event. */
    void append(AuditEvent event);

    /** Queries audit events with filtering and pagination. */
    AuditEventPage find(AuditQuery query);
}
