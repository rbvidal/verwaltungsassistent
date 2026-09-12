package reasoning.common.audit;

/** Identifies the actor, tenant, and entity that are the subject of an audit event. */
public record AuditSubject(
        String actorId,
        String tenantId,
        String entityType,
        String entityId
) {
    /** Creates an audit subject with actor, entity type, and entity ID, leaving tenant as null. */
    public static AuditSubject of(String actorId, String entityType, String entityId) {
        return new AuditSubject(actorId, null, entityType, entityId);
    }
}
