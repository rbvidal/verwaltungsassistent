package verwaltungsassistent.web.planning.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Temporärer View-Anspruch (Lease) auf einen Arbeitspool-Vorgang:
 * Sobald eine Mitarbeiterin einen freien Pool-Vorgang öffnet, wird der
 * Vorgang atomar für sie reserviert (claim), damit keine zweite Mitarbeiterin
 * denselben Vorgang gleichzeitig öffnen/bearbeiten kann. Der Anspruch ist
 * ein kurzes Lease mit Heartbeat und Ablauf (expiresAt) — Browser-Crash,
 * geschlossener Tab oder Netzwerkfehler geben den Vorgang nach Ablauf
 * automatisch wieder frei. Ein Anspruch IST KEINE Zuweisung: Sie entsteht
 * erst, wenn die Mitarbeiterin einen echten Pipeline-Schritt startet.
 */
@Entity
@Table(name = "case_view_claims")
public class CaseViewClaimEntity {

    @Id
    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "claimant", nullable = false, length = 190)
    private String claimant;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected CaseViewClaimEntity() {
    }

    public CaseViewClaimEntity(String caseId, String claimant, Instant now, Instant expiresAt) {
        this.caseId = caseId;
        this.claimant = claimant;
        this.claimedAt = now;
        this.heartbeatAt = now;
        this.expiresAt = expiresAt;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getClaimant() {
        return claimant;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void refresh(Instant now, Instant expiresAt) {
        this.heartbeatAt = now;
        this.expiresAt = expiresAt;
    }
}
