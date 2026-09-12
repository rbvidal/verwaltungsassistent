package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the server-side inactivity expiry: only the activity beacon
 * timestamp matters, expired sessions are invalidated, and the configured
 * timeout is honored.
 */
class SessionInactivityServiceTest {

    private static final long TIMEOUT_SECONDS = 15 * 60;

    @Test
    void configuredTimeout_isExposedForTheClient() {
        assertEquals(15 * 60, new SessionInactivityService(15).timeoutSeconds());
        assertEquals(60, new SessionInactivityService(1).timeoutSeconds());
    }

    @Test
    void sessionWithoutActivity_getsBaselineAndIsNotExpiredImmediately() {
        SessionInactivityService service = new SessionInactivityService(15);
        MockHttpSession session = new MockHttpSession();

        service.register(session);
        service.expireInactiveSessions();

        assertFalse(session.isInvalid(), "fresh sessions must not be expired immediately");
        assertTrue(session.getAttribute(SessionInactivityService.LAST_ACTIVITY_ATTR) instanceof Instant,
                "a baseline activity timestamp must be recorded");
    }

    @Test
    void oldActivity_expiresTheSession() {
        SessionInactivityService service = new SessionInactivityService(15);
        MockHttpSession session = new MockHttpSession();
        service.register(session);
        session.setAttribute(SessionInactivityService.LAST_ACTIVITY_ATTR,
                Instant.now().minus(TIMEOUT_SECONDS + 60, ChronoUnit.SECONDS));

        service.expireInactiveSessions();

        assertTrue(session.isInvalid(), "a session without recent user activity must be expired");
        assertEquals(0, service.trackedCount(), "expired sessions must be removed from tracking");
    }

    @Test
    void recentActivity_keepsTheSessionAlive() {
        SessionInactivityService service = new SessionInactivityService(15);
        MockHttpSession session = new MockHttpSession();
        service.register(session);
        session.setAttribute(SessionInactivityService.LAST_ACTIVITY_ATTR, Instant.now());

        service.expireInactiveSessions();

        assertFalse(session.isInvalid(), "recent user activity must keep the session alive");
    }

    @Test
    void touch_recordsActivityAndRegistersTheSession() {
        SessionInactivityService service = new SessionInactivityService(15);
        MockHttpSession session = new MockHttpSession();

        service.touch(session);

        assertTrue(session.getAttribute(SessionInactivityService.LAST_ACTIVITY_ATTR) instanceof Instant,
                "the activity beacon must record the timestamp");
        assertEquals(1, service.trackedCount(), "the beacon must register the session");
    }

    @Test
    void register_isIdempotent() {
        SessionInactivityService service = new SessionInactivityService(15);
        MockHttpSession session = new MockHttpSession();
        service.register(session);
        service.register(session);
        assertEquals(1, service.trackedCount());
    }
}
