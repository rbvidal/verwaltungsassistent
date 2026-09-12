package verwaltungsassistent.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Produktions-Uhr (Systemzeitzone). Der Bean ist bewusst austauschbar:
 * Test-/E2E-Profile (playwright) definieren eine eigene {@link Clock}-Bean
 * mit {@code @Primary}, damit die Bearbeitungszeit-Messung ohne echte
 * Wartezeit deterministisch gesteuert werden kann.
 */
@Configuration
public class AppClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
