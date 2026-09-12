package reasoning.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity tracking a refresh token session with revocation and expiry support. */
@Entity
@Table(name = "auth_refresh_token_sessions")
public class RefreshTokenSessionEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    @Column(name = "created_by_ip")
    private String createdByIp;

    @Column(name = "user_agent")
    private String userAgent;

    @jakarta.persistence.Version
    private Long version;

    protected RefreshTokenSessionEntity() {
    }

    public RefreshTokenSessionEntity(
            UserAccountEntity user,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            String createdByIp,
            String userAgent
    ) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.createdByIp = createdByIp;
        this.userAgent = userAgent;
    }

    /** Marks this session as revoked at the given time, optionally recording the replacement token. */
    public void revoke(Instant revokedAt, UUID replacedByTokenId) {
        this.revokedAt = revokedAt;
        this.replacedByTokenId = replacedByTokenId;
    }

    /** Returns {@code true} if this session is not revoked and has not yet expired at the given instant. */
    public boolean isActiveAt(Instant instant) {
        return revokedAt == null && expiresAt.isAfter(instant);
    }

    public UUID getId() {
        return id;
    }

    public UserAccountEntity getUser() {
        return user;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
