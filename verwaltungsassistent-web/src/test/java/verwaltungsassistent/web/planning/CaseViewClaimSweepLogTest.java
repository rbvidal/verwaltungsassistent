package verwaltungsassistent.web.planning;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import verwaltungsassistent.web.planning.persistence.JpaCaseViewClaimRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the sweep logging behaviour: the INFO "Case-View-Leases" message is
 * only emitted when at least one expired lease was actually deleted.
 */
class CaseViewClaimSweepLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger serviceLogger =
            (Logger) LoggerFactory.getLogger(CaseViewClaimService.class);
    private final Level originalLevel = serviceLogger.getLevel();

    private void attachLogger() {
        serviceLogger.setLevel(Level.INFO);
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogger() {
        serviceLogger.detachAppender(appender);
        serviceLogger.setLevel(originalLevel);
    }

    private List<ILoggingEvent> sweep(int deletedRows) {
        JpaCaseViewClaimRepository repository = mock(JpaCaseViewClaimRepository.class);
        when(repository.deleteExpired(any())).thenReturn(deletedRows);
        CaseViewClaimService service = new CaseViewClaimService(repository, Duration.ofSeconds(60));
        attachLogger();
        service.sweepExpired();
        return appender.list;
    }

    @Test
    void logsCleanupMessageWhenLeasesWereDeleted() {
        List<ILoggingEvent> events = sweep(1);
        assertEquals(1, events.size());
        assertTrue(events.get(0).getFormattedMessage().contains("Case-View-Leases"));
        assertTrue(events.get(0).getFormattedMessage().contains("1"));
    }

    @Test
    void doesNotLogCleanupMessageWhenNothingWasDeleted() {
        List<ILoggingEvent> events = sweep(0);
        assertTrue(events.isEmpty());
    }
}
