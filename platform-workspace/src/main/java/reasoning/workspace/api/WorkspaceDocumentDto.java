package reasoning.workspace.api;

import reasoning.common.model.DocumentCategory;

import java.time.Instant;
import java.util.Map;

/** DTO representing a document linked to a workspace. */
public record WorkspaceDocumentDto(
        String id,
        String workspaceId,
        String documentId,
        String documentName,
        DocumentCategory documentType,
        String documentCategory,
        Map<String, Object> extractedMetadata,
        Instant uploadedAt
) {}
