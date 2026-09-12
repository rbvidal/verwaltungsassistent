package reasoning.workspace.application;

import reasoning.workspace.api.WorkspaceEntity;
import reasoning.common.model.WorkspacePhase;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable context carrying workspace state, completed phases, and arbitrary attributes during workflow execution. */
public class WorkflowContext {

    private final WorkspaceEntity workspace;
    private final Map<WorkspacePhase, Boolean> completedPhases;
    private final Map<String, Object> attributes;

    /** Constructs a context wrapping the given workspace entity. */
    public WorkflowContext(WorkspaceEntity workspace) {
        this.workspace = workspace;
        this.completedPhases = new LinkedHashMap<>();
        this.attributes = new LinkedHashMap<>();
    }

    public WorkspaceEntity getWorkspace() { return workspace; }

    public String getWorkspaceId() { return workspace.getId(); }

    public WorkspacePhase getCurrentPhase() { return workspace.getPhase(); }

    /** Sets the current phase on the underlying workspace entity. */
    public void setCurrentPhase(WorkspacePhase phase) {
        workspace.setPhase(phase);
    }

    /** Marks the given phase as completed. */
    public void markPhaseCompleted(WorkspacePhase phase) {
        completedPhases.put(phase, true);
    }

    /** Returns whether the given phase has been marked completed. */
    public boolean isPhaseCompleted(WorkspacePhase phase) {
        return completedPhases.getOrDefault(phase, false);
    }

    /** Stores an arbitrary attribute in the context. */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /** Retrieves a typed attribute from the context by key. */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /** Returns an unmodifiable copy of all context attributes. */
    public Map<String, Object> getAttributes() {
        return Map.copyOf(attributes);
    }
}
