package reasoning.common.model;

/** Sequential phases a workspace progresses through (setup, ingestion, analysis, review, complete). */
public enum WorkspacePhase {
    SETUP,
    INGESTION,
    ANALYSIS,
    REVIEW,
    COMPLETE
}
