package reasoning.ai.model;

import java.util.List;

/**
 * A structured hierarchy of findings organized by role: primary, secondary, procedural, and supporting.
 */
public record FindingHierarchy(
        List<FindingElement> primaryFindings,
        List<FindingElement> secondaryFindings,
        List<FindingElement> proceduralFindings,
        List<FindingElement> supportingFindings,
        List<String> relationships
) {
    public FindingHierarchy {
        primaryFindings = primaryFindings == null ? List.of() : List.copyOf(primaryFindings);
        secondaryFindings = secondaryFindings == null ? List.of() : List.copyOf(secondaryFindings);
        proceduralFindings = proceduralFindings == null ? List.of() : List.copyOf(proceduralFindings);
        supportingFindings = supportingFindings == null ? List.of() : List.copyOf(supportingFindings);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }
}
