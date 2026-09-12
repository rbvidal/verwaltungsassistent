package verwaltungsassistent.web.config;

import reasoning.search.api.QueryIntentClassifier;
import reasoning.search.application.KeywordIntentClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides search infrastructure beans not auto-discovered by component scanning.
 * Mirrors the assembly-level wiring in platform-api's SearchInfrastructureConfig
 * without the platform-api-specific ingestion beans.
 */
@Configuration
public class SearchInfrastructureConfig {

    @Bean
    public QueryIntentClassifier queryIntentClassifier() {
        return new KeywordIntentClassifier();
    }
}
