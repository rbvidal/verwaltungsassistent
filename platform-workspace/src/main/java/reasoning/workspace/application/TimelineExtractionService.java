package reasoning.workspace.application;

import reasoning.document.api.TextExtractionService;
import reasoning.workspace.model.TimelineEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Extracts timeline events from document text using date patterns and keyword classification. */
@Service
public class TimelineExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TimelineExtractionService.class);

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2}\\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4})" +
            "|(\\d{1,2}/\\d{1,2}/\\d{2,4})" +
            "|(\\d{4}-\\d{2}-\\d{2})" +
            "|(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, TimelineEventType> EVENT_KEYWORDS = Map.ofEntries(
            Map.entry("decision", TimelineEventType.DECISION),
            Map.entry("ruling", TimelineEventType.DECISION),
            Map.entry("deadline", TimelineEventType.DEADLINE),
            Map.entry("due date", TimelineEventType.DEADLINE),
            Map.entry("milestone", TimelineEventType.MILESTONE),
            Map.entry("completed", TimelineEventType.MILESTONE),
            Map.entry("delivered", TimelineEventType.MILESTONE),
            Map.entry("communication", TimelineEventType.COMMUNICATION),
            Map.entry("email", TimelineEventType.COMMUNICATION),
            Map.entry("meeting", TimelineEventType.COMMUNICATION),
            Map.entry("call", TimelineEventType.COMMUNICATION),
            Map.entry("change", TimelineEventType.CHANGE),
            Map.entry("amendment", TimelineEventType.CHANGE),
            Map.entry("revision", TimelineEventType.CHANGE),
            Map.entry("discovery", TimelineEventType.DISCOVERY),
            Map.entry("finding", TimelineEventType.DISCOVERY),
            Map.entry("publication", TimelineEventType.PUBLICATION),
            Map.entry("published", TimelineEventType.PUBLICATION),
            Map.entry("released", TimelineEventType.PUBLICATION));

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE
    };

    private final ObjectProvider<TextExtractionService> textExtractor;

    public TimelineExtractionService(ObjectProvider<TextExtractionService> textExtractor) {
        this.textExtractor = textExtractor;
    }

    /** Result of timeline extraction containing extracted events and document processing stats. */
    public record ExtractionResult(List<ExtractedEvent> events, int docsScanned, int docsProcessing) {
        /** Returns whether any documents are still being processed. */
        public boolean hasProcessingDocs() { return docsProcessing > 0; }
        /** Returns whether any events were extracted. */
        public boolean hasEvents() { return !events.isEmpty(); }
    }

    /** A single extracted timeline event with date, description, type, confidence, and source document. */
    public record ExtractedEvent(LocalDate eventDate, String title, String description,
                                  TimelineEventType eventType, double confidence, String sourceDocumentId) {}

    /** Extracts timeline events from a list of document info objects, skipping those still processing. */
    public ExtractionResult extractFromDocuments(List<DocInfo> docs) {
        List<ExtractedEvent> allEvents = new ArrayList<>();
        int scanned = 0;
        int processing = 0;

        for (DocInfo doc : docs) {
            if (doc.status().equals("INGESTION_PENDING") || doc.status().equals("INGESTING")) {
                processing++;
                continue;
            }
            if (doc.text() == null || doc.text().isBlank()) {
                continue;
            }
            scanned++;
            List<ExtractedEvent> events = extractFromText(doc.text(), doc.documentId());
            allEvents.addAll(events);
        }

        return new ExtractionResult(allEvents, scanned, processing);
    }

    /** Extracts timeline events from raw text using date patterns and keyword-based classification. */
    public List<ExtractedEvent> extractFromText(String text, String sourceDocumentId) {
        if (text == null || text.isBlank()) return List.of();

        List<ExtractedEvent> events = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");

        for (String sentence : sentences) {
            Matcher dateMatcher = DATE_PATTERN.matcher(sentence);
            while (dateMatcher.find()) {
                String dateStr = dateMatcher.group();
                LocalDate date = parseDate(dateStr);
                if (date == null) continue;

                String cleanSentence = sentence.replace(dateStr, "").replaceAll("\\s+", " ").trim();
                if (cleanSentence.length() < 10) continue;

                TimelineEventType type = classifySentence(sentence.toLowerCase(Locale.ENGLISH));
                String title = cleanSentence.length() > 100
                        ? cleanSentence.substring(0, 97) + "..."
                        : cleanSentence;

                events.add(new ExtractedEvent(date, title, cleanSentence, type,
                        0.7, sourceDocumentId));
                break;
            }
        }

        return events.stream()
                .sorted(Comparator.comparing(ExtractedEvent::eventDate))
                .collect(Collectors.toList());
    }

    private TimelineEventType classifySentence(String lower) {
        for (Map.Entry<String, TimelineEventType> entry : EVENT_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return TimelineEventType.EVENT;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null) return null;
        dateStr = dateStr.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(dateStr, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Simple record holding document ID, status, and extracted text for timeline analysis. */
    public record DocInfo(String documentId, String status, String text) {}
}
