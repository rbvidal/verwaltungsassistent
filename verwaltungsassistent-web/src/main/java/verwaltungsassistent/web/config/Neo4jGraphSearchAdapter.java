package verwaltungsassistent.web.config;

import reasoning.neo4j.model.GraphNode;
import reasoning.neo4j.service.GraphEnrichmentService;
import reasoning.search.api.GraphSearchProvider;
import reasoning.search.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Bridges Neo4j graph enrichment to the search pipeline's GraphSearchProvider SPI.
 * Activated only when GraphEnrichmentService is available (requires Neo4j Driver).
 * The service is resolved lazily via ObjectProvider so the adapter degrades
 * gracefully when bean-registration order prevents the service from existing.
 */
@Component
@ConditionalOnBean(GraphEnrichmentService.class)
public class Neo4jGraphSearchAdapter implements GraphSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphSearchAdapter.class);

    private final ObjectProvider<GraphEnrichmentService> graphServiceProvider;

    public Neo4jGraphSearchAdapter(ObjectProvider<GraphEnrichmentService> graphServiceProvider) {
        this.graphServiceProvider = graphServiceProvider;
    }

    private GraphEnrichmentService graphService() {
        return graphServiceProvider.getIfAvailable();
    }

    @Override
    public boolean isAvailable() {
        GraphEnrichmentService graphService = graphService();
        return graphService != null && graphService.isAvailable();
    }

    @Override
    public List<RetrievalCandidate> search(SearchQuery query) {
        GraphEnrichmentService graphService = graphService();
        if (graphService == null || !isAvailable() || query.query() == null || query.query().isBlank()) return List.of();
        try {
            List<String> keywords = extractKeywords(query.query());
            if (keywords.isEmpty()) return List.of();

            List<GraphNode> docNodes = graphService.searchDocumentsByKeywords(keywords, 10);
            if (docNodes.isEmpty()) return List.of();

            List<String> seedIds = docNodes.stream().map(GraphNode::getId).toList();
            List<GraphNode> relatedNodes = graphService.traverse(seedIds, 2);

            Set<String> seenIds = new HashSet<>();
            List<GraphNode> allNodes = new ArrayList<>();
            for (GraphNode n : docNodes) {
                if (seenIds.add(n.getId())) allNodes.add(n);
            }
            for (GraphNode n : relatedNodes) {
                if (seenIds.add(n.getId())) allNodes.add(n);
            }

            List<RetrievalCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < allNodes.size(); i++) {
                GraphNode node = allNodes.get(i);
                String label = node.getLabel() != null ? node.getLabel() : "";
                String docIdStr = (String) node.getProperties().getOrDefault("docId", node.getId());
                UUID chunkId = UUID.nameUUIDFromBytes((node.getId() + i).getBytes());
                UUID docUuid = safeUuid(docIdStr);

                ChunkReference chunkRef = new ChunkReference(chunkId, docUuid, 1,
                        label, new ChunkPosition(null, null, i, null, null));

                String excerpt = label.length() > 200 ? label.substring(0, 200) : label;
                CitationReference citation = new CitationReference(
                        docUuid, chunkId, 1, label,
                        null, null, null, excerpt);

                double graphScore = node.getType() == GraphNode.NodeType.DOCUMENT ? 0.6 : 0.5;

                candidates.add(new RetrievalCandidate(chunkRef, label,
                        0.0, 0.0, Math.min(1.0, graphScore),
                        0.3, "graph", citation));
            }
            log.info("GraphRAG: {} document hits + {} related = {} candidates (keywords: {})",
                    docNodes.size(), relatedNodes.size(), candidates.size(), keywords.size());
            return candidates;
        } catch (Exception e) {
            log.warn("Graph search failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<String> findRelatedDocuments(String documentId, int maxDepth) {
        GraphEnrichmentService graphService = graphService();
        if (graphService == null || !isAvailable()) return List.of();
        return graphService.findRelatedDocuments(documentId, maxDepth);
    }

    private List<String> extractKeywords(String query) {
        return Arrays.stream(query.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 3)
                .distinct()
                .limit(10)
                .toList();
    }

    private static UUID safeUuid(String s) {
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return UUID.nameUUIDFromBytes(s.getBytes()); }
    }
}
