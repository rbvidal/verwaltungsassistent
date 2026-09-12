package verwaltungsassistent.web.service;

import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveDemoStatusServiceTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static AuthenticatedUser user(String email) {
        return new AuthenticatedUser(UUID.randomUUID(), email, "Test", Set.of("USER"));
    }

    private static LiveDemoStatusService service(SessionRegistryImpl registry, Clock clock) {
        return new LiveDemoStatusService(registry, clock, WINDOW, 5, 10);
    }

    // ── Active-user counting: live session AND recent activity required ──

    @Test
    void liveSessionWithRecentActivity_isCounted() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("s1", user("alice@x.de"));
        LiveDemoStatusService service = service(registry, Clock.systemDefaultZone());
        service.touch("alice@x.de");

        assertEquals(1, service.activeUserCount());
    }

    @Test
    void liveSessionWithoutActivity_isNotCounted() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("s1", user("alice@x.de"));
        LiveDemoStatusService service = service(registry, Clock.systemDefaultZone());

        assertEquals(0, service.activeUserCount());
    }

    @Test
    void activityWithoutLiveSession_isNotCounted() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        LiveDemoStatusService service = service(registry, Clock.systemDefaultZone());
        service.touch("alice@x.de");

        assertEquals(0, service.activeUserCount());
    }

    @Test
    void staleActivity_isNotCounted() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("s1", user("alice@x.de"));
        Map<String, Instant> activity = new ConcurrentHashMap<>();
        Instant t0 = Instant.parse("2026-08-19T12:00:00Z");
        LiveDemoStatusService early = new LiveDemoStatusService(
                registry, Clock.fixed(t0, ZoneOffset.UTC), WINDOW, 5, 10, activity);
        early.touch("alice@x.de");

        LiveDemoStatusService later = new LiveDemoStatusService(
                registry, Clock.fixed(t0.plus(WINDOW).plusSeconds(1), ZoneOffset.UTC), WINDOW, 5, 10, activity);
        assertEquals(0, later.activeUserCount());
    }

    @Test
    void activityAtWindowCutoff_isStillCounted() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("s1", user("alice@x.de"));
        Map<String, Instant> activity = new ConcurrentHashMap<>();
        Instant t0 = Instant.parse("2026-08-19T12:00:00Z");
        LiveDemoStatusService early = new LiveDemoStatusService(
                registry, Clock.fixed(t0, ZoneOffset.UTC), WINDOW, 5, 10, activity);
        early.touch("alice@x.de");

        LiveDemoStatusService atCutoff = new LiveDemoStatusService(
                registry, Clock.fixed(t0.plus(WINDOW), ZoneOffset.UTC), WINDOW, 5, 10, activity);
        assertEquals(1, atCutoff.activeUserCount());
    }

    @Test
    void multipleSessionsOfSameUser_countOnce() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        AuthenticatedUser alice = user("alice@x.de");
        registry.registerNewSession("s1", alice);
        registry.registerNewSession("s2", alice);
        LiveDemoStatusService service = service(registry, Clock.systemDefaultZone());
        service.touch("alice@x.de");

        assertEquals(1, service.activeUserCount());
    }

    @Test
    void multipleUsers_areCountedDistinctly() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("s1", user("alice@x.de"));
        registry.registerNewSession("s2", user("bob@x.de"));
        registry.registerNewSession("s3", user("carol@x.de"));
        LiveDemoStatusService service = service(registry, Clock.systemDefaultZone());
        service.touch("alice@x.de");
        service.touch("bob@x.de");

        assertEquals(2, service.activeUserCount());
    }

    @Test
    void emptyRegistry_countsZero() {
        LiveDemoStatusService service = service(new SessionRegistryImpl(), Clock.systemDefaultZone());
        assertEquals(0, service.activeUserCount());
    }

    // ── Level thresholds (defaults: 5 moderate, 10 high) ──

    @Test
    void levelMapping_usesConfiguredThresholds() {
        LiveDemoStatusService service = service(new SessionRegistryImpl(), Clock.systemDefaultZone());
        assertEquals(LiveDemoStatusService.Level.GREEN, service.levelFor(0));
        assertEquals(LiveDemoStatusService.Level.GREEN, service.levelFor(5));
        assertEquals(LiveDemoStatusService.Level.YELLOW, service.levelFor(6));
        assertEquals(LiveDemoStatusService.Level.YELLOW, service.levelFor(10));
        assertEquals(LiveDemoStatusService.Level.RED, service.levelFor(11));
    }

    @Test
    void status_mapsCountAndLevel() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        LiveDemoStatusService service = service(registry, Clock.systemDefaultZone());
        for (int i = 0; i < 12; i++) {
            registry.registerNewSession("s" + i, user("u" + i + "@x.de"));
            service.touch("u" + i + "@x.de");
        }

        LiveDemoStatusService.Status status = service.status();
        assertEquals(12, status.count());
        assertEquals(LiveDemoStatusService.Level.RED, status.level());
    }
}
