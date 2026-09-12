package verwaltungsassistent.web.config;

import reasoning.ai.application.DefaultGroundingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provides German-language resources to the generic Verwaltungsassistent core.
 * The core does not assume any specific language; the municipal
 * application supplies German stop words via configuration.
 */
@Configuration
public class GermanLanguageConfig {

    private final DefaultGroundingService groundingService;

    @Value("${platform.ai.grounding.stop-words:}")
    private String stopWordsCsv;

    public GermanLanguageConfig(DefaultGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    @PostConstruct
    void configureStopWords() {
        Set<String> words = stopWordsCsv.isBlank() ? Set.of() :
                Arrays.stream(stopWordsCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        groundingService.setStopWords(words);
    }
}
