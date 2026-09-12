package reasoning.workspace.application;

import reasoning.common.model.WorkspacePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Orchestrates workspace phase progression by dispatching to registered {@link PhaseHandler} instances. */
public class WorkspaceOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceOrchestrator.class);

    private static final List<WorkspacePhase> DEFAULT_FLOW = List.of(WorkspacePhase.values());

    private final Map<WorkspacePhase, PhaseHandler> handlers = new LinkedHashMap<>();

    /** Registers a phase handler keyed by its supported phase. */
    public void registerHandler(PhaseHandler handler) {
        handlers.put(handler.supportedPhase(), handler);
        log.debug("Registered phase handler: {} for {}", handler.getClass().getSimpleName(), handler.supportedPhase());
    }

    /** Determines the next phase in the default flow after the context's current phase. */
    public WorkspacePhase determineNextPhase(WorkflowContext ctx) {
        int currentIdx = DEFAULT_FLOW.indexOf(ctx.getCurrentPhase());
        if (currentIdx < 0 || currentIdx >= DEFAULT_FLOW.size() - 1) {
            return ctx.getCurrentPhase();
        }
        return DEFAULT_FLOW.get(currentIdx + 1);
    }

    /** Determines the previous phase in the default flow before the context's current phase. */
    public WorkspacePhase determinePreviousPhase(WorkflowContext ctx) {
        int currentIdx = DEFAULT_FLOW.indexOf(ctx.getCurrentPhase());
        if (currentIdx <= 0) {
            return ctx.getCurrentPhase();
        }
        return DEFAULT_FLOW.get(currentIdx - 1);
    }

    /** Processes the current phase by delegating to the registered handler. */
    public PhaseResult processPhase(WorkflowContext ctx) {
        PhaseHandler handler = handlers.get(ctx.getCurrentPhase());
        if (handler == null) {
            return PhaseResult.completed(ctx.getCurrentPhase(), "No handler registered for phase");
        }
        return handler.process(ctx);
    }

    /** Returns {@code true} if the given phase is SETUP (the first phase). */
    public boolean isFirstPhase(WorkspacePhase phase) {
        return phase == WorkspacePhase.SETUP;
    }

    /** Returns {@code true} if the given phase is COMPLETE (the last phase). */
    public boolean isLastPhase(WorkspacePhase phase) {
        return phase == WorkspacePhase.COMPLETE;
    }

    /** Returns an unmodifiable copy of the default phase flow. */
    public List<WorkspacePhase> allPhases() {
        return List.copyOf(DEFAULT_FLOW);
    }

    /** Result of processing a workspace phase, containing the phase, optional data, and a message. */
    public record PhaseResult(WorkspacePhase phase, Map<String, Object> data, String message) {
        public PhaseResult(WorkspacePhase phase, String message) {
            this(phase, Map.of(), message);
        }

        /** Creates a completed phase result with a message and empty data. */
        public static PhaseResult completed(WorkspacePhase phase, String message) {
            return new PhaseResult(phase, message);
        }

        /** Creates a completed phase result with data and a message. */
        public static PhaseResult completed(WorkspacePhase phase, Map<String, Object> data, String message) {
            return new PhaseResult(phase, data, message);
        }
    }
}
