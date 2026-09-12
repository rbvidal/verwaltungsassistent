package reasoning.search.model;

/** Request context for a search operation including actor, tenant, correlation, and request IDs. */
public record SearchRequestContext(
        String actorId,
        String tenantId,
        String correlationId,
        String requestId
) {
}
