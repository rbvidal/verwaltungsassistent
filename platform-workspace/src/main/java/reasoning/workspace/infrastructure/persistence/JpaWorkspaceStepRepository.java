package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.WorkspaceStepEntity;
import reasoning.common.model.WorkspacePhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link WorkspaceStepEntity} with workspace and phase-based lookups. */
@Repository
public interface JpaWorkspaceStepRepository extends JpaRepository<WorkspaceStepEntity, UUID> {
    /** Finds steps for a workspace ordered by completion time. */
    List<WorkspaceStepEntity> findByWorkspaceIdOrderByCompletedAt(UUID workspaceId);
    /** Finds a step by workspace ID and phase. */
    Optional<WorkspaceStepEntity> findByWorkspaceIdAndPhase(UUID workspaceId, WorkspacePhase phase);
    /** Deletes all steps belonging to a workspace. */
    void deleteByWorkspaceId(UUID workspaceId);
}
