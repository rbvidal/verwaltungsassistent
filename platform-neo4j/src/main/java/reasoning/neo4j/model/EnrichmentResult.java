package reasoning.neo4j.model;

import java.util.ArrayList;
import java.util.List;

/** The result of semantic enrichment on a document, containing extracted nodes and relationships. */
public class EnrichmentResult {

    private final String documentId;
    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphRelationship> relationships = new ArrayList<>();

    public EnrichmentResult(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentId() { return documentId; }
    public List<GraphNode> getNodes() { return nodes; }
    public List<GraphRelationship> getRelationships() { return relationships; }

    public void addNode(GraphNode node) { nodes.add(node); }
    public void addRelationship(GraphRelationship rel) { relationships.add(rel); }
}
