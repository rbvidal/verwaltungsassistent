package verwaltungsassistent.web.service;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Server-side inactivity handling: a session is expired when no REAL user
 * activity was recorded for {@code app.security.inactivity-timeout-minutes}.
 * Only the explicit activity beacon ({@code POST /session/activity}) updates
 * the activity timestamp — ordinary requests (including background polling
 * and AJAX) never keep an inactive user alive.
 *
 * <p>Sessions are tracked application-side and invalidated on expiry; the
 * client shows a countdown and redirects to {@code /login?expired=inactivity}
 * when the countdown reaches zero. A session without any recorded activity
 * gets a baseline timestamp on the first check.</p>
 */
@Service
public class SessionInactivityService implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(SessionInactivityService.class);
    public static final String LAST_ACTIVITY_ATTR = "lastUserActivity";
    public static final String TRACKED_ATTR = "inactivityTracked";

    private final ConcurrentMap<String, HttpSession> trackedSessions = new ConcurrentHashMap<>();
    private final long inactivitySeconds;

    public SessionInactivityService(@Value("${app.security.inactivity-timeout-minutes:15}")
                                    long inactivityTimeoutMinutes) {
        this.inactivitySeconds = Math.max(1, inactivityTimeoutMinutes * 60);
    }

    /** The configured inactivity timeout in seconds (for the client countdown). */
    public long timeoutSeconds() {
        return inactivitySeconds;
    }

    /** Registers an authenticated session for inactivity tracking (idempotent). */
    public void register(HttpSession session) {
        if (session != null && session.getAttribute(TRACKED_ATTR) == null) {
            session.setAttribute(TRACKED_ATTR, Boolean.TRUE);
            trackedSessions.put(session.getId(), session);
        }
    }

    /** Records real user activity on the session (called by the activity beacon). */
    public void touch(HttpSession session) {
        if (session != null) {
            register(session);
            session.setAttribute(LAST_ACTIVITY_ATTR, Instant.now());
        }
    }

    /** Expires sessions whose recorded user activity is older than the timeout. */
    @Scheduled(fixedDelayString = "${app.security.inactivity-check-delay-ms:30000}")
    public void expireInactiveSessions() {
        Instant cutoff = Instant.now().minusSeconds(inactivitySeconds);
        int expired = 0;
        for (HttpSession session : trackedSessions.values()) {
            try {
                Object raw = session.getAttribute(LAST_ACTIVITY_ATTR);
                Instant lastActivity;
                if (raw instanceof Instant inst) {
                    lastActivity = inst;
                } else {
                    // Pre-existing session without a baseline: record the
                    // current time so it is not expired immediately.
                    lastActivity = Instant.now();
                    session.setAttribute(LAST_ACTIVITY_ATTR, lastActivity);
                }
                if (lastActivity.isBefore(cutoff)) {
                    log.info("Expiring session {}: no user activity since {}", session.getId(), lastActivity);
                    session.invalidate();
                    trackedSessions.remove(session.getId());
                    expired++;
                }
            } catch (IllegalStateException e) {
                trackedSessions.remove(session.getId());
            } catch (Exception e) {
                log.debug("Could not check session {}: {}", session.getId(), e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Inactivity check expired {} session(s)", expired);
        }
    }

    /** Number of currently tracked sessions (for tests). */
    int trackedCount() {
        return trackedSessions.size();
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        trackedSessions.remove(se.getSession().getId());
    }
}
