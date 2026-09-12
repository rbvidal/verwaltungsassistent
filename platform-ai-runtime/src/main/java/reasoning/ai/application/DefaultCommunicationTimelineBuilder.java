package reasoning.ai.application;

import reasoning.ai.api.CommunicationTimelineBuilder;
import reasoning.ai.model.SourceCitation;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Builds a procedural timeline by classifying source titles into communication event types.
 */
@Service
public class DefaultCommunicationTimelineBuilder implements CommunicationTimelineBuilder {

    private static final Set<String> COMMUNICATION_KEYWORDS = Set.of(
            "email", "brief", "letter", "schreiben", "mahnung", "reminder",
            "kündigung", "termination", "warnung", "warning",
            "bestätigung", "acknowledgment", "antwort", "reply",
            "mitteilung", "notification", "aufforderung", "demand"
    );

    private static final Set<String> SUMMARY_KEYWORDS = Set.of(
            "sachverhalt", "zusammenfassung", "summary",
            "übersicht", "analyse", "analysis"
    );

    @Override
    public ProceduralTimeline build(String query, List<SourceCitation> sources) {
        List<TimelineEvent> events = new ArrayList<>();
        int commCount = 0;
        int summaryCount = 0;
        int seq = 0;

        for (SourceCitation s : sources) {
            String title = s.title() != null ? s.title() : "";
            String lower = title.toLowerCase();

            boolean isComm = COMMUNICATION_KEYWORDS.stream().anyMatch(lower::contains);
            boolean isSummary = SUMMARY_KEYWORDS.stream().anyMatch(lower::contains);

            String type = classifyEventType(lower);
            String sender = extractParty(lower, "sender");
            String recipient = extractParty(lower, "recipient");

            events.add(new TimelineEvent(
                    seq++, type, title, title, sender, recipient, isComm));

            if (isComm) commCount++;
            if (isSummary) summaryCount++;
        }

        Set<String> foundTypes = new HashSet<>();
        for (TimelineEvent e : events) foundTypes.add(e.type());
        List<String> missingTypes = new ArrayList<>();
        for (String required : List.of("CONTRACT", "PAYMENT_OBLIGATION",
                "PAYMENT_MISSED", "REMINDER", "TERMINATION_NOTICE", "ESCALATION")) {
            if (!foundTypes.contains(required)) missingTypes.add(required);
        }

        String assessment;
        if (commCount == 0) {
            assessment = "CRITICAL: No communication evidence found. Answer based only on summaries/derived sources.";
        } else if (summaryCount > commCount) {
            assessment = "WARNING: Summaries (" + summaryCount + ") outnumber communications (" + commCount + "). Prioritize communications in reasoning.";
        } else {
            assessment = "Communication evidence present (" + commCount + " items). Proceed with communication-first synthesis.";
        }

        return new ProceduralTimeline(events, missingTypes, commCount, summaryCount, assessment);
    }

    private String classifyEventType(String lower) {
        if (lower.contains("contract") || lower.contains("lease") || lower.contains("agreement"))
            return "CONTRACT";
        if (lower.contains("termination") || lower.contains("kündigung"))
            return "TERMINATION_NOTICE";
        if (lower.contains("reminder") || lower.contains("mahnung") || lower.contains("payment request"))
            return "REMINDER";
        if (lower.contains("warning") || lower.contains("warnung") || lower.contains("abmahnung"))
            return "ESCALATION";
        if (lower.contains("acknowledgment") || lower.contains("bestätigung") || lower.contains("admitted"))
            return "ACKNOWLEDGMENT";
        if (lower.contains("unpaid") || lower.contains("nicht gezahlt") || lower.contains("missed"))
            return "PAYMENT_MISSED";
        if (lower.contains("payment") || lower.contains("zahlung") || lower.contains("invoice"))
            return "PAYMENT_OBLIGATION";
        if (lower.contains("email") || lower.contains("brief") || lower.contains("schreiben"))
            return "COMMUNICATION";
        return "DOCUMENT";
    }

    private String extractParty(String lower, String role) {
        if (lower.contains("landlord") || lower.contains("vermieter")) return "Landlord";
        if (lower.contains("tenant") || lower.contains("mieter")) return "Tenant";
        return "Unknown";
    }
}
