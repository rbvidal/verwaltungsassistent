package reasoning.workspace.api;

import reasoning.common.model.WorkspacePhase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** JPA entity recording a completed step within a workspace phase. */
@Entity
@Table(name = "workspace_steps")
public class WorkspaceStepEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private WorkspacePhase phase;

    @Column(name = "step_name")
    private String stepName;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private String data;

    public WorkspaceStepEntity() {}

    public WorkspaceStepEntity(String id, String workspaceId, WorkspacePhase phase, String stepName, String status) {
        this.id = id != null ? UUID.fromString(id) : UUID.randomUUID();
        this.workspaceId = UUID.fromString(workspaceId);
        this.phase = phase;
        this.stepName = stepName;
        this.status = status;
        this.completedAt = Instant.now();
        this.data = "{}";
    }

    public UUID getUuid() { return id; }
    public String getId() { return id != null ? id.toString() : null; }
    public void setId(String id) { this.id = id != null ? UUID.fromString(id) : null; }

    public UUID getWorkspaceUuid() { return workspaceId; }
    public String getWorkspaceId() { return workspaceId != null ? workspaceId.toString() : null; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = UUID.fromString(workspaceId); }

    public WorkspacePhase getPhase() { return phase; }
    public void setPhase(WorkspacePhase phase) { this.phase = phase; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
}
