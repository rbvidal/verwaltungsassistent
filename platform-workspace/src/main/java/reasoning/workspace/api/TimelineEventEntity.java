package reasoning.workspace.api;

import reasoning.workspace.model.TimelineEventType;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** JPA entity for a timeline event within a workspace, optionally AI-generated. */
@Entity
@Table(name = "workspace_timeline_events")
public class TimelineEventEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private TimelineEventType eventType;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TimelineEventEntity() {}

    public TimelineEventEntity(String id, String workspaceId, LocalDate eventDate, String title, String description,
                               TimelineEventType eventType, String sourceDocumentId, double confidence, boolean aiGenerated) {
        this.id = id != null ? UUID.fromString(id) : UUID.randomUUID();
        this.workspaceId = UUID.fromString(workspaceId);
        this.eventDate = eventDate;
        this.title = title;
        this.description = description;
        this.eventType = eventType;
        this.sourceDocumentId = sourceDocumentId != null ? UUID.fromString(sourceDocumentId) : null;
        this.confidence = confidence;
        this.aiGenerated = aiGenerated;
        this.createdAt = Instant.now();
    }

    public UUID getUuid() { return id; }
    public String getId() { return id != null ? id.toString() : null; }
    public void setId(String id) { this.id = id != null ? UUID.fromString(id) : null; }

    public UUID getWorkspaceUuid() { return workspaceId; }
    public String getWorkspaceId() { return workspaceId != null ? workspaceId.toString() : null; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = UUID.fromString(workspaceId); }

    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public TimelineEventType getEventType() { return eventType; }
    public void setEventType(TimelineEventType eventType) { this.eventType = eventType; }

    public UUID getSourceDocumentUuid() { return sourceDocumentId; }
    public String getSourceDocumentId() { return sourceDocumentId != null ? sourceDocumentId.toString() : null; }
    public void setSourceDocumentId(String sourceDocumentId) { this.sourceDocumentId = sourceDocumentId != null ? UUID.fromString(sourceDocumentId) : null; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean aiGenerated) { this.aiGenerated = aiGenerated; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
