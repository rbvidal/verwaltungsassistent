package verwaltungsassistent.web.e2e;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Veränderbare Uhr für deterministische E2E-Zeitsteuerung (playwright-Profil).
 * Die Anwendung selbst bemerkt keinen Unterschied — {@code instant()} liefert
 * einfach eine vorwärtsgeschobene Zeit, ohne dass ein Browser-Test wirklich
 * 20/30/60 Minuten warten müsste.
 */
public class TestClock extends Clock {

    private final ZoneId zone = ZoneId.systemDefault();
    private final Instant startedAt;
    private volatile Instant now;

    public TestClock(Instant start) {
        this.startedAt = start;
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    public Instant startedAt() {
        return startedAt;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
