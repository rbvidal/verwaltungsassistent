package verwaltungsassistent.web.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser für das Dateisystem-E-Mail-Dataset (Phase 2D.15): liest UTF-8-Text-
 * Dateien im Kopfzeilenformat und erzeugt daraus {@link ParsedEmail}-Objekte.
 *
 * <pre>
 * From: Bernd Becker &lt;bernd.becker@example.de&gt;
 * To: info@verwaltungs-demo.de
 * Subject: Baugenehmigung – Carport Nachfrage
 * Date: 2026-08-06T08:42:00+02:00
 * Message-ID: &lt;demo-email-001@verwaltungs-demo.de&gt;
 * In-Reply-To: &lt;demo-email-000@verwaltungs-demo.de&gt;   (optional)
 * References: &lt;demo-email-000@verwaltungs-demo.de&gt;    (optional)
 * Attachments: wohngeld/mietvertrag.pdf, baugenehmigung/lageplan.pdf
 *
 * --- body ab hier ---
 * Sehr geehrte Damen und Herren, …
 * </pre>
 *
 * <p>Pflichtfelder: From (mit verwendbarer Absender-Adresse), To, Subject,
 * Date (ISO-8601 mit Zeitzone), Message-ID. Optional: In-Reply-To, References,
 * Attachments (kommasepariert, relativ zu {@code <root>/attachments/}).
 * Der Body beginnt nach dem Separator {@code --- body ab hier ---}; fehlt der
 * Separator, beginnt er nach der ersten Leerzeile. Ein leerer Body ist gültig.</p>
 *
 * <p>Determinismus: Die Gesamtliste ist primär nach {@code Date}, bei
 * Gleichstand nach Dateiname sortiert. Duplikate von Message-IDs innerhalb
 * eines Datasets werden abgelehnt (Fehler mit Angabe der Dateien).</p>
 */
public final class DemoEmailFileParser {

    private static final String BODY_MARKER = "--- body ab hier ---";
    private static final Pattern HEADER = Pattern.compile(
            "^([A-Za-z][A-Za-z -]*?):[ \\t]*(.*)$");
    private static final Pattern FROM_WITH_ANGLE = Pattern.compile(
            "^\\s*(.*?)\\s*<([^>]+)>\\s*$");

    private DemoEmailFileParser() {
    }

    /** Eine aus einer Dataset-Datei geparste E-Mail (noch ohne Anhang-Bytes). */
    public record ParsedEmail(String fileName,
                              String messageId,
                              String inReplyTo,
                              String references,
                              String senderName,
                              String senderEmail,
                              String recipient,
                              String subject,
                              Instant receivedAt,
                              String body,
                              List<String> attachmentPaths) {
    }

    /**
     * Parst alle {@code *.txt}-Dateien eines Verzeichnisses (UTF-8), prüft auf
     * doppelte Message-IDs und sortiert deterministisch (Date, dann Dateiname).
     */
    public static List<ParsedEmail> parseDirectory(Path emailsDir) throws IOException {
        if (emailsDir == null || !Files.isDirectory(emailsDir)) {
            throw new IOException("E-Mail-Verzeichnis nicht vorhanden: " + emailsDir);
        }
        List<ParsedEmail> parsed = new ArrayList<>();
        try (var stream = Files.list(emailsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted()
                    .toList();
            if (files.isEmpty()) {
                throw new IOException("Keine .txt-E-Mail-Dateien im Verzeichnis: " + emailsDir);
            }
            for (Path file : files) {
                parsed.add(parseFile(file));
            }
        }
        rejectDuplicateMessageIds(parsed);
        parsed.sort(Comparator
                .comparing(ParsedEmail::receivedAt)
                .thenComparing(ParsedEmail::fileName));
        return List.copyOf(parsed);
    }

    /** Parst EINE Datei und validiert die Pflichtfelder. */
    public static ParsedEmail parseFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String fileName = file.getFileName().toString();
        String[] headerBlock = headerBlockOf(content);
        Map<String, String> headers = parseHeaders(headerBlock);
        String body = bodyOf(content, headerBlock.length);

        String from = require(headers, "From", fileName);
        String senderEmail;
        String senderName;
        Matcher m = FROM_WITH_ANGLE.matcher(from);
        if (m.matches() && m.group(2) != null && !m.group(2).isBlank()) {
            senderName = m.group(1).isBlank() ? null : m.group(1).trim();
            senderEmail = m.group(2).trim();
        } else {
            senderName = null;
            senderEmail = from.trim();
        }
        if (!senderEmail.contains("@")) {
            throw new IllegalArgumentException(
                    "Ungültige Absender-Adresse in '" + fileName + "': " + from);
        }
        String subject = require(headers, "Subject", fileName);
        String messageId = require(headers, "Message-ID", fileName);
        String recipient = require(headers, "To", fileName);
        String date = require(headers, "Date", fileName);
        Instant receivedAt = parseDate(date, fileName);

        return new ParsedEmail(
                fileName,
                messageId,
                firstLine(headers, "In-Reply-To"),
                firstLine(headers, "References"),
                senderName,
                senderEmail,
                recipient,
                subject,
                receivedAt,
                body,
                splitAttachments(firstLine(headers, "Attachments")));
    }

