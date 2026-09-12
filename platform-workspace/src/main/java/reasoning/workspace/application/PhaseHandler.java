package reasoning.workspace.application;

import reasoning.common.model.WorkspacePhase;

/** Handler for processing a specific workspace phase. */
public interface PhaseHandler {
    /** Returns the phase this handler supports. */
    WorkspacePhase supportedPhase();
    /** Processes the given workflow context and returns a phase result. */
    WorkspaceOrchestrator.PhaseResult process(WorkflowContext ctx);
    /** Returns whether this handler is applicable to the given context; defaults to {@code true}. */
    default boolean isApplicable(WorkflowContext ctx) { return true; }
}
