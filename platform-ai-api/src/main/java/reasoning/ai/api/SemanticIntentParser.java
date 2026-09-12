package reasoning.ai.api;

import reasoning.ai.model.StructuredIntent;

/**
 * Extracts structured semantic intent/parameters from a natural-language query.
 *
 * <p>This is the boundary between natural language and the deterministic
 * Verwaltungsassistent core. The implementation may use regex (current behavior) or an LLM
 * (experimental). The core consumes only the structured output.
 */
public interface SemanticIntentParser {

    /** Extracts structured intent from a natural-language question. */
    StructuredIntent parse(String question);
}
