package reasoning.ai.model;

/**
 * The classified intent of a user query (e.g., question answering, workspace analysis, document lookup).
 */
public enum QueryIntent {
    QUESTION_ANSWERING,
    WORKSPACE_ANALYSIS,
    SOURCE_ANALYSIS,
    DOCUMENT_RESEARCH,
    DOCUMENT_LOOKUP,
    INDEX_INSPECTION,
    CORPUS_DISCOVERY
}
