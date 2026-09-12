package reasoning.search.infrastructure.persistence;

import reasoning.common.model.DocumentFileType;
import reasoning.search.model.ChunkType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** JPA entity storing a document chunk with position, type, text, metadata, tags, and embedding reference. */
@Entity
@Table(name = "search_document_chunks")
public class DocumentChunkEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_version", nullable = false)
    private int documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", nullable = false)
    private ChunkType chunkType;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "section_index")
    private Integer sectionIndex;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "start_offset")
    private Integer startOffset;

    @Column(name = "end_offset")
    private Integer endOffset;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentFileType documentType;

    private String category;

    private String source;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "document_created_at")
    private Instant documentCreatedAt;

    @Column(name = "published_at")
    private LocalDate publishedAt;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "superseded_at")
    private LocalDate supersededAt;

    @Column(name = "embedding_reference")
    private String embeddingReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "search_document_chunk_tags", joinColumns = @JoinColumn(name = "chunk_id"))
    @Column(name = "tag", nullable = false)
    private Set<String> tags = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "search_document_chunk_metadata", joinColumns = @JoinColumn(name = "chunk_id"))
    private Set<MetadataAttributeEmbeddable> attributes = new HashSet<>();

    protected DocumentChunkEntity() {
    }

    public DocumentChunkEntity(
            UUID id,
            UUID documentId,
            int documentVersion,
            ChunkType chunkType,
            String text,
            Integer pageNumber,
            Integer sectionIndex,
            int chunkIndex,
            Integer startOffset,
            Integer endOffset,
            String title,
            DocumentFileType documentType,
            String category,
            Set<String> tags,
            String source,
            String tenantId,
            Instant documentCreatedAt,
            Set<MetadataAttributeEmbeddable> attributes,
            String embeddingReference,
            LocalDate publishedAt,
            LocalDate validFrom,
            LocalDate validUntil,
            LocalDate supersededAt
    ) {
        this.id = id;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.chunkType = chunkType;
        this.text = text;
        this.pageNumber = pageNumber;
        this.sectionIndex = sectionIndex;
        this.chunkIndex = chunkIndex;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.title = title;
        this.documentType = documentType;
        this.category = category;
        this.tags = new HashSet<>(tags);
        this.source = source;
        this.tenantId = tenantId;
        this.documentCreatedAt = documentCreatedAt;
        this.attributes = new HashSet<>(attributes);
        this.embeddingReference = embeddingReference;
        this.publishedAt = publishedAt;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.supersededAt = supersededAt;
    }

    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public DocumentChunkEntity(
            UUID id,
            UUID documentId,
            int documentVersion,
            ChunkType chunkType,
            String text,
            Integer pageNumber,
            Integer sectionIndex,
            int chunkIndex,
            Integer startOffset,
            Integer endOffset,
            String title,
            DocumentFileType documentType,
            String category,
            Set<String> tags,
            String source,
            String tenantId,
            Instant documentCreatedAt,
            Set<MetadataAttributeEmbeddable> attributes,
            String embeddingReference
    ) {
        this(id, documentId, documentVersion, chunkType, text, pageNumber, sectionIndex, chunkIndex,
                startOffset, endOffset, title, documentType, category, tags, source, tenantId,
                documentCreatedAt, attributes, embeddingReference, null, null, null, null);
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getDocumentVersion() {
        return documentVersion;
    }

    public ChunkType getChunkType() {
        return chunkType;
    }

    public String getText() {
        return text;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public Integer getSectionIndex() {
        return sectionIndex;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public Integer getStartOffset() {
        return startOffset;
    }

    public Integer getEndOffset() {
        return endOffset;
    }

    public String getTitle() {
        return title;
    }

    public DocumentFileType getDocumentType() {
        return documentType;
    }

    public String getCategory() {
        return category;
    }

    public Set<String> getTags() {
        return Set.copyOf(tags);
    }

    public String getSource() {
        return source;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getDocumentCreatedAt() {
        return documentCreatedAt;
    }

    public LocalDate getPublishedAt() {
        return publishedAt;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public LocalDate getSupersededAt() {
        return supersededAt;
    }

    public Set<MetadataAttributeEmbeddable> getAttributes() {
        return Set.copyOf(attributes);
    }

    public String getEmbeddingReference() {
        return embeddingReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
