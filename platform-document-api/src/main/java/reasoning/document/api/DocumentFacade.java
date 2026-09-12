package reasoning.document.api;

import reasoning.document.model.Document;

import java.util.UUID;

/**
 * Facade for document lifecycle management, extending ingestion capabilities.
 */
public interface DocumentFacade extends DocumentIngestionService {
    /** Creates a new document from the given command. */
    Document createDocument(CreateDocumentCommand command);

    /** Retrieves a document by ID, verifying view permission. */
    Document getDocument(UUID documentId, String actorId);

    /** Queries documents with filtering and pagination. */
    DocumentPage findDocuments(DocumentFilter filter);

    /** Updates a document's metadata. */
    Document updateMetadata(UpdateDocumentMetadataCommand command);

    /** Adds a new version to a document. */
    Document addVersion(AddDocumentVersionCommand command);

    /** Archives a document by ID. */
    Document archiveDocument(UUID documentId, String actorId);

    /**
     * Marks a document obsolete (no longer an active knowledge source; the
     * source file and record are retained for the audit trail).
     */
    Document markObsoleteDocument(UUID documentId, String actorId);

    /** Soft-deletes a document by ID. */
    Document deleteDocument(UUID documentId, String actorId);
}
