package reasoning.ai.health;

import reasoning.ai.api.ChatCompletionProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/** Aggregated health check for all configured AI chat providers. */
@Component
public class ProviderHealthIndicator implements HealthIndicator {

    private final List<ChatCompletionProvider> providers;

    public ProviderHealthIndicator(List<ChatCompletionProvider> providers) {
        this.providers = providers;
    }

    @Override
    public Health health() {
        if (providers.isEmpty()) {
            return Health.down().withDetail("providers", "none configured").build();
        }
        var builder = Health.up();
        for (var p : providers) {
            builder.withDetail(p.providerName(), p.isAvailable() ? "available" : "unavailable");
        }
        return builder.build();
    }
}
