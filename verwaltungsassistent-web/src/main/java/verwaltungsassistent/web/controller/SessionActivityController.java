package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.service.SessionInactivityService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Client-side activity beacon for the inactivity timeout. This path is NOT
 * under /api/** on purpose: /api/** is handled by the JWT filter chain,
 * while browser sessions authenticate via the MVC form-login chain.
 * Only this endpoint updates the inactivity timestamp — background polling
 * and other AJAX requests never do.
 */
@Controller
public class SessionActivityController {

    private final SessionInactivityService inactivityService;

    public SessionActivityController(SessionInactivityService inactivityService) {
        this.inactivityService = inactivityService;
    }

    @PostMapping("/session/activity")
    public ResponseEntity<String> activity(HttpSession session) {
        inactivityService.touch(session);
        return ResponseEntity.ok("ok");
    }
}
