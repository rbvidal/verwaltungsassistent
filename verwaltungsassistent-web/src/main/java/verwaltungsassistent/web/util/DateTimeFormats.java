package verwaltungsassistent.web.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Canonical German presentation formats for the human-facing UI.
 *
 * <p>Presentation only: machine/API/storage/LLM representations remain
 * ISO-8601 (LocalDate → {@code yyyy-MM-dd}, Instant → ISO timestamp).
 * This class is the single place where German display formatting is defined.
 */
public final class DateTimeFormats {

    /** {@code dd.MM.yyyy} — German date display, e.g. 20.08.2026. */
    public static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** {@code dd.MM.yyyy HH:mm} — German date/time display, e.g. 20.08.2026 20:15. */
    public static final DateTimeFormatter GERMAN_DATETIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private DateTimeFormats() {
    }

    /** Formats a date-only value for German UI display; null stays null. */
    public static String formatDate(LocalDate date) {
        return date != null ? date.format(GERMAN_DATE) : null;
    }

    /** Formats an absolute instant for German UI display in the given zone; null stays null. */
    public static String formatInstant(Instant instant, ZoneId zone) {
        if (instant == null) {
            return null;
        }
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return GERMAN_DATETIME.format(instant.atZone(effectiveZone));
    }

    /** Formats a local date-time for German UI display; null stays null. */
    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? GERMAN_DATETIME.format(dateTime) : null;
    }
}
