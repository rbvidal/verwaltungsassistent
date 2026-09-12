package reasoning.ai.api;

import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;

/**
 * The top-level facade for answering AI questions.
 */
public interface AiFacade {
    /** Answers a question given an {@code AiRequest} and returns an {@code AiResponse}. */
    AiResponse answer(AiRequest request);
}
