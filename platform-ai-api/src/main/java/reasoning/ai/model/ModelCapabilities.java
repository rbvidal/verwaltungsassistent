package reasoning.ai.model;

/**
 * Describes the capabilities of an AI model, including provider, context window size, and feature support.
 */
public record ModelCapabilities(
        String provider,
        String model,
        int contextWindowTokens,
        boolean supportsSystemMessages,
        boolean supportsCitations,
        boolean localCompatible
) {
}
