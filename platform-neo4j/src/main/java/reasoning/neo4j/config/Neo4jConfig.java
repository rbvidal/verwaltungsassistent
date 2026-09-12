package reasoning.neo4j.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures Neo4j driver. Disabled when Neo4j is unavailable. */
@Configuration
@ConditionalOnProperty(name = "platform.neo4j.uri")
public class Neo4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "platform.neo4j")
    public Neo4jProperties neo4jProperties() {
        return new Neo4jProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "platform.neo4j.uri")
    public Driver neo4jDriver(Neo4jProperties props) {
        log.info("Connecting to Neo4j at {}", props.getUri());
        return GraphDatabase.driver(props.getUri(),
                AuthTokens.basic(props.getUsername(), props.getPassword()));
    }
}
