package reasoning.ai.model;

import java.util.UUID;

/**
 * A request for AI inference, decoupled from any specific provider.
 * Contains the prompt, model preference, and capability requirements.
 */
public record InferenceRequest(
        String prompt,
        String preferredModel,
        String preferredProvider,
        ModelCapability.CapabilityRequest requiredCapability,
        String promptTemplateId,
        int promptTemplateVersion,
        UUID correlationId,
        String actorId
) {
    public InferenceRequest {
        if (requiredCapability == null) requiredCapability = ModelCapability.CapabilityRequest.CHAT;
    }

    /** Creates a simple chat inference request. */
    public static InferenceRequest forChat(String prompt, String preferredModel) {
        return new InferenceRequest(prompt, preferredModel, null,
                ModelCapability.CapabilityRequest.CHAT, null, 0, null, "system");
    }
}
