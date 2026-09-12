package reasoning.workspace.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted analysis run for a workspace (Fall). Runs are versioned per
 * workspace (1, 2, 3, …) and survive application restarts, so a previous
 * decision preparation remains reproducible from the information that was
 * available at that point in time.
 */
@Entity
@Table(name = "workspace_analysis_runs")
public class WorkspaceAnalysisRunEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private int version;

    /** RUNNING / COMPLETED / FAILED */
    @Column(nullable = false)
    private String status;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "document_count", nullable = false)
    private int documentCount;

    /**
     * Exact identities of the attached documents the analysis was based on
     * (JSON list of "documentId@version" strings, sorted). Enables detecting
     * replaced or removed evidence even when the document count is unchanged.
     */
    @Column(name = "evidence_ids", columnDefinition = "text")
    private String evidenceIds;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    protected WorkspaceAnalysisRunEntity() {
    }

    public WorkspaceAnalysisRunEntity(UUID id, UUID workspaceId, int version, String status,
                                      String triggeredBy, Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.version = version;
        this.status = status;
        this.triggeredBy = triggeredBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public int getVersion() {
        return version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(int documentCount) {
        this.documentCount = documentCount;
    }

    public String getEvidenceIds() {
        return evidenceIds;
    }

    public void setEvidenceIds(String evidenceIds) {
        this.evidenceIds = evidenceIds;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }
}
