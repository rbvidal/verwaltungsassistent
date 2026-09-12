package reasoning.document.api;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/** Service for storing, locating, and deleting document file content. */
public interface DocumentStorageService {
    /** Stores document content and returns storage provider details. */
    StoredDocument store(DocumentStorageRequest request);

    /** Finds a storage reference for a document version. */
    Optional<DocumentStorageReference> find(UUID documentId, int versionNumber);

    /** Deletes the stored content for a document version. */
    void delete(UUID documentId, int versionNumber);

    /** Request to store document content with file metadata and input stream. */
    record DocumentStorageRequest(
            UUID documentId,
            int versionNumber,
            String fileName,
            String contentType,
            long sizeBytes,
            InputStream content
    ) {
    }

    /** Result of a storage operation with provider, key, checksum, and size. */
    record StoredDocument(
            String storageProvider,
            String storageKey,
            String checksumSha256,
            long sizeBytes
    ) {
    }

    /** Reference to a stored document by provider and key. */
    record DocumentStorageReference(
            String storageProvider,
            String storageKey
    ) {
    }
}
