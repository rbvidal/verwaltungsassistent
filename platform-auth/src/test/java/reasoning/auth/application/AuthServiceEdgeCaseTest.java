package reasoning.auth.application;

import reasoning.auth.api.*;
import reasoning.auth.infrastructure.persistence.RefreshTokenSessionEntity;
import reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.auth.model.Role;
import reasoning.auth.security.AuthProperties;
import reasoning.auth.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Security audit edge case tests for AuthService.
 * Covers token rotation safety, concurrent refresh, disabled users,
 * and logout invalidation edge cases.
 */
class AuthServiceEdgeCaseTest {

    private AuthService authService;
    private UserAccountRepository users;
    private RefreshTokenSessionRepository refreshTokens;
    private JwtTokenService tokenService;

    private UserAccountEntity activeUser;
    private UserAccountEntity lockedUser;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        refreshTokens = mock(RefreshTokenSessionRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);

        AuthProperties props = new AuthProperties();
        props.setJwtSecret("test-secret-that-is-at-least-32-bytes-long-for-hs256");
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        props.setRefreshTokenTtl(Duration.ofDays(30));

        // Create a real JwtTokenService with a real encoder
        JwtEncoder jwtEncoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                        new javax.crypto.spec.SecretKeySpec(
                                props.getJwtSecret().getBytes(), "HmacSHA256")));
        tokenService = new JwtTokenService(jwtEncoder, props);

        // Default save behavior: return the saved entity
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthAuditPublisher auditPublisher = mock(AuthAuditPublisher.class);
        authService = new AuthService(users, refreshTokens, encoder, tokenService, auditPublisher,
                new LoginAttemptGuard(props));

        activeUser = new UserAccountEntity("active@test.com",
                encoder.encode("password123"), "Active User", Set.of(Role.USER));
        lockedUser = new UserAccountEntity("locked@test.com",
                encoder.encode("password123"), "Locked User", Set.of(Role.USER));
        lockedUser.setLocked(true);
    }

    // ── Token rotation ──

    @Test
    void refreshShouldRotateTokens() {
        var session = new RefreshTokenSessionEntity(activeUser,
                tokenService.hashRefreshToken("old-refresh-token"),
                Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1", "test-agent");

        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(session));

        AuthTokens result = authService.refresh(new RefreshTokenCommand("old-refresh-token", null, null));

        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertNotEquals("old-refresh-token", result.refreshToken());

        // Old session should be revoked
        assertFalse(session.isActiveAt(Instant.now()));
    }

    @Test
    void refreshShouldRejectRevokedToken() {
        var session = new RefreshTokenSessionEntity(activeUser,
                tokenService.hashRefreshToken("revoked-token"),
                Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1", "test-agent");
        session.revoke(Instant.now(), null);

        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(session));

        assertThrows(BadCredentialsException.class, () ->
                authService.refresh(new RefreshTokenCommand("revoked-token", null, null)));
    }

    @Test
    void refreshShouldRejectExpiredToken() {
        var session = new RefreshTokenSessionEntity(activeUser,
                tokenService.hashRefreshToken("expired-token"),
                Instant.now().minus(Duration.ofDays(60)),
                Instant.now().minus(Duration.ofDays(30)),
                "127.0.0.1", "test-agent");

        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(session));

        assertThrows(BadCredentialsException.class, () ->
                authService.refresh(new RefreshTokenCommand("expired-token", null, null)));
    }

    @Test
    void refreshShouldRejectInvalidTokenHash() {
        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () ->
                authService.refresh(new RefreshTokenCommand("non-existent-token", null, null)));
    }

    // ── Disabled / locked user during refresh ──

    @Test
    void refreshShouldRejectLockedUser() {
        var session = new RefreshTokenSessionEntity(lockedUser,
                tokenService.hashRefreshToken("locked-user-token"),
                Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1", "test-agent");

        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(session));

        assertThrows(BadCredentialsException.class, () ->
                authService.refresh(new RefreshTokenCommand("locked-user-token", null, null)));
        // The session should be revoked
        assertFalse(session.isActiveAt(Instant.now()));
    }

    // ── Login edge cases ──

    @Test
    void loginShouldRejectLockedUser() {
        UserAccountEntity locked = new UserAccountEntity("locked@test.com",
                "encoded", "Locked", Set.of(Role.USER));
        locked.setLocked(true);

        when(users.findByEmail("locked@test.com")).thenReturn(Optional.of(locked));

        assertThrows(BadCredentialsException.class, () ->
                authService.login(new LoginCommand("locked@test.com", "password", null, null)));
    }

    @Test
    void loginShouldRejectDisabledUser() {
        UserAccountEntity disabled = new UserAccountEntity("disabled@test.com",
                "encoded", "Disabled", Set.of(Role.USER));
        disabled.setEnabled(false);

        when(users.findByEmail("disabled@test.com")).thenReturn(Optional.of(disabled));

        assertThrows(BadCredentialsException.class, () ->
                authService.login(new LoginCommand("disabled@test.com", "password", null, null)));
    }

    @Test
    void loginShouldRejectWrongPassword() {
        PasswordEncoder realEncoder = new BCryptPasswordEncoder(4);
        UserAccountEntity user = new UserAccountEntity("user@test.com",
                realEncoder.encode("correct-password"), "User", Set.of(Role.USER));

        when(users.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class, () ->
                authService.login(new LoginCommand("user@test.com", "wrong-password", null, null)));
    }

    @Test
    void loginShouldReportBlockedAccountOnlyForValidCredentials() {
        PasswordEncoder realEncoder = new BCryptPasswordEncoder(4);
        UserAccountEntity locked = new UserAccountEntity("locked@test.com",
                realEncoder.encode("correct-password"), "Locked", Set.of(Role.USER));
        locked.setLocked(true);
        when(users.findByEmail("locked@test.com")).thenReturn(Optional.of(locked));

        AccountLockedException ex = assertThrows(AccountLockedException.class, () ->
                authService.login(new LoginCommand("locked@test.com", "correct-password", null, null)));
        assertEquals(AccountLockedException.MESSAGE, ex.getMessage());
    }

    @Test
    void loginShouldNotRevealBlockedStateForWrongPassword() {
        PasswordEncoder realEncoder = new BCryptPasswordEncoder(4);
        UserAccountEntity locked = new UserAccountEntity("locked@test.com",
                realEncoder.encode("correct-password"), "Locked", Set.of(Role.USER));
        locked.setLocked(true);
        when(users.findByEmail("locked@test.com")).thenReturn(Optional.of(locked));

        assertThrows(BadCredentialsException.class, () ->
                authService.login(new LoginCommand("locked@test.com", "wrong-password", null, null)));
    }

    @Test
    void loginShouldReportDisabledAccountOnlyForValidCredentials() {
        PasswordEncoder realEncoder = new BCryptPasswordEncoder(4);
        UserAccountEntity disabled = new UserAccountEntity("disabled@test.com",
                realEncoder.encode("correct-password"), "Disabled", Set.of(Role.USER));
        disabled.setEnabled(false);
        when(users.findByEmail("disabled@test.com")).thenReturn(Optional.of(disabled));

        assertThrows(AccountLockedException.class, () ->
                authService.login(new LoginCommand("disabled@test.com", "correct-password", null, null)));
    }

    // ── Logout idempotency ──

    @Test
    void logoutShouldBeIdempotent() {
        var session = new RefreshTokenSessionEntity(activeUser,
                tokenService.hashRefreshToken("logout-token"),
                Instant.now(), Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1", "test-agent");

        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(session));

        // First logout
        authService.logout(new LogoutCommand("logout-token", "user-1"));
        // Second logout on already-revoked token should not throw
        assertDoesNotThrow(() ->
                authService.logout(new LogoutCommand("logout-token", "user-1")));
    }

    @Test
    void logoutWithInvalidTokenShouldNotThrow() {
        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() ->
                authService.logout(new LogoutCommand("unknown-token", "user-1")));
    }

    // ── Registration edge cases ──

    @Test
    void registerShouldRejectEmptyPassword() {
        assertThrows(IllegalArgumentException.class, () ->
                authService.register(new RegisterUserCommand("new@test.com", "", "Name", Set.of())));
    }

    @Test
    void registerShouldRejectEmptyDisplayName() {
        assertThrows(IllegalArgumentException.class, () ->
                authService.register(new RegisterUserCommand("new@test.com", "pass123", "", Set.of())));
    }

    @Test
    void registerShouldRejectNullRoles() {
        when(users.existsByEmail(any())).thenReturn(false);

        AuthTokens result = authService.register(
                new RegisterUserCommand("new@test.com", "password123", "New User", null));
        assertNotNull(result);
        // Null roles should default to USER
        assertNotNull(result.accessToken());
    }
}
