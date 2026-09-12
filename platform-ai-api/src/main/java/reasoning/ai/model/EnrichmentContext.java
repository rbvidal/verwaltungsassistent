package reasoning.ai.model;

import java.util.ArrayList;
import java.util.List;

/** The result of semantic enrichment on a document. */
public class EnrichmentContext {

    private final String documentId;
    private final List<ExtractedEntity> entities = new ArrayList<>();
    private final List<ExtractedConcept> concepts = new ArrayList<>();
    private final List<EntityRelationship> relationships = new ArrayList<>();

    public EnrichmentContext(String documentId) { this.documentId = documentId; }

    public String getDocumentId() { return documentId; }
    public List<ExtractedEntity> getEntities() { return entities; }
    public List<ExtractedConcept> getConcepts() { return concepts; }
    public List<EntityRelationship> getRelationships() { return relationships; }

    public void addEntity(ExtractedEntity e) { entities.add(e); }
    public void addConcept(ExtractedConcept c) { concepts.add(c); }
    public void addRelationship(EntityRelationship r) { relationships.add(r); }

    /** A named entity extracted from text. */
    public record ExtractedEntity(
            String name, String type, double confidence,
            List<String> mentions, String context
    ) {}

    /** A concept or topic identified in text. */
    public record ExtractedConcept(
            String label, String domain, double confidence,
            List<String> relatedEntities
    ) {}

    /** A relationship between two entities or concepts. */
    public record EntityRelationship(
            String sourceId, String targetId, String type,
            double confidence, String evidence
    ) {}
}
