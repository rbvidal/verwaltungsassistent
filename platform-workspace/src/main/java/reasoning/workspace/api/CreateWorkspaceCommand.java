package reasoning.workspace.api;

/** Command to create a new workspace with name, description, type, and creator. */
public record CreateWorkspaceCommand(
        String name,
        String description,
        String workspaceType,
        String createdBy
) {}
