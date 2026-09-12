package reasoning.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables AI provider and pipeline configuration properties. */
@Configuration
@EnableConfigurationProperties({AiProviderProperties.class, AiPipelineProperties.class})
public class AiConfig {
}
