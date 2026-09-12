package verwaltungsassistent.web.planning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Strukturierter Arbeitsplanungs-Zustand eines Falls (Phase 1 der intelligenten
 * Mitarbeiter-Arbeitsplanung). Bewusst KEIN einzelnes "priority"-Feld, sondern
 * ein erklärbarer Planungsstand: Punktwert, Klasse, Begründung, berechnete
 * Faktoren (JSON) und Zeitstempel.
 *
 * <p>Frischheit: {@code basisFingerprint} ist der Fingerabdruck der
 * substanziellen Fall-Eingaben zum Berechnungszeitpunkt (dieselbe Mechanik wie
 * beim Fallbriefing — {@code CaseBriefingService.fingerprint}). Ändert sich
 * der Fingerabdruck (neue E-Mails/Dokumente/Ereignisse/neue Analyse), ist der
 * Planungsstand veraltet und wird beim nächsten Zugriff neu berechnet. Es wird
 * bewusst KEINE zweite Frische-Mechanik eingeführt.</p>
 *
 * <p>{@code basisAnalysisVersion} merkt sich den Analyse-Lauf, dessen
 * Ergebnisse (Belege, Absicherung, offene Punkte) in die Faktoren eingeflossen
 * sind; {@code effortMinutes}/{@code contextSwitchCost} sind Platzhalter für
 * Phase 2 (Aufwands-/Kontextwechselkosten) und werden dort befüllt.</p>
 */
@Entity
@Table(name = "case_planning")
public class CasePlanningEntity {

    @Id
    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "priority_score", nullable = false)
    private int priorityScore;

    @Column(name = "priority_class", nullable = false, length = 20)
    private String priorityClass;

    @Column(name = "priority_reason", length = 500)
    private String priorityReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factors_json", columnDefinition = "jsonb")
    private String factorsJson;

    @Column(name = "workable", nullable = false)
    private boolean workable;

    @Column(name = "blocked_reason", length = 300)
    private String blockedReason;

    /** Strukturierter Bearbeitungszustand (Scheduler-Vertrag, {@link verwaltungsassistent.web.planning.WorkabilityState}). */
    @Column(name = "workability_state", nullable = false, length = 30)
    private String workabilityState = "READY_TO_WORK";

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "basis_analysis_version")
    private Integer basisAnalysisVersion;

    @Column(name = "basis_fingerprint", length = 64)
    private String basisFingerprint;

    @Column(name = "effort_minutes")
    private Integer effortMinutes;

    @Column(name = "context_switch_cost")
    private Double contextSwitchCost;

    protected CasePlanningEntity() {
    }

    public CasePlanningEntity(UUID caseId) {
        this.caseId = caseId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public int getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(int priorityScore) {
        this.priorityScore = priorityScore;
    }

    public String getPriorityClass() {
        return priorityClass;
    }

    public void setPriorityClass(String priorityClass) {
        this.priorityClass = priorityClass;
    }

    public String getPriorityReason() {
        return priorityReason;
    }

    public void setPriorityReason(String priorityReason) {
        this.priorityReason = priorityReason;
    }

    public String getFactorsJson() {
        return factorsJson;
    }

    public void setFactorsJson(String factorsJson) {
        this.factorsJson = factorsJson;
    }

    public boolean isWorkable() {
        return workable;
    }

    public void setWorkable(boolean workable) {
        this.workable = workable;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public String getWorkabilityState() {
        return workabilityState;
    }

    public void setWorkabilityState(String workabilityState) {
        this.workabilityState = workabilityState;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(Instant calculatedAt) {
        this.calculatedAt = calculatedAt;
    }

    public Integer getBasisAnalysisVersion() {
        return basisAnalysisVersion;
    }

    public void setBasisAnalysisVersion(Integer basisAnalysisVersion) {
        this.basisAnalysisVersion = basisAnalysisVersion;
    }

    public String getBasisFingerprint() {
        return basisFingerprint;
    }

    public void setBasisFingerprint(String basisFingerprint) {
        this.basisFingerprint = basisFingerprint;
    }

    public Integer getEffortMinutes() {
        return effortMinutes;
    }

    public void setEffortMinutes(Integer effortMinutes) {
        this.effortMinutes = effortMinutes;
    }

    public Double getContextSwitchCost() {
        return contextSwitchCost;
    }

    public void setContextSwitchCost(Double contextSwitchCost) {
        this.contextSwitchCost = contextSwitchCost;
    }
}
