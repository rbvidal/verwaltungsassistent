package reasoning.workspace.api;

import reasoning.workspace.model.TimelineEventType;
import java.time.LocalDate;

/** DTO for a timeline event with date, type, confidence, and AI-generation flag. */
public record TimelineEventDto(
        String id,
        String workspaceId,
        LocalDate eventDate,
        String title,
        String description,
        TimelineEventType eventType,
        String sourceDocumentId,
        double confidence,
        boolean aiGenerated
) {}
