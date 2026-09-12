package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.service.LiveDemoStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Supplies the live-demo status to every rendered page so the banner below
 * the navigation is present without a flash; htmx polling then refreshes it.
 */
@ControllerAdvice
public class LiveDemoModelAdvice {

    private final LiveDemoStatusService statusService;
    private final boolean enabled;

    public LiveDemoModelAdvice(LiveDemoStatusService statusService,
                               @Value("${app.live-demo.enabled:false}") boolean enabled) {
        this.statusService = statusService;
        this.enabled = enabled;
    }

    @ModelAttribute("liveDemo")
    public LiveDemoStatusService.Status liveDemo() {
        return enabled ? statusService.status() : null;
    }
}
