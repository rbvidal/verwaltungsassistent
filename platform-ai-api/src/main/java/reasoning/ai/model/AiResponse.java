package reasoning.ai.model;

/**
 * The full AI response containing a reasoned answer and inference metadata.
 */
public record AiResponse(
        ReasonedAnswer answer,
        InferenceMetadata metadata
) {
}
