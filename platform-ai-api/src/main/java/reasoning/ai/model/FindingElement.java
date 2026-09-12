package reasoning.ai.model;

import java.util.List;

/**
 * A single finding within a hierarchy, with a label, role, priority, and governing references.
 */
public record FindingElement(
        String label,
        FindingRole role,
        double priority,
        List<String> governingReferences,
        List<String> relatedReferences,
        String description
) {
    public FindingElement {
        governingReferences = governingReferences == null ? List.of() : List.copyOf(governingReferences);
        relatedReferences = relatedReferences == null ? List.of() : List.copyOf(relatedReferences);
    }
}
