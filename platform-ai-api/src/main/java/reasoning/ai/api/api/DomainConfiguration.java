package reasoning.ai.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pluggable domain configuration for customizing AI behavior per application domain.
 *
 * <p>The platform ships with a default general-purpose configuration. Applications
 * in specific domains (contract intelligence, financial analysis, regulatory compliance,
 * etc.) provide their own {@code DomainConfiguration} bean to customize concept extraction,
 * objective analysis, finding hierarchies, semantic centrality, and source orchestration.
 *
 * <p>This is the primary extension point for adapting the platform to new domains
 * without modifying core platform services.
 */
public interface DomainConfiguration {

    /** Domain identifier (e.g., "contract-intelligence", "financial-analysis"). */
    String domainId();

    /** Domain display name. */
    String displayName();

    /** Concepts relevant to this domain, each with keywords and governing references. */
    List<ConceptDefinition> concepts();

    /** Analysis objectives for this domain. */
    List<ObjectiveDefinition> objectives();

    /**
     * Finding hierarchy rules mapping governing references to finding roles.
     * Key: reference identifier, Value: finding role (PRIMARY, SUPPORTING, PROCEDURAL, etc.)
     */
    Map<String, String> findingRoleMapping();

    /** Relationships between findings in this domain. */
    List<FindingRelationship> findingRelationships();

    /** Centrality weights for authority references (0.0 = peripheral, 1.0 = central). */
    Map<String, Double> centralityWeights();

    /** Peripheral reference identifiers for this domain. */
    Set<String> peripheralReferences();

    /** Source roles and their associated keywords for classification. */
    Map<String, List<String>> roleKeywords();

    /** System instruction for domain-specific AI behavior. Null to use platform default. */
    String systemInstruction();

    /** Answer structure guidance. Null to use platform default. */
    String answerStructureGuidance();

    // ── Nested definition types ──

    /** A concept definition with keywords and governing references. */
    record ConceptDefinition(String label, List<String> keywords,
                              List<String> governingReferences, List<String> relatedConcepts) {}

    /** An analysis objective with label, keywords, and governing references. */
    record ObjectiveDefinition(String label, List<String> keywords,
                                List<String> governingReferences) {}

    /** A relationship between two findings. */
    record FindingRelationship(String sourceRef, String targetRef, String relationshipType) {}
}
