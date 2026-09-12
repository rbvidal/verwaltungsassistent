package reasoning.auth.application;

import reasoning.auth.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory brute-force protection for logins.
 *
 * <p>After {@code platform.auth.login.max-failures} failed attempts within
 * {@code platform.auth.login.failure-window}, further attempts for that email
 * are rejected for {@code platform.auth.login.lockout-duration}. The lockout
 * is strictly time-based and expires automatically — no account can be locked
 * permanently, including administrator accounts. A successful login resets
 * the counter. State is in-memory only (per instance) and deliberately not a
 * distributed mechanism.</p>
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private final Map<String, Deque<Instant>> failuresByEmail = new ConcurrentHashMap<>();
    private final AuthProperties properties;

    public LoginAttemptGuard(AuthProperties properties) {
        this.properties = properties;
    }

    /** Records a failed login attempt for the given (normalized) email. */
    public void recordFailure(String email) {
        if (email == null || email.isBlank()) return;
        Instant now = Instant.now();
        Deque<Instant> deque = failuresByEmail.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, now);
            deque.addLast(now);
        }
    }

    /** Returns true when the email is currently locked out due to repeated failures. */
    public boolean isBlocked(String email) {
        if (email == null || email.isBlank()) return false;
        Deque<Instant> deque = failuresByEmail.get(email);
        if (deque == null) return false;
        Instant now = Instant.now();
        synchronized (deque) {
            prune(deque, now);
            if (deque.size() < properties.getLogin().getMaxFailures()) return false;
            Instant lastFailure = deque.peekLast();
            return lastFailure != null
                    && lastFailure.plus(properties.getLogin().getLockoutDuration()).isAfter(now);
        }
    }

    /** Clears the failure history (called after a successful login). */
    public void reset(String email) {
        if (email == null) return;
        failuresByEmail.remove(email);
    }

    /** Current failure count within the window (used by tests/diagnostics). */
    public int failureCount(String email) {
        Deque<Instant> deque = failuresByEmail.get(email);
        if (deque == null) return 0;
        Instant now = Instant.now();
        synchronized (deque) {
            prune(deque, now);
            return deque.size();
        }
    }

    /** Removes entries older than the configured window. */
    private void prune(Deque<Instant> deque, Instant now) {
        Instant cutoff = now.minus(properties.getLogin().getFailureWindow());
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.removeFirst();
        }
    }
}
