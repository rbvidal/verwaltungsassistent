package reasoning.ai.model;

import java.util.List;
import java.util.Map;

/**
 * A dossier summarizing which source roles are present, missing, and the overall coverage score.
 */
public record SourceDossier(
        Map<SourceRole, List<String>> sourcesByRole,
        List<String> presentRoles,
        List<String> missingRoles,
        double coverageScore,
        String completenessAssessment
) {
    public SourceDossier {
        sourcesByRole = sourcesByRole == null ? Map.of() : Map.copyOf(sourcesByRole);
        presentRoles = presentRoles == null ? List.of() : List.copyOf(presentRoles);
        missingRoles = missingRoles == null ? List.of() : List.copyOf(missingRoles);
    }
}
