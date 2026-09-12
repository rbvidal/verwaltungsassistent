package reasoning.workspace.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for one domain-agnostic structured knowledge item extracted
 * from a document. The column naming follows the workspace persistence
 * conventions (snake_case, {@code item_key} because KEY is reserved in H2).
 */
@Entity
@Table(name = "structured_knowledge_items")
public class StructuredKnowledgeItemEntity {

    @Id
    private UUID id;

    /** TABLE | THRESHOLD | RULE | DEFINITION | EXCEPTION | EFFECTIVE_DATE */
    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String domain;

    /** Identity declared by the source document (e.g. the regulation name as extracted). */
    @Column(name = "item_key", nullable = false)
    private String key;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    /** CANDIDATE | VALIDATED | ACTIVE | REJECTED | SUPERSEDED */
    @Column(nullable = false)
    private String status;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_until")
    private LocalDate effectiveUntil;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "source_document_version", nullable = false)
    private int sourceDocumentVersion;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(name = "source_chunk_index")
    private Integer sourceChunkIndex;

    @Column(name = "source_excerpt", columnDefinition = "text")
    private String sourceExcerpt;

    @Column(name = "extraction_confidence")
    private double extractionConfidence;

    @Column(name = "extracted_by")
    private String extractedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "superseded_by")
    private UUID supersededById;

    protected StructuredKnowledgeItemEntity() {
    }

    public StructuredKnowledgeItemEntity(
            UUID id,
            String kind,
            String domain,
            String key,
            String payloadJson,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            UUID sourceDocumentId,
            int sourceDocumentVersion,
            Integer sourcePage,
            Integer sourceChunkIndex,
            String sourceExcerpt,
            double extractionConfidence,
            String extractedBy,
            UUID supersededById) {
        this.id = id;
        this.kind = kind;
        this.domain = domain;
        this.key = key;
        this.payloadJson = payloadJson;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.sourceDocumentId = sourceDocumentId;
        this.sourceDocumentVersion = sourceDocumentVersion;
        this.sourcePage = sourcePage;
        this.sourceChunkIndex = sourceChunkIndex;
        this.sourceExcerpt = sourceExcerpt;
        this.extractionConfidence = extractionConfidence;
        this.extractedBy = extractedBy;
        this.supersededById = supersededById;
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

    public void setStatus(String status) { this.status = status; }

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public String getDomain() { return domain; }
    public String getKey() { return key; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveUntil() { return effectiveUntil; }
    public UUID getSourceDocumentId() { return sourceDocumentId; }
    public int getSourceDocumentVersion() { return sourceDocumentVersion; }
    public Integer getSourcePage() { return sourcePage; }
    public Integer getSourceChunkIndex() { return sourceChunkIndex; }
    public String getSourceExcerpt() { return sourceExcerpt; }
    public double getExtractionConfidence() { return extractionConfidence; }
    public String getExtractedBy() { return extractedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getSupersededById() { return supersededById; }
}
