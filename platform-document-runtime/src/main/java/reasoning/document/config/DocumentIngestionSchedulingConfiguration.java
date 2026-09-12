package reasoning.document.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables Spring's scheduled task execution for document ingestion polling. */
@Configuration
@EnableScheduling
public class DocumentIngestionSchedulingConfiguration {
}
