package reasoning.neo4j.model;

import java.util.Map;

/**
 * A relationship between two nodes in the knowledge graph.
 * Carries provenance to enable full auditability of graph edges.
 */
public class GraphRelationship {

    private String sourceId;
    private String targetId;
    private RelationshipType type;
    private Map<String, Object> properties;
    private GraphNode.NodeProvenance provenance;

    public GraphRelationship() {}

    public GraphRelationship(String sourceId, String targetId, RelationshipType type,
                              Map<String, Object> properties) {
        this(sourceId, targetId, type, properties, null);
    }

    public GraphRelationship(String sourceId, String targetId, RelationshipType type,
                              Map<String, Object> properties, GraphNode.NodeProvenance provenance) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.type = type;
        this.properties = properties != null ? Map.copyOf(properties) : Map.of();
        this.provenance = provenance;
    }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public RelationshipType getType() { return type; }
    public void setType(RelationshipType type) { this.type = type; }
    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    public GraphNode.NodeProvenance getProvenance() { return provenance; }
    public void setProvenance(GraphNode.NodeProvenance provenance) { this.provenance = provenance; }

    public enum RelationshipType {
        REFERENCES, DEPENDS_ON, PART_OF, RELATED_TO,
        IMPLEMENTS, USES, MENTIONS, BELONGS_TO, DERIVED_FROM
    }
}
