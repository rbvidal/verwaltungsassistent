package reasoning.ai.model;

import java.util.List;

/**
 * Describes the capabilities of an AI model.
 * Used by the orchestration layer for intelligent model and provider selection.
 *
 * <p>Every model registered in the {@link reasoning.ai.api.ModelCapabilityRegistry}
 * exposes its features, limits, and estimated performance characteristics.
 * The {@link reasoning.ai.api.ProviderRouter} uses this information
 * to make routing decisions based on requested capabilities.
 */
public record ModelCapability(
        String modelName,
        String provider,
        boolean supportsStreaming,
        boolean supportsVision,
        boolean supportsJson,
        boolean supportsToolCalling,
        boolean supportsEmbeddings,
        boolean supportsReasoning,
        boolean supportsStructuredOutput,
        int maxContextWindow,
        int maxOutputTokens,
        int estimatedLatencyMs,
        double estimatedCostPer1kTokens,
        double recommendedTemperature,
        List<String> preferredUseCases,
        List<String> tags
) {
    public ModelCapability {
        preferredUseCases = preferredUseCases != null ? List.copyOf(preferredUseCases) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
    }

    /** Returns true if this model can fulfill the requested capability. */
    public boolean supports(CapabilityRequest request) {
        return switch (request) {
            case CHAT -> true;
            case STREAMING -> supportsStreaming;
            case VISION -> supportsVision;
            case JSON_OUTPUT -> supportsJson;
            case TOOL_CALLING -> supportsToolCalling;
            case EMBEDDING -> supportsEmbeddings;
            case REASONING -> supportsReasoning;
            case STRUCTURED_OUTPUT -> supportsStructuredOutput;
        };
    }

    /** Named capabilities that can be requested by the orchestration layer. */
    public enum CapabilityRequest {
        CHAT, STREAMING, VISION, JSON_OUTPUT, TOOL_CALLING,
        EMBEDDING, REASONING, STRUCTURED_OUTPUT
    }
}
