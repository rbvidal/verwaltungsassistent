package reasoning.document.api;

import java.util.UUID;

/** Hook for verifying document-level permissions before operations. */
public interface DocumentPermissionHook {
    /** Verifies that an actor can manage (edit/delete) a document. */
    void verifyCanManage(String actorId, UUID documentId);

    /** Verifies that an actor can view a document. */
    void verifyCanView(String actorId, UUID documentId);
}
