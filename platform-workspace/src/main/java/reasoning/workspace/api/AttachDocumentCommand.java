package reasoning.workspace.api;

import reasoning.common.model.DocumentCategory;

/** Command to attach a document to a workspace. */
public record AttachDocumentCommand(
        String workspaceId,
        String documentId,
        DocumentCategory documentType,
        String documentCategory,
        String notes
) {}
