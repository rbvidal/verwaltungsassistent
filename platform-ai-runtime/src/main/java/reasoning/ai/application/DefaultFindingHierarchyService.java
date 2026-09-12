package reasoning.ai.application;

import reasoning.ai.api.FindingHierarchyService;
import reasoning.ai.model.AnalysisObjective;
import reasoning.ai.model.FindingElement;
import reasoning.ai.model.FindingHierarchy;
import reasoning.ai.model.FindingRole;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Builds a finding hierarchy by mapping governing references to finding roles.
 */
@Service
public class DefaultFindingHierarchyService implements FindingHierarchyService {

    private static final Map<String, FindingRole> REF_ROLE_MAP = Map.ofEntries(
            Map.entry("535", FindingRole.PRIMARY_FINDING),
            Map.entry("537", FindingRole.PRIMARY_FINDING),
            Map.entry("556b", FindingRole.PRIMARY_FINDING),
            Map.entry("551", FindingRole.SUPPORTING_FINDING),
            Map.entry("387", FindingRole.COLLATERAL_FINDING),
            Map.entry("389", FindingRole.COLLATERAL_FINDING),
            Map.entry("543", FindingRole.PROCEDURAL_FINDING),
            Map.entry("569", FindingRole.PROCEDURAL_FINDING),
            Map.entry("573", FindingRole.PROCEDURAL_FINDING),
            Map.entry("573c", FindingRole.PROCEDURAL_FINDING),
            Map.entry("540", FindingRole.PROCEDURAL_FINDING),
            Map.entry("553", FindingRole.PROCEDURAL_FINDING),
            Map.entry("280", FindingRole.SUPPORTING_FINDING),
            Map.entry("281", FindingRole.SUPPORTING_FINDING),
            Map.entry("546a", FindingRole.SUPPORTING_FINDING),
            Map.entry("536", FindingRole.PROCEDURAL_FINDING),
            Map.entry("568", FindingRole.PROCEDURAL_FINDING),
            Map.entry("546", FindingRole.SUPPORTING_FINDING),
            Map.entry("574", FindingRole.PROCEDURAL_FINDING),
            Map.entry("556", FindingRole.PRIMARY_FINDING),
            Map.entry("556a", FindingRole.PRIMARY_FINDING),
            Map.entry("542", FindingRole.PROCEDURAL_FINDING)
    );

    private static final Map<String, List<String>> FINDING_RELATIONSHIPS = Map.of(
            "535", List.of("551:SECURED_BY", "543:ENFORCED_BY", "280:REMEDY_FOR_BREACH"),
            "551", List.of("387:OFFSET_VIA", "389:OFFSET_EFFECT"),
            "543", List.of("569:SPECIALIZED_BY", "546:LEADS_TO", "573c:GOVERNED_BY"),
            "280", List.of("546a:SPECIALIZED_BY", "281:ALTERNATIVE_TO"),
            "387", List.of("389:CONSEQUENCE_OF")
    );

    @Override
    public FindingHierarchy buildHierarchy(String query, List<AnalysisObjective> objectives) {
        Set<String> allReferences = new LinkedHashSet<>();
        for (AnalysisObjective obj : objectives) {
            allReferences.addAll(obj.governingReferences());
        }

        List<FindingElement> primaryFindings = new ArrayList<>();
        List<FindingElement> secondaryFindings = new ArrayList<>();
        List<FindingElement> proceduralFindings = new ArrayList<>();
        List<FindingElement> supportingFindings = new ArrayList<>();
        List<String> relationships = new ArrayList<>();

        for (String ref : allReferences) {
            FindingRole role = REF_ROLE_MAP.getOrDefault(ref, FindingRole.PROCEDURAL_FINDING);

            FindingElement element = new FindingElement(
                    refLabel(ref),
                    role,
                    rolePriority(role),
                    List.of(ref),
                    List.of(),
                    roleDescription(ref, role));

            switch (role) {
                case PRIMARY_FINDING -> primaryFindings.add(element);
                case SUPPORTING_FINDING -> secondaryFindings.add(element);
                case PROCEDURAL_FINDING -> proceduralFindings.add(element);
                case CONTEXTUAL_FINDING, COLLATERAL_FINDING -> supportingFindings.add(element);
            }

            List<String> rels = FINDING_RELATIONSHIPS.get(ref);
            if (rels != null) {
                for (String r : rels) {
                    relationships.add(refLabel(ref) + " " + r);
                }
            }
        }

        primaryFindings.sort(Comparator.comparingDouble(FindingElement::priority).reversed());
        secondaryFindings.sort(Comparator.comparingDouble(FindingElement::priority).reversed());
        proceduralFindings.sort(Comparator.comparingDouble(FindingElement::priority).reversed());
        supportingFindings.sort(Comparator.comparingDouble(FindingElement::priority).reversed());

        return new FindingHierarchy(
                primaryFindings, secondaryFindings,
                proceduralFindings, supportingFindings,
                relationships);
    }

    private double rolePriority(FindingRole role) {
        return switch (role) {
            case PRIMARY_FINDING -> 1.0;
            case SUPPORTING_FINDING -> 0.8;
            case PROCEDURAL_FINDING -> 0.6;
            case COLLATERAL_FINDING -> 0.5;
            case CONTEXTUAL_FINDING -> 0.4;
        };
    }

    private String refLabel(String ref) {
        return "Ref § " + ref;
    }

    private String roleDescription(String ref, FindingRole role) {
        return switch (role) {
            case PRIMARY_FINDING -> "Core finding — the primary basis";
            case SUPPORTING_FINDING -> "Supporting element — does not replace primary finding";
            case PROCEDURAL_FINDING -> "Procedural — actionable path to enforce";
            case CONTEXTUAL_FINDING -> "Contextual — provides background";
            case COLLATERAL_FINDING -> "Collateral instrument — supports but does not replace primary finding";
        };
    }
}
