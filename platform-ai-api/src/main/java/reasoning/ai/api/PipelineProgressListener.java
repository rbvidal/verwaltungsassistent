package reasoning.ai.api;

/**
 * Optional observer for pipeline stage transitions. Implementations receive
 * semantic stage keys while a request is being processed; they are free to
 * translate them into user-facing progress information.
 *
 * <p>This is an observability hook only: it must not influence the pipeline.
 * Implementations are optional — when none is registered, the pipeline runs
 * unchanged.</p>
 */
public interface PipelineProgressListener {

    /** Called when the pipeline for the given request reaches a stage. */
    void onStage(String requestId, String stage);

    /**
     * Called with optional runtime data for the stage (observability only).
     * Implementations that do not need the data may ignore the default.
     */
    default void onStage(String requestId, String stage, java.util.Map<String, Object> data) {
        onStage(requestId, stage);
    }
}
