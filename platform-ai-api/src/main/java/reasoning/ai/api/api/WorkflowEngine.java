package reasoning.ai.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reusable workflow engine for executing configurable multi-step processes.
 * Supports document review, approval, AI analysis, batch processing, and compliance workflows.
 */
public interface WorkflowEngine {

    /** Starts a new workflow instance from the given definition. */
    WorkflowInstance start(String workflowDefinitionId, Map<String, Object> context);

    /** Advances the workflow to the next step. */
    WorkflowInstance advance(String instanceId);

    /** Returns the workflow to the previous step. */
    WorkflowInstance previous(String instanceId);

    /** Finds a workflow instance by ID. */
    Optional<WorkflowInstance> findInstance(String instanceId);

    /** Lists all active workflow instances. */
    List<WorkflowInstance> listActive();

    /** A single step in a workflow. */
    record WorkflowStep(String id, String name, String description, String handlerType,
                         Map<String, Object> config, List<String> nextSteps) {}

    /** A complete workflow definition with ordered steps. */
    record WorkflowDefinition(String id, String name, String description,
                               List<WorkflowStep> steps, String initialStep) {}

    /** A running instance of a workflow. */
    record WorkflowInstance(String id, String workflowDefinitionId, String currentStep,
                             WorkflowStatus status, Map<String, Object> context,
                             java.time.Instant startedAt, java.time.Instant updatedAt) {}

    enum WorkflowStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }
}
