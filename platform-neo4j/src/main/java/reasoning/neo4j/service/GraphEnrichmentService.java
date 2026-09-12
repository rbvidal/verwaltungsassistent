package reasoning.neo4j.service;

import reasoning.neo4j.config.Neo4jProperties;
import reasoning.neo4j.model.EnrichmentResult;
import reasoning.neo4j.model.GraphNode;
import reasoning.neo4j.model.GraphRelationship;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists semantic enrichment results to Neo4j.
 * Auto-generates graph from documents — no manual CRUD.
 * Degrades gracefully when Neo4j is unavailable.
 */
@Service
@ConditionalOnBean(Driver.class)
public class GraphEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(GraphEnrichmentService.class);

    private final ObjectProvider<Driver> driverProvider;

    public GraphEnrichmentService(ObjectProvider<Driver> driverProvider) {
        this.driverProvider = driverProvider;
    }

    /** Returns true if Neo4j is connected. */
    public boolean isAvailable() {
        try {
            Driver driver = driverProvider.getIfAvailable();
            if (driver == null) return false;
            driver.verifyConnectivity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Persists an enrichment result to the graph. */
    public void persist(EnrichmentResult result) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            log.debug("Neo4j not available — skipping enrichment persist for document {}", result.getDocumentId());
            return;
        }
        try (Session session = driver.session()) {
            for (GraphNode node : result.getNodes()) {
                upsertNode(session, node);
            }
            for (GraphRelationship rel : result.getRelationships()) {
                createRelationship(session, rel);
            }
            log.debug("Persisted {} nodes and {} relationships for document {}",
                    result.getNodes().size(), result.getRelationships().size(), result.getDocumentId());
        } catch (Exception e) {
            log.warn("Failed to persist enrichment to Neo4j for document {}: {}",
                    result.getDocumentId(), e.getMessage());
        }
    }

    /** Traverses the graph from a set of seed node IDs, returning related nodes up to maxDepth hops. */
    public List<GraphNode> traverse(List<String> seedIds, int maxDepth) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null || seedIds.isEmpty()) return List.of();
        try (Session session = driver.session()) {
            var result = session.run(
                    "MATCH (n) WHERE n.id IN $seedIds " +
                    "MATCH (n)-[*1.." + maxDepth + "]-(related) " +
                    "RETURN DISTINCT related.id AS id, labels(related) AS labels, properties(related) AS props",
                    Map.of("seedIds", seedIds));
            return result.stream()
                    .map(r -> new GraphNode(
                            r.get("id").asString(),
                            parseNodeType(r.get("labels").asList()),
                            r.get("props").get("label", "?").toString(),
                            r.get("props").asMap()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Graph traversal failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Returns all document IDs related to the given document through chunk/concept/entity links. */
    public List<String> findRelatedDocuments(String documentId, int maxDepth) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) return List.of();
        try (Session session = driver.session()) {
            var result = session.run(
                    "MATCH (d:DOCUMENT {id: $docId})-[*1.." + maxDepth + "]-(other:DOCUMENT) " +
                    "RETURN DISTINCT other.id AS id",
                    Map.of("docId", documentId));
            return result.stream().map(r -> r.get("id").asString()).toList();
        } catch (Exception e) {
            log.debug("Related documents query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Searches for Document nodes whose label, title, or category match any of the given keywords. */
    public List<GraphNode> searchDocumentsByKeywords(List<String> keywords, int maxResults) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null || keywords.isEmpty()) return List.of();
        try (Session session = driver.session()) {
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) where.append(" OR ");
                String k = "$kw" + i;
                where.append("toLower(d.label) CONTAINS toLower(").append(k).append(")");
                where.append(" OR toLower(d.title) CONTAINS toLower(").append(k).append(")");
                where.append(" OR toLower(d.category) CONTAINS toLower(").append(k).append(")");
                where.append(" OR toLower(d.tags) CONTAINS toLower(").append(k).append(")");
            }
            var result = session.run(
                    "MATCH (d:DOCUMENT) WHERE " + where +
                    " RETURN d.id AS id, labels(d) AS labels, properties(d) AS props" +
                    " LIMIT " + maxResults,
                    buildKeywordParams(keywords));
            return result.stream()
                    .map(r -> new GraphNode(
                            r.get("id").asString(),
                            GraphNode.NodeType.DOCUMENT,
                            r.get("props").get("label", "?").toString(),
                            r.get("props").asMap()))
                    .toList();
        } catch (Exception e) {
            log.debug("Keyword document search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Deletes all nodes and relationships related to a document. */
    public void deleteDocumentNodes(String documentId) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) return;
        try (Session session = driver.session()) {
            session.run(
                    "MATCH (n {docId: $docId}) DETACH DELETE n",
                    Map.of("docId", documentId));
            session.run(
                    "MATCH (n {id: $docId})-[r]-(related) " +
                    "WHERE related:CHUNK OR related:REGULATION OR related:ORGANIZATION " +
                    "DETACH DELETE r, related",
                    Map.of("docId", documentId));
            session.run(
                    "MATCH (n {id: $docId}) DETACH DELETE n",
                    Map.of("docId", documentId));
        } catch (Exception e) {
            log.debug("Failed to delete graph nodes for document {}: {}", documentId, e.getMessage());
        }
    }

    /**
     * Deletes every document-derived node (DOCUMENT, CHUNK, REGULATION,
     * ORGANIZATION) including orphans from replaced uploads. Static knowledge
     * nodes (CONCEPT, TECHNOLOGY, …) are not derived from documents and stay.
     */
    public void deleteAllDocumentNodes() {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) return;
        try (Session session = driver.session()) {
            session.run("MATCH (n) WHERE n:DOCUMENT OR n:CHUNK OR n:REGULATION OR n:ORGANIZATION DETACH DELETE n");
        } catch (Exception e) {
            log.debug("Failed to delete document-derived graph nodes: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildKeywordParams(List<String> keywords) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keywords.size(); i++) {
            params.put("kw" + i, keywords.get(i));
        }
        return params;
    }

    private void upsertNode(Session session, GraphNode node) {
        session.run("MERGE (n:" + node.getType().name() + " {id: $id}) " +
                "SET n += $props",
                Values.parameters("id", node.getId(), "props", node.getProperties()));
    }

    private void createRelationship(Session session, GraphRelationship rel) {
        session.run("MATCH (a {id: $sourceId}), (b {id: $targetId}) " +
                "MERGE (a)-[r:" + rel.getType().name() + "]->(b) " +
                "SET r += $props",
                Values.parameters(
                        "sourceId", rel.getSourceId(),
                        "targetId", rel.getTargetId(),
                        "props", rel.getProperties() != null ? rel.getProperties() : Map.of()));
    }

    private GraphNode.NodeType parseNodeType(List<Object> labels) {
        if (labels == null || labels.isEmpty()) return GraphNode.NodeType.CONCEPT;
        String label = labels.get(0).toString().toUpperCase();
        try { return GraphNode.NodeType.valueOf(label); }
        catch (IllegalArgumentException e) { return GraphNode.NodeType.CONCEPT; }
    }
}
