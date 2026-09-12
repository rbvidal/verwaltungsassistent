package verwaltungsassistent.web.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small in-memory concurrency guard for expensive AI/LLM operations in the
 * public demo. Purpose: protect the local GPU from accidental or deliberate
 * excessive parallel AI workload. This is NOT an authorization mechanism.
 *
 * <p>Limits: {@link #GLOBAL_MAX} concurrent expensive operations globally and
 * {@link #USER_MAX} per authenticated demo account. Navigation and all other
 * non-LLM functionality is not affected. Slots must be released reliably in a
 * {@code finally} block after the guarded operation finishes, fails or is
 * cancelled.</p>
 */
@Component
@Profile("demo")
public class DemoAiConcurrencyGuard {

    public static final int GLOBAL_MAX = 4;
    public static final int USER_MAX = 2;

    public static final String CAPACITY_MESSAGE =
            "Die KI-Verarbeitung ist derzeit ausgelastet. Bitte versuchen Sie es in wenigen Sekunden erneut.";

    private final AtomicInteger globalActive = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> perUserActive = new ConcurrentHashMap<>();

    /**
     * Recovery-notice bookkeeping per user key: {@code lastRejectedAt} is set
     * when a {@link #tryAcquire} attempt is refused, {@code capacityFreeAt} is
     * set when a release makes at least one usable slot available again. The
     * UI polls {@link #recoveryAvailable} while an entry exists and shows the
     * green "KI wieder verfügbar" notice once; the entry is removed after
     * delivery ({@link #markRecoveryDelivered}). Pure observability — the
     * guard's acquire/release semantics are unchanged.
     */
    private final ConcurrentHashMap<String, Long> lastRejectedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> capacityFreeAt = new ConcurrentHashMap<>();

    /**
     * Tries to acquire one slot for the given user key.
     *
     * @param userKey account identifier (email) or {@code "anonymous"} when unknown
     * @return true if a slot was acquired, false if a limit is reached
     */
    public synchronized boolean tryAcquire(String userKey) {
        String key = normalize(userKey);
        int userActive = perUserActive.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
        if (globalActive.get() >= GLOBAL_MAX || userActive >= USER_MAX) {
            lastRejectedAt.put(key, System.currentTimeMillis());
            return false;
        }
        globalActive.incrementAndGet();
        perUserActive.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        return true;
    }

    /** Releases one previously acquired slot. Never throws; never goes negative. */
    public synchronized void release(String userKey) {
        String key = normalize(userKey);
        globalActive.updateAndGet(v -> Math.max(0, v - 1));
        perUserActive.computeIfPresent(key, (k, v) -> {
            v.updateAndGet(cur -> Math.max(0, cur - 1));
            return v.get() == 0 ? null : v;
        });
        if (!lastRejectedAt.isEmpty()) {
            long now = System.currentTimeMillis();
            if (activeFor(key) < USER_MAX) {
                capacityFreeAt.put(key, now);
            }
            if (globalActive.get() < GLOBAL_MAX) {
                // Jede Freigabe unter das globale Maximum schafft potenziell
                // einen nutzbaren Slot für ALLE abgewiesenen Konten.
                for (String watched : lastRejectedAt.keySet()) {
                    capacityFreeAt.compute(watched, (k, v) -> v == null || now > v ? now : v);
                }
            }
        }
    }

    /**
     * True while a rejection for this user is recorded AND a slot has become
     * available again (usable right now) but the recovery notice was not yet
     * delivered. Drives the "KI wieder verfügbar" notification.
     */
    public synchronized boolean recoveryAvailable(String userKey) {
        String key = normalize(userKey);
        Long rejectedAt = lastRejectedAt.get(key);
        if (rejectedAt == null) {
            return false;
        }
        Long freeAt = capacityFreeAt.get(key);
        if (freeAt == null || freeAt < rejectedAt) {
            return false;
        }
        return globalActive.get() < GLOBAL_MAX && activeFor(key) < USER_MAX;
    }

    /** True while an undelivered rejection for this user is recorded. */
    public synchronized boolean awaitingRecovery(String userKey) {
        return lastRejectedAt.containsKey(normalize(userKey));
    }

    /** Removes the pending rejection entry after the recovery notice was shown. */
    public synchronized void markRecoveryDelivered(String userKey) {
        String key = normalize(userKey);
        lastRejectedAt.remove(key);
        capacityFreeAt.remove(key);
    }

    /** Test/observability helper — current global usage. */
    public int activeGlobal() {
        return globalActive.get();
    }

    /** Test/observability helper — current usage for one user key. */
    public int activeFor(String userKey) {
        AtomicInteger v = perUserActive.get(normalize(userKey));
        return v == null ? 0 : v.get();
    }

    private static String normalize(String userKey) {
        return userKey == null || userKey.isBlank() ? "anonymous" : userKey.trim();
    }
}
