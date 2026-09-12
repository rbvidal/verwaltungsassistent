package reasoning.workspace.infrastructure.persistence;

import reasoning.workspace.api.TimelineEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link TimelineEventEntity} with workspace-based lookup and deletion. */
@Repository
public interface JpaTimelineEventRepository extends JpaRepository<TimelineEventEntity, UUID> {
    /**
     * Finds timeline events for a workspace in deterministic chronological
     * order: event date ASC, then creation time ASC (Phase 2D.6). Multiple
     * events on the SAME day (the typical demo lifecycle) are thereby ordered
     * by their actual creation sequence, not by unspecified database order.
     */
    List<TimelineEventEntity> findByWorkspaceIdOrderByEventDateAscCreatedAtAsc(UUID workspaceId);
    /** Deletes all timeline events belonging to a workspace. */
    void deleteByWorkspaceId(UUID workspaceId);
}
