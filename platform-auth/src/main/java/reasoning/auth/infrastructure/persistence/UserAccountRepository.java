package reasoning.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Repository for {@link UserAccountEntity} with email-based lookups. */
public interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
    /** Finds a user account by normalized email address. */
    Optional<UserAccountEntity> findByEmail(String email);

    /** Checks whether an account with the given email already exists. */
    boolean existsByEmail(String email);
}
