package verwaltungsassistent.web.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoAiConcurrencyGuardTest {

    @Test
    void allowsUpToGlobalLimitAcrossUsers() {
        DemoAiConcurrencyGuard g = new DemoAiConcurrencyGuard();
        for (int i = 1; i <= DemoAiConcurrencyGuard.GLOBAL_MAX; i++) {
            assertTrue(g.tryAcquire("u" + i), "slot " + i + " should be free");
        }
        assertFalse(g.tryAcquire("extra"), "5th global slot must be rejected");
    }

    @Test
    void allowsUpToPerUserLimit() {
        DemoAiConcurrencyGuard g = new DemoAiConcurrencyGuard();
        assertTrue(g.tryAcquire("demo01@verwaltungsassistent.local"));
        assertTrue(g.tryAcquire("demo01@verwaltungsassistent.local"));
        assertFalse(g.tryAcquire("demo01@verwaltungsassistent.local"), "3rd concurrent op of one user must be rejected");
        // other users are not blocked by this user's slots
        assertTrue(g.tryAcquire("demo02@verwaltungsassistent.local"));
    }

    @Test
    void releasesSlotsAfterUse() {
        DemoAiConcurrencyGuard g = new DemoAiConcurrencyGuard();
        assertTrue(g.tryAcquire("demo01@verwaltungsassistent.local"));
        assertTrue(g.tryAcquire("demo01@verwaltungsassistent.local"));
        g.release("demo01@verwaltungsassistent.local");
        assertTrue(g.tryAcquire("demo01@verwaltungsassistent.local"), "slot must be free again after release");
    }

    @Test
    void releaseIsSafeAndClamped() {
        DemoAiConcurrencyGuard g = new DemoAiConcurrencyGuard();
        g.release("never-acquired@verwaltungsassistent.local");
        g.release("anonymous");
        assertEquals(0, g.activeGlobal());
        assertEquals(0, g.activeFor("never-acquired@verwaltungsassistent.local"));
    }

    @Test
    void anonymousKeyIsNormalized() {
        DemoAiConcurrencyGuard g = new DemoAiConcurrencyGuard();
        assertTrue(g.tryAcquire(null));
        assertTrue(g.tryAcquire("   "), "blank key maps to the same anonymous bucket (second slot allowed)");
        assertFalse(g.tryAcquire("  "), "third anonymous slot must be rejected");
        assertEquals(2, g.activeFor("anonymous"));
        g.release(null);
        assertEquals(1, g.activeFor("anonymous"));
    }
}
