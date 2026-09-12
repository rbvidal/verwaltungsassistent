package reasoning.workspace.api;

import reasoning.common.model.DocumentCategory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** JPA entity linking a document to a workspace. */
@Entity
@Table(name = "workspace_documents")
public class WorkspaceDocumentLinkEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_name")
    private String documentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentCategory documentType;

    @Column(name = "document_category")
    private String documentCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_metadata", columnDefinition = "jsonb")
    private String extractedMetadata;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public WorkspaceDocumentLinkEntity() {}

    public WorkspaceDocumentLinkEntity(String id, String workspaceId, String documentId, String documentName,
                                       DocumentCategory documentType, String documentCategory) {
        this.id = id != null ? UUID.fromString(id) : UUID.randomUUID();
        this.workspaceId = UUID.fromString(workspaceId);
        this.documentId = UUID.fromString(documentId);
        this.documentName = documentName;
        this.documentType = documentType;
        this.documentCategory = documentCategory != null ? documentCategory : "general";
        this.extractedMetadata = "{}";
        this.uploadedAt = Instant.now();
    }

    public UUID getUuid() { return id; }
    public String getId() { return id != null ? id.toString() : null; }
    public void setId(String id) { this.id = id != null ? UUID.fromString(id) : null; }

    public UUID getWorkspaceUuid() { return workspaceId; }
    public String getWorkspaceId() { return workspaceId != null ? workspaceId.toString() : null; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = UUID.fromString(workspaceId); }

    public UUID getDocumentUuid() { return documentId; }
    public String getDocumentId() { return documentId != null ? documentId.toString() : null; }
    public void setDocumentId(String documentId) { this.documentId = UUID.fromString(documentId); }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public DocumentCategory getDocumentType() { return documentType; }
    public void setDocumentType(DocumentCategory documentType) { this.documentType = documentType; }
    public String getDocumentCategory() { return documentCategory; }
    public void setDocumentCategory(String documentCategory) { this.documentCategory = documentCategory; }
    public String getExtractedMetadata() { return extractedMetadata; }
    public void setExtractedMetadata(String extractedMetadata) { this.extractedMetadata = extractedMetadata; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
