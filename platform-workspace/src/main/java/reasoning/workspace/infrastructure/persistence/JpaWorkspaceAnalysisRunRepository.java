package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository for persisted, versioned analysis runs of a workspace. */
public interface JpaWorkspaceAnalysisRunRepository extends JpaRepository<WorkspaceAnalysisRunEntity, UUID> {

    List<WorkspaceAnalysisRunEntity> findByWorkspaceIdOrderByVersionAsc(UUID workspaceId);

    Optional<WorkspaceAnalysisRunEntity> findFirstByWorkspaceIdOrderByVersionDesc(UUID workspaceId);

    Optional<WorkspaceAnalysisRunEntity> findByWorkspaceIdAndVersion(UUID workspaceId, int version);

    long countByWorkspaceId(UUID workspaceId);
}
