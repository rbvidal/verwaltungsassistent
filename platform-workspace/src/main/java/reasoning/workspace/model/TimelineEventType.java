package reasoning.workspace.model;

/** Classification of timeline events (decision, deadline, milestone, communication, etc.). */
public enum TimelineEventType {
    EVENT,
    DECISION,
    COMMUNICATION,
    DEADLINE,
    MILESTONE,
    CHANGE,
    DISCOVERY,
    PUBLICATION,
    OTHER
}
