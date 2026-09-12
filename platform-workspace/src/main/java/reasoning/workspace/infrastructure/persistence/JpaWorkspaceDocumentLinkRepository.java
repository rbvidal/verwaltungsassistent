package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link WorkspaceDocumentLinkEntity} with workspace-based lookup. */
@Repository
public interface JpaWorkspaceDocumentLinkRepository extends JpaRepository<WorkspaceDocumentLinkEntity, UUID> {
    /** Finds all document links for a given workspace. */
    List<WorkspaceDocumentLinkEntity> findByWorkspaceId(UUID workspaceId);
}
