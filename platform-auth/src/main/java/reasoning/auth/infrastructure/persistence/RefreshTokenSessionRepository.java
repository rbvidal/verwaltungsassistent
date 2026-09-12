package reasoning.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Repository for {@link RefreshTokenSessionEntity} with token-hash lookup. */
public interface RefreshTokenSessionRepository extends JpaRepository<RefreshTokenSessionEntity, UUID> {
    /** Finds a session by the hashed refresh token value. */
    Optional<RefreshTokenSessionEntity> findByTokenHash(String tokenHash);

    /** Removes all refresh-token sessions of a user account (used before account removal). */
    void deleteByUser(UserAccountEntity user);
}
