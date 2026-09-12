package verwaltungsassistent.web.service;

import reasoning.auth.api.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts currently active event-demo participants.
 *
 * <p>Two signals are combined: the Spring Security {@link SessionRegistry}
 * supplies the set of live authenticated sessions, and per-request activity
 * (recorded by {@link verwaltungsassistent.web.config.LiveDemoActivityFilter})
 * decides which of those users were active within the configured inactivity
 * window. The activity filter deliberately excludes the /live-demo/status
 * polling endpoint so the banner's own refresh can never keep an idle user
 * counted; distinct users are counted once regardless of session count.</p>
 */
@Service
public class LiveDemoStatusService {

    private static final Logger log = LoggerFactory.getLogger(LiveDemoStatusService.class);

    public enum Level {
        GREEN, YELLOW, RED
    }

    /** Presentation state for the demo banner. */
    public record Status(Level level, int count) {}

    private final SessionRegistry sessionRegistry;
    private final Clock clock;
    private final Duration inactivityWindow;
    private final int moderateThreshold;
    private final int highThreshold;
    private final Map<String, Instant> lastActivityByEmail;

    @Autowired
    public LiveDemoStatusService(SessionRegistry sessionRegistry,
                                 @Value("${app.live-demo.inactivity-window:PT5M}") Duration inactivityWindow,
                                 @Value("${app.live-demo.moderate-threshold:5}") int moderateThreshold,
                                 @Value("${app.live-demo.high-threshold:10}") int highThreshold) {
        this(sessionRegistry, Clock.systemDefaultZone(), inactivityWindow, moderateThreshold, highThreshold,
                new ConcurrentHashMap<>());
    }

    LiveDemoStatusService(SessionRegistry sessionRegistry, Clock clock,
                          Duration inactivityWindow, int moderateThreshold, int highThreshold) {
        this(sessionRegistry, clock, inactivityWindow, moderateThreshold, highThreshold,
                new ConcurrentHashMap<>());
    }

    LiveDemoStatusService(SessionRegistry sessionRegistry, Clock clock,
                          Duration inactivityWindow, int moderateThreshold, int highThreshold,
                          Map<String, Instant> lastActivityByEmail) {
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
        this.inactivityWindow = inactivityWindow;
        this.moderateThreshold = moderateThreshold;
        this.highThreshold = highThreshold;
        this.lastActivityByEmail = lastActivityByEmail;
    }

    /** Records real user activity (called by the demo activity filter). */
    public void touch(String email) {
        lastActivityByEmail.put(email, clock.instant());
    }

    /** Current demo status: active participant count and load level. */
    public Status status() {
        int count = activeUserCount();
        return new Status(levelFor(count), count);
    }

    /** Distinct authenticated users with at least one recently active session. */
    public int activeUserCount() {
        Instant cutoff = clock.instant().minus(inactivityWindow);
        lastActivityByEmail.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        Set<String> liveEmails = new HashSet<>();
        try {
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                Object user = principal instanceof Authentication auth ? auth.getPrincipal() : principal;
                if (user instanceof AuthenticatedUser authenticated) {
                    liveEmails.add(authenticated.email());
                }
            }
        } catch (Exception e) {
            log.warn("Could not read active sessions: {}", e.getMessage());
        }
        return (int) liveEmails.stream().filter(lastActivityByEmail::containsKey).count();
    }

    /** Green up to the moderate threshold, yellow above it, red above the high threshold. */
    public Level levelFor(int count) {
        if (count > highThreshold) {
            return Level.RED;
        }
        if (count > moderateThreshold) {
            return Level.YELLOW;
        }
        return Level.GREEN;
    }
}
