package verwaltungsassistent.web.planning;

import verwaltungsassistent.web.planning.persistence.CaseViewClaimEntity;
import verwaltungsassistent.web.planning.persistence.JpaCaseViewClaimRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import reasoning.workspace.api.WorkspaceEntity;
import verwaltungsassistent.web.service.DemoDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporäre View-Anspruchs-Verwaltung (Lease) für Arbeitspool-Vorgänge.
 *
 * <p>Ein freier Pool-Vorgang (owner null / admin / allgemeine Mailbox) wird
 * beim Öffnen durch eine Mitarbeiterin ATOMAR für genau diese Mitarbeiterin
 * reserviert: Der Claim ist ein Datensatz mit dem Vorgang als Primärschlüssel —
 * zwei gleichzeitige Claims desselben Vorgangs können nur EINEN Gewinner
 * haben (Unique/PK-Constraint). Solange der Claim lebt, ist der Vorgang für
 * alle anderen Mitarbeiterinnen unsichtbar (Listen-Filter) und direkt nicht
 * erreichbar (Zugriffs-Guard).</p>
 *
 * <p>Der Claim ist bewusst KEINE Zuweisung: Er läuft über einen Heartbeat
 * (Client ≈ alle 25 s) und verfällt kurzfristig (Lease ≈ 60 s), wenn der
 * Heartbeat ausbleibt (Browser-Crash, geschlossener Tab, Netzwerkfehler).
 * Eine echte Zuweisung entsteht erst durch einen Pipeline-Start
 * ({@link #promoteToAssignment} bzw. den bestehenden
 * {@code CaseAssignmentService.assign(..., startWork=true)}).</p>
 */
@Service
public class CaseViewClaimService {

    private static final Logger log = LoggerFactory.getLogger(CaseViewClaimService.class);

    private final JpaCaseViewClaimRepository repository;

    /** Lease-Länge ohne Heartbeat, bevor der Anspruch verfällt. */
    private final Duration lease;

    public CaseViewClaimService(JpaCaseViewClaimRepository repository,
                                @Value("${app.case-view-claim.lease:PT60S}") Duration lease) {
        this.repository = repository;
        this.lease = lease;
    }

    /** Nur Pool-/freie CASE-Vorgänge sind claim-bar (GEO, eigene, admin-lesend nicht). */
    public static boolean isClaimable(WorkspaceEntity ws) {
        return ws != null
                && "CASE".equalsIgnoreCase(ws.getWorkspaceType())
                && DemoDataService.isGeneralPoolOwner(ws.getOwnerId());
    }

    /**
     * Reserviert den Vorgang atomar für {@code email}. Gelingt genau dann,
     * wenn der Vorgang frei ist ODER bereits von {@code email} gehalten wird
     * (dann wird das Lease verlängert). Wirft {@link ClaimConflictException},
     * wenn ein anderer Anspruch besteht.
     */
    @Transactional
    public void claim(String caseId, String email) {
        Instant now = Instant.now();
        Optional<CaseViewClaimEntity> existing = repository.findById(caseId);
        if (existing.isPresent()) {
            if (!existing.get().getClaimant().equalsIgnoreCase(email)) {
                throw new ClaimConflictException(caseId, existing.get().getClaimant());
            }
            existing.get().refresh(now, now.plus(lease));
            repository.save(existing.get());
            return;
        }
        try {
            repository.saveAndFlush(new CaseViewClaimEntity(caseId, email, now, now.plus(lease)));
        } catch (DataIntegrityViolationException e) {
            // Paralleler Claim hat gewonnen (PK-Constraint).
            throw new ClaimConflictException(caseId, repository.findById(caseId)
                    .map(CaseViewClaimEntity::getClaimant).orElse("unbekannt"));
        }
    }

    /** Verlängert das Lease; wirft {@link ClaimConflictException} bei fremdem Anspruch. */
    @Transactional
    public void heartbeat(String caseId, String email) {
        claim(caseId, email);
    }

    /** Gibt den Anspruch frei (explizites Verlassen der Fall-Ansicht). */
    @Transactional
    public void release(String caseId, String email) {
        repository.findById(caseId).ifPresent(claim -> {
            if (claim.getClaimant().equalsIgnoreCase(email)) {
                repository.delete(claim);
                repository.flush();
            }
        });
    }

    /** Liefert die Vorgänge, die aktuell von ANDEREN Mitarbeiterinnen gehalten werden. */
    @Transactional(readOnly = true)
    public Set<String> claimedByOthers(String email) {
        return repository.findAll().stream()
                .filter(c -> !c.getClaimant().equalsIgnoreCase(email))
                .map(CaseViewClaimEntity::getCaseId)
                .collect(Collectors.toSet());
    }

    /**
     * Überführt einen View-Anspruch in eine echte Zuweisung: Nur der
     * aktuelle Anspruchsinhaber darf das; ohne gültigen eigenen Anspruch
     * wird der Übergang abgelehnt (Vorgang gehört dann einem anderen bzw.
     * ist nicht frei). Aufruf ATOMAR zusammen mit dem Pipeline-Start.
     */
    @Transactional
    public void verifyOwnClaimForAssignment(String caseId, String email) {
        Optional<CaseViewClaimEntity> claim = repository.findById(caseId);
        if (claim.isEmpty()) {
            throw new ClaimConflictException(caseId, null);
        }
        if (!claim.get().getClaimant().equalsIgnoreCase(email)) {
            throw new ClaimConflictException(caseId, claim.get().getClaimant());
        }
        repository.delete(claim.get());
        repository.flush();
    }

    /**
     * Überführung View-Anspruch → echte Zuweisung beim Pipeline-Start:
     * <ul>
     *   <li>kein Anspruch vorhanden → {@code false} (keine Erfindung eines
     *       Umwegs; es gelten die bestehenden Autorisierungs-Semantiken),</li>
     *   <li>Anspruch einer ANDEREN Mitarbeiterin → {@link ClaimConflictException},</li>
     *   <li>eigener Anspruch → wird gelöscht, {@code true} (der Aufrufer führt
     *       danach die echte Zuweisung über den bestehenden
     *       {@code CaseAssignmentService} aus).</li>
     * </ul>
     */
    @Transactional
    public boolean promoteIfClaimedBy(String caseId, String email) {
        Optional<CaseViewClaimEntity> claim = repository.findById(caseId);
        if (claim.isEmpty()) {
            return false;
        }
        if (!claim.get().getClaimant().equalsIgnoreCase(email)) {
            throw new ClaimConflictException(caseId, claim.get().getClaimant());
        }
        repository.delete(claim.get());
        repository.flush();
        return true;
    }

    /** Räumt abgelaufene Leases auf — Sicherheitsnetz gegen Crash/vergessene Freigabe. */
    @Scheduled(fixedDelay = 20_000)
    @Transactional
    public void sweepExpired() {
        try {
            int removed = repository.deleteExpired(Instant.now());
            if (removed > 0) {
                log.info("Case-View-Leases: {} abgelaufene Ansprüche entfernt", removed);
            }
        } catch (Exception e) {
            log.warn("Case-View-Lease-Räumung fehlgeschlagen: {}", e.getMessage());
        }
    }

    /** 409-artige, fachliche Ablehnung bei fremdem Anspruch. */
    public static final class ClaimConflictException extends RuntimeException {
        private final String caseId;
        private final String holder;

        public ClaimConflictException(String caseId, String holder) {
            super("Vorgang wird gerade von einer anderen Mitarbeiterin bearbeitet");
            this.caseId = caseId;
            this.holder = holder;
        }

        public String getCaseId() {
            return caseId;
        }

        public String getHolder() {
            return holder;
        }
    }
}
