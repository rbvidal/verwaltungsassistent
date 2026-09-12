package verwaltungsassistent.web.analysis.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository for general demo mailboxes (real recipient addresses). */
public interface MailboxRepository extends JpaRepository<MailboxEntity, UUID> {

    Optional<MailboxEntity> findByAddress(String address);

    List<MailboxEntity> findAllByOrderByAddress();
}
