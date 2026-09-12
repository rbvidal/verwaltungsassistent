package verwaltungsassistent.web.ingestion;

import reasoning.ai.api.EnrichmentService;
import reasoning.ai.model.EnrichmentContext;
import reasoning.neo4j.model.EnrichmentResult;
import reasoning.neo4j.model.GraphNode;
import reasoning.neo4j.model.GraphRelationship;
import reasoning.neo4j.service.GraphEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class EnrichmentHook {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentHook.class);

    private final ObjectProvider<EnrichmentService> enrichmentProvider;
    private final ObjectProvider<GraphEnrichmentService> graphProvider;

    public EnrichmentHook(ObjectProvider<EnrichmentService> enrichmentProvider,
                          ObjectProvider<GraphEnrichmentService> graphProvider) {
        this.enrichmentProvider = enrichmentProvider;
        this.graphProvider = graphProvider;
    }

    public void enrich(String documentId, String documentName, String text) {
        EnrichmentService enricher = enrichmentProvider.getIfAvailable();
        if (enricher == null) {
            log.debug("Enrichment service not available — skipping for document {}", documentId);
            return;
        }
        GraphEnrichmentService graph = graphProvider.getIfAvailable();
        if (graph == null || !graph.isAvailable()) {
            log.debug("Graph service not available — skipping enrichment persist for document {}", documentId);
            return;
        }

        try {
            EnrichmentContext ctx = enricher.enrich(documentId, text);
            if (ctx.getEntities().isEmpty() && ctx.getConcepts().isEmpty()) {
                return;
            }

            EnrichmentResult result = new EnrichmentResult(documentId);
            result.addNode(new GraphNode(documentId, GraphNode.NodeType.DOCUMENT,
                    documentName != null ? documentName : documentId,
                    Map.of("id", documentId, "label", documentName)));

            String entityPrefix = documentId + "/entity/";
            for (var entity : ctx.getEntities()) {
                String entityId = entityPrefix + UUID.nameUUIDFromBytes(
                        (entity.name() + entity.type()).getBytes());
                result.addNode(new GraphNode(entityId, GraphNode.NodeType.valueOf(
                        mapEntityType(entity.type())), entity.name(),
                        Map.of("id", entityId, "label", entity.name(),
                                "type", entity.type(), "confidence", entity.confidence())));
                result.addRelationship(new GraphRelationship(documentId, entityId,
                        GraphRelationship.RelationshipType.MENTIONS,
                        Map.of("confidence", entity.confidence())));
            }

            String conceptPrefix = documentId + "/concept/";
            for (var concept : ctx.getConcepts()) {
                String conceptId = conceptPrefix + UUID.nameUUIDFromBytes(concept.label().getBytes());
                result.addNode(new GraphNode(conceptId, GraphNode.NodeType.CONCEPT,
                        concept.label(),
                        Map.of("id", conceptId, "label", concept.label(),
                                "domain", concept.domain(), "confidence", concept.confidence())));
                result.addRelationship(new GraphRelationship(documentId, conceptId,
                        GraphRelationship.RelationshipType.RELATED_TO,
                        Map.of("confidence", concept.confidence())));
            }

            for (var rel : ctx.getRelationships()) {
                result.addRelationship(new GraphRelationship(rel.sourceId(), rel.targetId(),
                        GraphRelationship.RelationshipType.valueOf(mapRelationshipType(rel.type())),
                        Map.of("confidence", rel.confidence(), "evidence", rel.evidence())));
            }

            graph.persist(result);
            log.info("Enrichment complete for document {}: {} entities, {} concepts, {} relationships",
                    documentId, ctx.getEntities().size(), ctx.getConcepts().size(), ctx.getRelationships().size());
        } catch (Exception e) {
            log.warn("Enrichment failed for document {}: {}", documentId, e.getMessage());
        }
    }

    private static String mapEntityType(String type) {
        return switch (type.toUpperCase()) {
            case "ORGANIZATION" -> "ORGANIZATION";
            case "PERSON" -> "PERSON";
            case "TECHNOLOGY" -> "TECHNOLOGY";
            case "REGULATION" -> "REGULATION";
            case "PROJECT" -> "PROJECT";
            default -> "ENTITY";
        };
    }

    private static String mapRelationshipType(String type) {
        return switch (type.toUpperCase()) {
            case "REFERENCES" -> "REFERENCES";
            case "DEPENDS_ON" -> "DEPENDS_ON";
            case "PART_OF" -> "PART_OF";
            case "USES" -> "USES";
            case "MENTIONS" -> "MENTIONS";
            case "BELONGS_TO" -> "BELONGS_TO";
            case "DERIVED_FROM" -> "DERIVED_FROM";
            default -> "RELATED_TO";
        };
    }
}
