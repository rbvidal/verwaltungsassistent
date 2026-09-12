package reasoning.ai.model;

/**
 * Lifecycle of extracted structured knowledge.
 * <p>LLM extraction always enters as CANDIDATE. Only items that pass
 * deterministic structural validation, provenance validation, temporal
 * validation, and source entailment may become ACTIVE — the only status
 * the deterministic engine may consume. REJECTED and SUPERSEDED are
 * terminal; SUPERSEDED items are retained for audit.
 */
public enum StructuredKnowledgeStatus {
    CANDIDATE,
    VALIDATED,
    ACTIVE,
    REJECTED,
    SUPERSEDED
}
