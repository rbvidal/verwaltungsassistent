package reasoning.neo4j.model;

import java.time.Instant;
import java.util.Map;

/**
 * A node in the knowledge graph generated from document enrichment.
 * Every node carries provenance information linking it back to its source document and extraction method.
 */
public class GraphNode {

    private String id;
    private NodeType type;
    private String label;
    private Map<String, Object> properties;
    private NodeProvenance provenance;

    public GraphNode() {}

    public GraphNode(String id, NodeType type, String label, Map<String, Object> properties) {
        this(id, type, label, properties, null);
    }

    public GraphNode(String id, NodeType type, String label, Map<String, Object> properties,
                      NodeProvenance provenance) {
        this.id = id;
        this.type = type;
        this.label = label;
        this.properties = properties != null ? Map.copyOf(properties) : Map.of();
        this.provenance = provenance;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public NodeType getType() { return type; }
    public void setType(NodeType type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    public NodeProvenance getProvenance() { return provenance; }
    public void setProvenance(NodeProvenance provenance) { this.provenance = provenance; }

    /**
     * Provenance metadata linking a graph node back to its source document and extraction method.
     * Makes the knowledge graph fully auditable.
     */
    public record NodeProvenance(
            String sourceDocumentId,
            String chunkId,
            Integer chunkOffset,
            double extractionConfidence,
            Instant extractionTimestamp,
            String extractionModel,
            String promptVersion,
            String provider
    ) {}

    public enum NodeType {
        DOCUMENT, CHUNK, ENTITY, CONCEPT, TOPIC,
        ORGANIZATION, PERSON, TECHNOLOGY, REGULATION, PROJECT
    }
}
