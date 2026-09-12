package reasoning.ai.model;

import java.util.List;

/**
 * An analysis objective with an ID, label, description, confidence, priority, key actions, and governing references.
 */
public record AnalysisObjective(
        String objectiveId,
        String label,
        String description,
        double confidence,
        int priority,
        List<String> keyActions,
        List<String> governingReferences
) {
    public AnalysisObjective {
        keyActions = keyActions == null ? List.of() : List.copyOf(keyActions);
        governingReferences = governingReferences == null ? List.of() : List.copyOf(governingReferences);
    }
}
