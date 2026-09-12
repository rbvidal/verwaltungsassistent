package verwaltungsassistent.web.config;

import verwaltungsassistent.web.service.DemoDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Startup seeding of the deterministic incoming e-mail work queue (dev/demo
 * profiles). Delegates to {@link DemoDataService#seedEmailQueueIfEmpty()} — the
 * controlled e-mail catalog lives in exactly one place, and the queue is only
 * created when it is empty (a manual reset can freely change it afterwards).
 */
@Component
@Profile({"dev", "demo"})
public class DemoEmailSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoEmailSeeder.class);

    private final DemoDataService demoDataService;

    public DemoEmailSeeder(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @Override
    public void run(String... args) {
        try {
            demoDataService.seedEmailQueueIfEmpty();
        } catch (Exception e) {
            log.warn("Incoming e-mail seeding failed: {}", e.getMessage());
        }
    }
}
