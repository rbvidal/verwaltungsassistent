package verwaltungsassistent.web.analysis.persistence;

import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for incoming citizen e-mails (the email work queue).
 *
 * <p>Die Warteschlangen-Abfragen liefern die Kandidaten als LISTE (Eingangs-
 * zeit absteigend); die Prioritäts-Reihung (Wartezeit-Dringlichkeit, höchste
 * zuerst) und die Paginierung übernimmt der Service in Java über DENSELBEN
 * {@code PriorityCalculationService.emailPriorityClass} wie die Badges.
 * Bewusst KEINE Sortierung über CASE-Ausdrücke mit benannten Parametern im
 * ORDER BY: solche Konstrukte sind über JPA-Provider/Versionen nicht robust
 * (Hibernate meldet dort ungebundene benannte Parameter).</p>
 */
public interface JpaIncomingEmailRepository extends JpaRepository<IncomingEmailEntity, UUID> {

    /** Deduplizierung über die Message-ID (Phase 2C.2-Mailbox-Import). */
    Optional<IncomingEmailEntity> findByMessageId(String messageId);

    /** Alle unerledigten E-Mails (Admin-Warteschlange), Eingangszeit absteigend. */
    List<IncomingEmailEntity> findByStatusOrderByReceivedAtDesc(IncomingEmailEntity.Status status);

    long countByStatus(IncomingEmailEntity.Status status);

    long countByStatusNot(IncomingEmailEntity.Status status);

    long countByAssignedToAndStatusNot(String assignedTo, IncomingEmailEntity.Status status);

    List<IncomingEmailEntity> findByStatusNotOrderByReceivedAtDesc(IncomingEmailEntity.Status status);

    List<IncomingEmailEntity> findByAssignedToOrderByReceivedAtDesc(String assignedTo);

    List<IncomingEmailEntity> findByAddressedToEmailOrderByReceivedAtDesc(String email);

    long count();

    /**
     * Unerledigte Warteschlange EINES Mitarbeiters als Liste: direkt an sie
     * adressiert, von ihr übernommen oder noch unzugewiesen in einer
     * allgemeinen Mailbox (Eingangszeit absteigend).
     */
    @Query("""
            SELECT e FROM IncomingEmailEntity e
            WHERE e.status = :status
              AND (e.addressedToEmail = :email OR e.assignedTo = :email
                   OR (e.addressedTo = :general AND e.assignedTo IS NULL))
            ORDER BY e.receivedAt DESC
            """)
    List<IncomingEmailEntity> findVisibleQueueList(@Param("email") String email,
                                                   @Param("status") Status status,
                                                   @Param("general") AddressedTo general);

    /** Volltext-Suche über die unerledigten E-Mails (Admin) — Liste, Eingangszeit absteigend. */
    @Query("""
            SELECT e FROM IncomingEmailEntity e
            WHERE e.status = :status
              AND (LOWER(e.subject) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.addressedToEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.assignedTo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.text) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.receivedAt DESC
            """)
    List<IncomingEmailEntity> searchNewByStatus(@Param("status") Status status,
                                                @Param("q") String q);

    /** Volltext-Suche in der sichtbaren Warteschlange eines Mitarbeiters (Liste). */
    @Query("""
            SELECT e FROM IncomingEmailEntity e
            WHERE e.status = :status
              AND (e.addressedToEmail = :email OR e.assignedTo = :email
                   OR (e.addressedTo = :general AND e.assignedTo IS NULL))
              AND (LOWER(e.subject) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.addressedToEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.assignedTo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.text) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.receivedAt DESC
            """)
    List<IncomingEmailEntity> searchVisibleQueueList(@Param("email") String email,
                                                     @Param("status") Status status,
                                                     @Param("general") AddressedTo general,
                                                     @Param("q") String q);

    /** Alle E-Mails, die einem Fall (Workspace) zugeordnet sind. */
    List<IncomingEmailEntity> findByWorkspaceIdOrderByReceivedAtDesc(UUID workspaceId);

    /** Volltext-Suche über die BEARBEITETEN E-Mails (alle außer NEW), Admin-Sicht. */
    @Query("""
            SELECT e FROM IncomingEmailEntity e
            WHERE e.status <> :newStatus
              AND (LOWER(e.subject) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.addressedToEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.assignedTo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.text) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.receivedAt DESC
            """)
    List<IncomingEmailEntity> searchProcessed(@Param("newStatus") Status newStatus,
                                              @Param("q") String q);

    /** Volltext-Suche über die bearbeiteten E-Mails EINES Mitarbeiters. */
    @Query("""
            SELECT e FROM IncomingEmailEntity e
            WHERE e.status <> :newStatus
              AND e.assignedTo = :email
              AND (LOWER(e.subject) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.senderEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.addressedToEmail) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.assignedTo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(e.text) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.receivedAt DESC
            """)
    List<IncomingEmailEntity> searchProcessedForAssignee(@Param("newStatus") Status newStatus,
                                                         @Param("email") String email,
                                                         @Param("q") String q);
}
