package verwaltungsassistent.web.analysis.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One real demo mailbox (general recipient address) inside the existing e-mail
 * architecture. General incoming e-mails reference a mailbox address via
 * {@code IncomingEmailEntity.addressedToEmail} — the mailbox is a real
 * recipient record, never a hard-coded display string.
 */
@Entity
@Table(name = "mailboxes")
public class MailboxEntity {

    @Id
    private UUID id;

    @Column(name = "address", nullable = false, unique = true)
    private String address;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description")
    private String description;

    protected MailboxEntity() {
    }

    public MailboxEntity(UUID id, String address, String displayName, String description) {
        this.id = id;
        this.address = address;
        this.displayName = displayName;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
