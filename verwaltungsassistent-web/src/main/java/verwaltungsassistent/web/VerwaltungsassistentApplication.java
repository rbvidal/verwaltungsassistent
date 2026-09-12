package verwaltungsassistent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = {
        "verwaltungsassistent.web",
        "reasoning.auth",
        "reasoning.audit",
        "reasoning.document",
        "reasoning.workspace",
        "reasoning.ai",
        "reasoning.search",
        "reasoning.neo4j"
})
@EntityScan(basePackages = {
        "reasoning.auth.infrastructure.persistence",
        "reasoning.document.infrastructure.persistence",
        "reasoning.workspace.api",
        "reasoning.search.infrastructure.persistence",
        "verwaltungsassistent.web.analysis.persistence",
        "verwaltungsassistent.web.geo",
        "verwaltungsassistent.web.planning.persistence"
})
@EnableJpaRepositories(basePackages = {
        "reasoning.auth.infrastructure.persistence",
        "reasoning.document.infrastructure.persistence",
        "reasoning.workspace.infrastructure.persistence",
        "reasoning.search.infrastructure.persistence",
        "verwaltungsassistent.web.analysis.persistence",
        "verwaltungsassistent.web.geo",
        "verwaltungsassistent.web.planning.persistence"
})
@ConfigurationPropertiesScan(basePackages = {
        "reasoning.search",
        "reasoning.ai",
        "verwaltungsassistent.web.config"
})
public class VerwaltungsassistentApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerwaltungsassistentApplication.class, args);
    }
}
