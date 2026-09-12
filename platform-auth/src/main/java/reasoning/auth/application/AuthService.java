package reasoning.auth.application;

import reasoning.common.audit.AuditEventType;
import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthTokens;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.LoginCommand;
import reasoning.auth.api.LogoutCommand;
import reasoning.auth.api.RefreshTokenCommand;
import reasoning.auth.api.RegisterUserCommand;
import reasoning.auth.infrastructure.persistence.RefreshTokenSessionEntity;
import reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.auth.model.Role;
import reasoning.auth.security.JwtTokenService;
import reasoning.auth.security.JwtTokenService.IssuedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Implementation of {@link AuthFacade} handling registration, login, token refresh, and logout. */
@Service
public class AuthService implements AuthFacade {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final AuthAuditPublisher auditPublisher;
    private final LoginAttemptGuard loginAttemptGuard;

    public AuthService(
            UserAccountRepository users,
            RefreshTokenSessionRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService,
            AuthAuditPublisher auditPublisher,
            LoginAttemptGuard loginAttemptGuard
    ) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditPublisher = auditPublisher;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    @Override
    @Transactional
    public AuthTokens register(RegisterUserCommand command) {
        String email = normalizeEmail(command.email());
        requireText(command.password(), "Password is required");
        requireText(command.displayName(), "Display name is required");
        if (users.existsByEmail(email)) {
            auditPublisher.emit(null, AuditEventType.USER_REGISTRATION_FAILED, null, Map.of("email", email, "reason", "duplicate_email"));
            throw new IllegalArgumentException("Email is already registered");
        }

        Set<Role> roles = normalizeRoles(command.roles());
        UserAccountEntity user = users.save(new UserAccountEntity(
                email,
                passwordEncoder.encode(command.password()),
                command.displayName().trim(),
                roles));

        auditPublisher.emit(user.getId().toString(), AuditEventType.USER_CREATED, user.getId().toString(),
                Map.of("email", user.getEmail()));
        return issueTokens(user, null, null);
    }

    @Override
    @Transactional
    public AuthTokens login(LoginCommand command) {
        String email = normalizeEmail(command.email());
        requireText(command.password(), "Password is required");

        // Brute-force protection: after repeated failures within the window,
        // further attempts are rejected until the lockout expires. The lockout
        // is strictly time-based — no permanent lockout, not even for admins.
        if (loginAttemptGuard.isBlocked(email)) {
            auditPublisher.emit(null, AuditEventType.USER_LOGIN_FAILED, null,
                    Map.of("email", email, "reason", "too_many_attempts"));
            throw invalidLogin(email);
        }

        UserAccountEntity user = users.findByEmail(email)
                .orElseThrow(() -> {
                    loginAttemptGuard.recordFailure(email);
                    return invalidLogin(email);
                });

        // Password first, account status second: a wrong password always gets
        // the generic error, so the locked/disabled state is never revealed to
        // someone who does not know the password. Only VALID credentials with
        // a blocked account produce the specific blocked-account message.
        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            loginAttemptGuard.recordFailure(email);
            throw invalidLogin(email);
        }
        if (!user.isEnabled() || user.isLocked()) {
            loginAttemptGuard.recordFailure(email);
            throw new AccountLockedException();
        }

