package reasoning.document.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity representing a document version with file, storage, and creator metadata. */
@Entity
@Table(name = "document_versions")
public class DocumentVersionEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_provider", nullable = false)
    private String storageProvider;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentVersionEntity() {
    }

    public DocumentVersionEntity(
            int versionNumber,
            String fileName,
            String contentType,
            long sizeBytes,
            String storageProvider,
            String storageKey,
            String checksumSha256,
            String createdBy
    ) {
        this.id = UUID.randomUUID();
        this.versionNumber = versionNumber;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageProvider = storageProvider;
        this.storageKey = storageKey;
        this.checksumSha256 = checksumSha256;
        this.createdBy = createdBy;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    void attachTo(DocumentEntity document) {
        this.document = document;
    }

    public UUID getId() {
        return id;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
