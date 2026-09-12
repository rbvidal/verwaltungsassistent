package reasoning.auth.application;

import reasoning.auth.security.AuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptGuardTest {

    private AuthProperties properties() {
        AuthProperties p = new AuthProperties();
        p.getLogin().setMaxFailures(5);
        p.getLogin().setFailureWindow(Duration.ofMinutes(5));
        p.getLogin().setLockoutDuration(Duration.ofMillis(300));
        return p;
    }

    @Test
    void failuresBelowThreshold_doNotBlock() {
        LoginAttemptGuard guard = new LoginAttemptGuard(properties());
        for (int i = 0; i < 4; i++) guard.recordFailure("a@test.local");
        assertFalse(guard.isBlocked("a@test.local"));
        assertEquals(4, guard.failureCount("a@test.local"));
    }

    @Test
    void reachingThreshold_blocksFurtherAttempts() {
        LoginAttemptGuard guard = new LoginAttemptGuard(properties());
        for (int i = 0; i < 5; i++) guard.recordFailure("a@test.local");
        assertTrue(guard.isBlocked("a@test.local"));
    }

    @Test
    void lockout_expiresAfterConfiguredDuration() throws InterruptedException {
        LoginAttemptGuard guard = new LoginAttemptGuard(properties());
        for (int i = 0; i < 5; i++) guard.recordFailure("a@test.local");
        assertTrue(guard.isBlocked("a@test.local"));
        Thread.sleep(450);
        assertFalse(guard.isBlocked("a@test.local"));
    }

    @Test
    void successfulLogin_resetsCounter() {
        LoginAttemptGuard guard = new LoginAttemptGuard(properties());
        for (int i = 0; i < 4; i++) guard.recordFailure("a@test.local");
        guard.reset("a@test.local");
        assertEquals(0, guard.failureCount("a@test.local"));
        for (int i = 0; i < 4; i++) guard.recordFailure("a@test.local");
        assertFalse(guard.isBlocked("a@test.local"),
                "counter must restart after a successful login");
    }

    @Test
    void failuresOutsideWindow_arePruned() throws InterruptedException {
        AuthProperties p = properties();
        p.getLogin().setFailureWindow(Duration.ofMillis(200));
        LoginAttemptGuard guard = new LoginAttemptGuard(p);
        for (int i = 0; i < 4; i++) guard.recordFailure("a@test.local");
        Thread.sleep(300);
        guard.recordFailure("a@test.local");
        assertTrue(guard.failureCount("a@test.local") <= 2,
                "old failures outside the window must be pruned");
    }

    @Test
    void differentEmails_areTrackedIndependently() {
        LoginAttemptGuard guard = new LoginAttemptGuard(properties());
        for (int i = 0; i < 5; i++) guard.recordFailure("a@test.local");
        assertTrue(guard.isBlocked("a@test.local"));
        assertFalse(guard.isBlocked("b@test.local"));
    }

    @Test
    void blankEmail_isIgnored() {
        LoginAttemptGuard guard = new LoginAttemptGuard(properties());
        guard.recordFailure("  ");
        guard.recordFailure(null);
        assertFalse(guard.isBlocked(""));
    }
}