    private static String[] headerBlockOf(String content) {
        if (content.contains(BODY_MARKER)) {
            int markerEnd = content.indexOf(BODY_MARKER) + BODY_MARKER.length();
            String headers = content.substring(0, markerEnd)
                    .replace(BODY_MARKER, "");
            return headers.split("\n", -1);
        }
        // Fallback: Header enden vor der ersten Leerzeile.
        String[] lines = content.split("\n", -1);
        int firstBlank = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                firstBlank = i;
                break;
            }
        }
        return firstBlank < 0 ? lines : java.util.Arrays.copyOf(lines, firstBlank);
    }

    private static String bodyOf(String content, int headerLineCount) {
        int markerIndex = content.indexOf(BODY_MARKER);
        String body;
        if (markerIndex >= 0) {
            body = content.substring(markerIndex + BODY_MARKER.length());
        } else {
            // Header-Block überspringen: Body beginnt nach der ersten Leerzeile.
            String[] lines = content.split("\n", -1);
            if (headerLineCount < lines.length && lines[headerLineCount].isBlank()) {
                body = String.join("\n", java.util.Arrays.copyOfRange(
                        lines, headerLineCount + 1, lines.length));
            } else {
                body = headerLineCount < lines.length
                        ? String.join("\n", java.util.Arrays.copyOfRange(
                                lines, headerLineCount, lines.length))
                        : "";
            }
        }
        int start = 0;
        while (start < body.length() && (body.charAt(start) == '\n' || body.charAt(start) == '\r')) {
            start++;
        }
        int end = body.length();
        while (end > start && (body.charAt(end - 1) == '\n' || body.charAt(end - 1) == '\r')) {
            end--;
        }
        return body.substring(start, end);
    }

    private static Map<String, String> parseHeaders(String[] headerLines) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : headerLines) {
            Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                String key = m.group(1).trim().toLowerCase();
                String value = m.group(2).trim();
                headers.putIfAbsent(key, value);
            }
        }
        return headers;
    }

    private static String require(Map<String, String> headers, String key, String fileName) {
        String value = headers.get(key.toLowerCase());
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Pflichtfeld '" + key + "' fehlt in '" + fileName + "'");
        }
        return value;
    }

    private static String firstLine(Map<String, String> headers, String key) {
        String value = headers.get(key.toLowerCase());
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.lines().map(String::trim)
                .filter(l -> !l.isEmpty()).findFirst().orElse(null);
        return first;
    }

    private static Instant parseDate(String value, String fileName) {
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return ZonedDateTime.parse(value.trim()).toInstant();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException(
                        "Datum nicht lesbar (ISO-8601 mit Zeitzone erwartet) in '"
                                + fileName + "': " + value);
            }
        }
    }

    /** Kommaseparierte Anhang-Pfade (relativ zum Attachment-Root), getrimmt, ohne Leereinträge. */
    private static List<String> splitAttachments(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return List.copyOf(parts);
    }

    private static void rejectDuplicateMessageIds(List<ParsedEmail> emails) {
        Map<String, List<String>> byId = new HashMap<>();
        for (ParsedEmail e : emails) {
            byId.computeIfAbsent(e.messageId(), k -> new ArrayList<>()).add(e.fileName());
        }
        List<String> duplicates = byId.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " in " + e.getValue())
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Doppelte Message-IDs im Dataset: " + duplicates);
        }
    }
}
