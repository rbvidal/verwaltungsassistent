package reasoning.ai.model;

import reasoning.ai.api.CommunicationTimelineBuilder.ProceduralTimeline;

import java.util.List;
import java.util.Map;

/**
 * The context produced by retrieval augmentation, containing sources, authority references,
 * a finding hierarchy, source dossier, and a procedural timeline.
 */
public record RetrievalContext(
        String query,
        String retrievalStrategy,
        List<SourceCitation> sources,
        List<AuthorityReference> authorityReferences,
        FindingHierarchy findingHierarchy,
        SourceDossier sourceDossier,
        ProceduralTimeline timeline,
        DecisionResult structuredDecision
) {
    public RetrievalContext {
        sources = sources == null ? List.of() : List.copyOf(sources);
        authorityReferences = authorityReferences == null ? List.of() : List.copyOf(authorityReferences);
        findingHierarchy = findingHierarchy == null
                ? new FindingHierarchy(List.of(), List.of(), List.of(), List.of(), List.of())
                : findingHierarchy;
        sourceDossier = sourceDossier == null
                ? new SourceDossier(Map.of(), List.of(), List.of(), 0.0, "No assessment")
                : sourceDossier;
        timeline = timeline == null
                ? new ProceduralTimeline(List.of(), List.of(), 0, 0, "No timeline")
                : timeline;
    }

    public RetrievalContext(String query, String retrievalStrategy, List<SourceCitation> sources) {
        this(query, retrievalStrategy, sources, List.of(), null, null, null, null);
    }

    public RetrievalContext(String query, String retrievalStrategy, List<SourceCitation> sources,
                           DecisionResult structuredDecision) {
        this(query, retrievalStrategy, sources, List.of(), null, null, null, structuredDecision);
    }
}
