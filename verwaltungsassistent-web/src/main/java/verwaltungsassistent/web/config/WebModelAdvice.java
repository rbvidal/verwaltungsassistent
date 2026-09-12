package verwaltungsassistent.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ResponseStatusException;

@ControllerAdvice
public class WebModelAdvice {

    private static final Logger log = LoggerFactory.getLogger(WebModelAdvice.class);

    private final Environment env;

    public WebModelAdvice(Environment env) {
        this.env = env;
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getServletPath();
    }

    /** Exposes demo mode flag to templates — placeholder nav items are hidden in demo mode. */
    /** Exposes the configured inactivity timeout for the client-side warning/countdown. */
    @ModelAttribute("inactivityTimeoutSeconds")
    public Long inactivityTimeoutSeconds(
            @org.springframework.beans.factory.annotation.Value("${app.security.inactivity-timeout-minutes:15}")
            long minutes) {
        return minutes * 60;
    }

    @ModelAttribute("demoMode")
    public boolean demoMode() {
        return "true".equals(env.getProperty("demo.mode", "false"));
    }

    /** Application version (single source: pom.xml, filtered into app.version). */
    @ModelAttribute("appVersion")
    public String appVersion() {
        return env.getProperty("app.version", "dev");
    }

    /** Configured Ollama chat model, shown in the sidebar system-health card. */
    @ModelAttribute("ollamaModel")
    public String ollamaModel() {
        return env.getProperty("platform.ai.ollama.chat-model", "qwen2.5:14b");
    }

    /**
     * Converts UUID parse errors to 404 instead of 500,
     * so malformed case IDs show the friendly error page.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleIllegalArgument(IllegalArgumentException ex, HttpServletResponse response, Model model) {
        if (ex.getMessage() != null && ex.getMessage().contains("Invalid UUID string")) {
            log.debug("Malformed UUID in URL, returning 404: {}", ex.getMessage());
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "error/404";
        }
        log.warn("Unexpected illegal argument: {}", ex.getMessage());
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return "error/500";
    }
}
