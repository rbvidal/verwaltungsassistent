package reasoning.ai.api;

/**
 * Assembles a raw AI answer into a structured answer with labeled sections.
 */
public interface StructuredAnswerAssembler {

    /**
     * A structured answer with separate sections for findings, norms, strongest position, steps, and risks.
     */
    record StructuredAnswer(
            String factualFindings,
            String objective,
            String governingNorms,
            String strongestPosition,
            String practicalSteps,
            String risksAndLimitations,
            String bottomLine,
            String disclaimer,
            boolean fullyValidated
    ) {}

    /**
     * Assembles a raw answer text into a structured answer with labeled sections.
     */
    StructuredAnswer assemble(String rawAnswer, String query);
}
