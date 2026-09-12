package reasoning.ai.model;

import java.util.List;

/**
 * Holds the conversation context for an AI request, including message history
 * and correlation identifiers for tracing.
 */
public record AiConversationContext(
        List<AiMessage> messages,
        String actorId,
        String tenantId,
        String correlationId,
        String requestId
) {
    public AiConversationContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
