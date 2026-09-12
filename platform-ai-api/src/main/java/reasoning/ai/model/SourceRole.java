package reasoning.ai.model;

/**
 * The role a source document plays in the case (e.g., establishing document, termination notice, financial record).
 */
public enum SourceRole {
    ESTABLISHING_DOCUMENT,
    TERMINATION_NOTICE,
    FINANCIAL_RECORD,
    WARNING,
    ESCALATION,
    ACKNOWLEDGMENT,
    ADMISSION,
    DAMAGES_SUPPORT,
    CHRONOLOGY,
    CORRESPONDENCE,
    UNCLASSIFIED
}
