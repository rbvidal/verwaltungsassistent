package reasoning.auth.api;

/**
 * Facade for authentication and token management operations.
 */
public interface AuthFacade {
    /** Registers a new user account and returns auth tokens. */
    AuthTokens register(RegisterUserCommand command);

    /** Authenticates a user and returns auth tokens. */
    AuthTokens login(LoginCommand command);

    /** Refreshes an access token using a refresh token. */
    AuthTokens refresh(RefreshTokenCommand command);

    /** Logs out by revoking the given refresh token. */
    void logout(LogoutCommand command);

    /** Returns the currently authenticated user by email. */
    AuthenticatedUser currentUser(String email);
}
