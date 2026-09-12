package reasoning.ai.config;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.SemanticIntentParser;
import reasoning.ai.application.LlmSemanticIntentParser;
import reasoning.ai.application.RegexSemanticIntentParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the semantic intent parser based on configuration.
 *
 * <p>Default: regex (current production behavior).
 * Experiment: set {@code platform.ai.ollama.semantic-intent.enabled=true}
 * to use the LLM-based parser.
 */
@Configuration
public class SemanticIntentConfig {

    @Bean
    RegexSemanticIntentParser regexSemanticIntentParser() {
        return new RegexSemanticIntentParser();
    }

    @Bean
    @ConditionalOnProperty(name = "platform.ai.ollama.semantic-intent.enabled", havingValue = "true")
    LlmSemanticIntentParser llmSemanticIntentParser(
            ChatCompletionProvider llmProvider, AiProviderProperties properties) {
        return new LlmSemanticIntentParser(llmProvider, properties);
    }

    @Bean
    @Primary
    SemanticIntentParser semanticIntentParser(
            RegexSemanticIntentParser regexParser,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            LlmSemanticIntentParser llmParser) {
        if (llmParser != null) {
            return llmParser;
        }
        return regexParser;
    }
}
