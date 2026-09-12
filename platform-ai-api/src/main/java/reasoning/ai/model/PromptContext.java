package reasoning.ai.model;

import java.util.List;
import java.util.Map;

/**
 * The full context used to construct an AI prompt, including system instruction, user question,
 * retrieval context, objectives, finding hierarchy, source dossier, and retrieval scope.
 */
public record PromptContext(
        String systemInstruction,
        String userQuestion,
        RetrievalContext retrievalContext,
        List<AiMessage> conversationHistory,
        List<AnalysisObjective> objectives,
        FindingHierarchy findingHierarchy,
        SourceDossier sourceDossier,
        RetrievalScope retrievalScope,
        EvidencePackage evidencePackage
) {
    public PromptContext {
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        findingHierarchy = findingHierarchy == null
                ? new FindingHierarchy(List.of(), List.of(), List.of(), List.of(), List.of())
                : findingHierarchy;
        sourceDossier = sourceDossier == null
                ? new SourceDossier(Map.of(), List.of(), List.of(), 0.0, "No assessment")
                : sourceDossier;
        retrievalScope = retrievalScope == null ? RetrievalScope.HYBRID : retrievalScope;
        evidencePackage = evidencePackage; // nullable — only present in evidence-reasoning mode
    }

    public PromptContext(String systemInstruction, String userQuestion,
                         RetrievalContext retrievalContext, List<AiMessage> conversationHistory) {
        this(systemInstruction, userQuestion, retrievalContext, conversationHistory, List.of(), null, null, null, null);
    }
}
