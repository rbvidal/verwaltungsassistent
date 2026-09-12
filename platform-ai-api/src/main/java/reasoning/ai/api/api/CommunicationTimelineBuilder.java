package reasoning.ai.api;

import reasoning.ai.model.SourceCitation;

import java.util.List;

/**
 * Builds a communication-first procedural timeline from source citations.
 */
public interface CommunicationTimelineBuilder {

    /**
     * An event on the procedural timeline with sequence, type, description, and party information.
     */
    record TimelineEvent(
            int sequence,
            String type,
            String description,
            String sourceDoc,
            String sender,
            String recipient,
            boolean isCommunication
    ) {}

    /**
     * A complete procedural timeline with events, missing event types, and an overall assessment.
     */
    record ProceduralTimeline(
            List<TimelineEvent> events,
            List<String> missingEventTypes,
            int communicationCount,
            int summaryCount,
            String assessment
    ) {}

    /**
     * Builds a procedural timeline from the given query and source citations.
     */
    ProceduralTimeline build(String query, List<SourceCitation> sources);
}
