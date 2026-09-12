package reasoning.ai.model;

/**
 * Generic kinds of structured knowledge. These are execution-shape
 * categories, not domain concepts — a THRESHOLD is any ordered-boundary
 * table regardless of whether it stems from a procurement regulation, an
 * environmental standard, or a tax table.
 */
public enum StructuredKnowledgeKind {
    TABLE,
    THRESHOLD,
    RULE,
    DEFINITION,
    EXCEPTION,
    EFFECTIVE_DATE
}
