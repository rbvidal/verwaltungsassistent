package reasoning.document.api;

import reasoning.document.model.Document;

import java.util.Optional;
import java.util.UUID;

/** Repository SPI for persisting and querying documents. */
public interface DocumentRepository {
    /** Persists a document and returns it. */
    Document save(Document document);

    /** Finds a document by its UUID. */
    Optional<Document> findById(UUID id);

    /** Queries documents with filtering and pagination. */
    DocumentPage find(DocumentFilter filter);
}
