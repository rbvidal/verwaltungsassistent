package verwaltungsassistent.web.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Presentation convention: German UI formats via DateTimeFormats, while the
 * machine/API representation stays ISO-8601.
 */
class DateTimeFormatsTest {

    @Test
    void germanDateFormatting() {
        assertEquals("20.08.2026", DateTimeFormats.formatDate(LocalDate.of(2026, 8, 20)));
        assertNull(DateTimeFormats.formatDate(null));
    }

    @Test
    void germanDateTimeFormatting() {
        assertEquals("20.08.2026 20:15",
                DateTimeFormats.formatDateTime(LocalDateTime.of(2026, 8, 20, 20, 15, 32)));
        assertEquals("20.08.2026 20:15",
                DateTimeFormats.formatInstant(
                        Instant.parse("2026-08-20T18:15:32.123Z"), ZoneId.of("Europe/Berlin")));
        assertNull(DateTimeFormats.formatInstant(null, ZoneId.of("Europe/Berlin")));
    }

    @Test
    void machineRepresentationStaysIso() throws Exception {
        // Mirrors the production Jackson config (JavaTimeModule + no date-as-timestamps).
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertEquals("\"2026-08-20\"", mapper.writeValueAsString(LocalDate.of(2026, 8, 20)));
        assertEquals("\"2026-08-20T18:15:32.123Z\"",
                mapper.writeValueAsString(Instant.parse("2026-08-20T18:15:32.123Z")));
    }
}
