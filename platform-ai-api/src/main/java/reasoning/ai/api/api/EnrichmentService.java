package reasoning.ai.api;

import reasoning.ai.model.EnrichmentContext;

/**
 * SPI for semantic enrichment of document text during ingestion.
 * Extracts entities, concepts, relationships, and taxonomy from raw text.
 * Results are automatically persisted to the knowledge graph when available.
 */
public interface EnrichmentService {

    /**
     * Enriches the given text with extracted semantic information.
     * @param documentId the document being enriched
     * @param text the raw text content
     * @return enrichment context containing entities, concepts, and relationships
     */
    EnrichmentContext enrich(String documentId, String text);
}
