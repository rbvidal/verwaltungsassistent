package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link WorkspaceEntity} with owner-based lookup. */
@Repository
public interface JpaWorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {
    /** Finds workspaces belonging to a given owner. */
    List<WorkspaceEntity> findByOwnerId(String ownerId);
}
