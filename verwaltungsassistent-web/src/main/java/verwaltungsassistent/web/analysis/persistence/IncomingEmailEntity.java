package verwaltungsassistent.web.analysis.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One incoming citizen e-mail that represents unprocessed email WORK. Owns the
 * assignment (which employee handles it) and the processing status so that the
 * dashboard feed, the E-Mail panel queue, the administrator view, the
 * processed-email history and the generated answer all use the same state.
 *
 * <p>Lifecycle: NEW (Eingegangen) → assigned (Zugeordnet) → IN_PROGRESS
 * (In Bearbeitung, set when the analysis runs) → COMPLETED (Erledigt).</p>
 */
@Entity
@Table(name = "incoming_emails")
public class IncomingEmailEntity {

    public enum Status {
        NEW,
        IN_PROGRESS,
        COMPLETED
    }

    public enum AddressedTo {
        /** Explicitly addressed to one employee. */
        EMPLOYEE,
        /** Addressed to a general Bürgeramt / office address. */
        GENERAL
    }

    /**
     * Herkunft der Nachricht (transportneutral, Phase 2C.1): MAILBOX steht für
     * die automatische Zustellung aus einer (künftigen) Mailbox-Anbindung —
     * die Nachricht kommt bereits normalisiert an und ist ggf. voranalysiert.
     * MANUAL steht für eine von der Mitarbeiterin manuell eingestellte
     * Nachricht (Einfügen/Import), die eine Analyse erfordert. Es gibt keinen
     * POP3/IMAP-/Provider-Bezug im Modell.
     */
    public enum SourceType {
        MAILBOX,
        MANUAL
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String subject;

    @Column(name = "sender_name", nullable = false)
    private String senderName;

    @Column(name = "sender_email")
    private String senderEmail;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "addressed_to", nullable = false)
    private AddressedTo addressedTo;

    @Column(name = "addressed_to_email")
    private String addressedToEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NEW;

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private String completedBy;

    @Column(name = "analysis_id")
    private UUID analysisId;

    /**
     * Der Fall/Vorgang (Workspace), dem diese E-Mail zugeordnet ist — mehrere
     * E-Mails können demselben Fall angehören (E-Mail 1..n → ein Fall). Wird
     * über "Diesem Fall zuordnen" bzw. beim Anlegen eines Falls aus der E-Mail
     * gesetzt. Die Zuordnung ist die Grundlage dafür, dass spätere E-Mails zum
     * selben Vorgang nicht erneut einen neuen Fall erzeugen.
     */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    /**
     * Transportneutrale Herkunft (MAILBOX = automatisch zugestellt /
     * voranalysiert, MANUAL = von der Mitarbeiterin eingestellt).
     * Bewusst nullable im Schema (ddl-auto=update kann einer befüllten
     * Tabelle keine NOT-NULL-Spalte ohne Default hinzufügen) — der Getter
     * behandelt NULL als MAILBOX (bestehende Zeilen).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private SourceType sourceType = SourceType.MAILBOX;

    /** Message-ID der Nachricht (Threading-Header, sofern technisch verfügbar). */
    @Column(name = "message_id")
    private String messageId;

    /** In-Reply-To der Nachricht (Threading-Header, sofern technisch verfügbar). */
    @Column(name = "in_reply_to")
    private String inReplyTo;

    /** References der Nachricht (Threading-Header, sofern technisch verfügbar). */
    @Column(name = "references_header")
    private String references;

    /**
     * Phase 2C.3b: Die Nachricht referenziert eine syntaktisch gültige, aber
     * NICHT existierende Vorgangsnummer (oder die automatische Zuordnung ist
     * fehlgeschlagen) — sie bleibt in der Warteschlange und wird zur Prüfung
     * durch eine Mitarbeiterin markiert. Es wird KEIN neuer Vorgang angelegt.
     */
    @Column(name = "review_required")
    private Boolean reviewRequired;

    protected IncomingEmailEntity() {
    }

    public IncomingEmailEntity(UUID id, String subject, String senderName, String senderEmail,
                               String text, Instant receivedAt, AddressedTo addressedTo,
                               String addressedToEmail) {
        this.id = id;
        this.subject = subject;
        this.senderName = senderName;
        this.senderEmail = senderEmail;
        this.text = text;
        this.receivedAt = receivedAt;
        this.addressedTo = addressedTo;
        this.addressedToEmail = addressedToEmail;
    }

    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public String getText() {
        return text;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public AddressedTo getAddressedTo() {
        return addressedTo;
    }

    public void setAddressedTo(AddressedTo addressedTo) {
        this.addressedTo = addressedTo;
    }

    public String getAddressedToEmail() {
        return addressedToEmail;
    }

    public void setAddressedToEmail(String addressedToEmail) {
        this.addressedToEmail = addressedToEmail;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(String completedBy) {
        this.completedBy = completedBy;
    }

    public UUID getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(UUID analysisId) {
        this.analysisId = analysisId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public SourceType getSourceType() {
        return sourceType != null ? sourceType : SourceType.MAILBOX;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getInReplyTo() {
        return inReplyTo;
    }

    public void setInReplyTo(String inReplyTo) {
        this.inReplyTo = inReplyTo;
    }

    public String getReferences() {
        return references;
    }

    public void setReferences(String references) {
        this.references = references;
    }

    /**
     * Thread-Referenzen dieser Nachricht: In-Reply-To plus alle Einzel-
     * Message-IDs aus dem References-Header (dedupliziert). Dient der
     * Header-basierten Thread-/Fall-Erkennung — zusätzlich zur semantischen
     * Zuordnung.
     */
    public List<String> threadReferences() {
        Set<String> refs = new java.util.LinkedHashSet<>();
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            refs.add(inReplyTo.trim());
        }
        if (references != null && !references.isBlank()) {
            for (String part : references.split("\\s+")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    refs.add(t);
                }
            }
        }
        return new java.util.ArrayList<>(refs);
    }

    /** Voranalysiert: eine Analyse existiert bereits (automatische Zustellung oder manuell ausgeführt). */
    public boolean isPreAnalyzed() {
        return analysisId != null;
    }

    public Boolean getReviewRequired() {
        return reviewRequired;
    }

    public void setReviewRequired(Boolean reviewRequired) {
        this.reviewRequired = reviewRequired;
    }

    /** Phase 2C.3b: ungültige/fehlende Vorgangsnummer → Prüfung durch eine Mitarbeiterin nötig. */
    public boolean isReviewRequired() {
        return Boolean.TRUE.equals(reviewRequired);
    }

    /**
     * True when the e-mail is directly addressed to a specific employee —
     * the recipient is the natural assignee and no "Bearbeitung übernehmen"
     * is needed.
     */
    public boolean isAssignedByRecipient() {
        return addressedTo == AddressedTo.EMPLOYEE && addressedToEmail != null && !addressedToEmail.isBlank();
    }

    /**
     * The effective assignee: the employee who claimed the e-mail, or the
     * addressed recipient for directly-addressed e-mails. Null means the
     * e-mail is genuinely unassigned (general mailbox, nobody claimed it).
     */
    public String effectiveAssignee() {
        if (assignedTo != null) {
            return assignedTo;
        }
        return isAssignedByRecipient() ? addressedToEmail : null;
    }
}
