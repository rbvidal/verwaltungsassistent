package reasoning.document.api;

import java.util.UUID;

/** Processor SPI for executing document ingestion logic (e.g., chunking, indexing). */
public interface DocumentIngestionProcessor {

    /** Ingests the document identified by the given ID. */
    void ingest(UUID documentId);
}