        loginAttemptGuard.reset(email);
        auditPublisher.emit(user.getId().toString(), AuditEventType.USER_LOGIN, user.getId().toString(),
                Map.of("email", user.getEmail()));
        return issueTokens(user, command.ipAddress(), command.userAgent());
    }

    @Override
    @Transactional
    public AuthTokens refresh(RefreshTokenCommand command) {
        requireText(command.refreshToken(), "Refresh token is required");
        String tokenHash = tokenService.hashRefreshToken(command.refreshToken());
        RefreshTokenSessionEntity session = refreshTokens.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        Instant now = Instant.now();
        if (!session.isActiveAt(now)) {
            auditPublisher.emit(null, AuditEventType.TOKEN_REFRESH_FAILED, null, Map.of("reason", "inactive_refresh_token"));
            throw new BadCredentialsException("Invalid refresh token");
        }

        UserAccountEntity user = session.getUser();
        if (!user.isEnabled() || user.isLocked()) {
            session.revoke(now, null);
            auditPublisher.emit(user.getId().toString(), AuditEventType.TOKEN_REFRESH_FAILED, user.getId().toString(),
                    Map.of("reason", "user_disabled_or_locked"));
            throw new BadCredentialsException("Account is disabled or locked");
        }

        String rawRefreshToken = tokenService.generateRefreshToken();
        try {
            RefreshTokenSessionEntity replacement = refreshTokens.save(new RefreshTokenSessionEntity(
                    user,
                    tokenService.hashRefreshToken(rawRefreshToken),
                    now,
                    tokenService.refreshTokenExpiresAt(),
                    command.ipAddress(),
                    command.userAgent()));
            session.revoke(now, replacement.getId());

            IssuedAccessToken accessToken = tokenService.issueAccessToken(user);
            auditPublisher.emit(user.getId().toString(), AuditEventType.TOKEN_REFRESHED, user.getId().toString(),
                    Map.of("sessionId", replacement.getId().toString()));
            return toTokens(user, accessToken, rawRefreshToken, replacement.getExpiresAt());
        } catch (OptimisticLockingFailureException e) {
            // A concurrent request already refreshed this session.
            // Issue fresh tokens anyway — the old session is already revoked.
            log.debug("Concurrent refresh detected, issuing new tokens directly");
            IssuedAccessToken accessToken = tokenService.issueAccessToken(user);
            return toTokens(user, accessToken, rawRefreshToken, tokenService.refreshTokenExpiresAt());
        }
    }

    @Override
    @Transactional
    public void logout(LogoutCommand command) {
        requireText(command.refreshToken(), "Refresh token is required");
        String tokenHash = tokenService.hashRefreshToken(command.refreshToken());
        refreshTokens.findByTokenHash(tokenHash)
                .filter(session -> session.isActiveAt(Instant.now()))
                .ifPresent(session -> {
                    session.revoke(Instant.now(), null);
                    auditPublisher.emit(command.actorId(), AuditEventType.USER_LOGOUT, session.getUser().getId().toString(),
                            Map.of("sessionId", session.getId().toString()));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUser currentUser(String email) {
        UserAccountEntity user = users.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()));
    }

    private AuthTokens issueTokens(UserAccountEntity user, String ipAddress, String userAgent) {
        String rawRefreshToken = tokenService.generateRefreshToken();
        RefreshTokenSessionEntity session = refreshTokens.save(new RefreshTokenSessionEntity(
                user,
                tokenService.hashRefreshToken(rawRefreshToken),
                Instant.now(),
                tokenService.refreshTokenExpiresAt(),
                ipAddress,
                userAgent));
        IssuedAccessToken accessToken = tokenService.issueAccessToken(user);
        return toTokens(user, accessToken, rawRefreshToken, session.getExpiresAt());
    }

    private AuthTokens toTokens(
            UserAccountEntity user,
            IssuedAccessToken accessToken,
            String refreshToken,
            Instant refreshTokenExpiresAt
    ) {
        return new AuthTokens(
                accessToken.token(),
                refreshToken,
                "Bearer",
                accessToken.expiresAt(),
                refreshTokenExpiresAt,
                user.getId(),
                user.getEmail(),
                user.getRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()));
    }

    private BadCredentialsException invalidLogin(String email) {
        auditPublisher.emit(null, AuditEventType.USER_LOGIN_FAILED, null, Map.of("email", email));
        return new BadCredentialsException("Invalid email or password");
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        requireText(normalized, "Email is required");
        return normalized;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private Set<Role> normalizeRoles(Set<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            return Set.of(Role.USER);
        }
        return requestedRoles.stream()
                .map(Role::fromName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
