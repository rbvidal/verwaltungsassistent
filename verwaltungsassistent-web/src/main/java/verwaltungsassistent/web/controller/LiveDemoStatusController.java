package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.service.LiveDemoStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Fragment endpoint for the live-demo banner. The banner is rendered
 * server-side on every page (see {@link LiveDemoModelAdvice}) and then
 * refreshed in place via htmx polling so the participant count updates
 * without a full page reload.
 */
@Controller
public class LiveDemoStatusController {

    private final LiveDemoStatusService statusService;
    private final boolean enabled;

    public LiveDemoStatusController(LiveDemoStatusService statusService,
                                    @Value("${app.live-demo.enabled:false}") boolean enabled) {
        this.statusService = statusService;
        this.enabled = enabled;
    }

    @GetMapping("/live-demo/status")
    public String banner(Model model) {
        model.addAttribute("liveDemo", enabled ? statusService.status() : null);
        return "live-demo/status :: banner";
    }
}
