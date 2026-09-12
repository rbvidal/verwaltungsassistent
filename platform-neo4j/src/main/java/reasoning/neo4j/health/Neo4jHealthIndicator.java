package reasoning.neo4j.health;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Health indicator for Neo4j graph database.
 *
 * <p>Verifies connectivity to the Neo4j instance. If Neo4j is not
 * configured (no platform.neo4j.uri property), reports UNKNOWN.
 * If configured but unreachable, reports DOWN.
 */
@Component
@ConditionalOnProperty(name = "platform.neo4j.uri")
public class Neo4jHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(Neo4jHealthIndicator.class);

    private final ObjectProvider<Driver> driverProvider;

    public Neo4jHealthIndicator(ObjectProvider<Driver> driverProvider) {
        this.driverProvider = driverProvider;
    }

    @Override
    public Health health() {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return Health.unknown()
                    .withDetail("reason", "Neo4j driver not configured")
                    .build();
        }

        try {
            driver.verifyConnectivity();
            return Health.up()
                    .withDetail("uri", extractUri(driver))
                    .withDetail("connected", true)
                    .build();
        } catch (Exception e) {
            log.warn("Neo4j health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("connected", false)
                    .build();
        }
    }

    private String extractUri(Driver driver) {
        try {
            // Neo4j driver doesn't expose its URI directly; get from session
            try (var session = driver.session()) {
                var result = session.run("CALL dbms.cluster.overview()");
                if (result.hasNext()) {
                    return result.next().get("addresses").toString();
                }
            }
        } catch (Exception ignored) {}
        return "neo4j://configured-uri";
    }
}
