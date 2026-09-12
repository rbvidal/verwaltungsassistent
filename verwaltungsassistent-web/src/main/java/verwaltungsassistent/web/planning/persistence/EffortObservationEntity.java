package verwaltungsassistent.web.planning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine empirische Beobachtung der AKTIVEN Bearbeitungszeit eines
 * abgeschlossenen Falls (Summe der ACTIVE-Intervalle des workState —
 * pausierte/wartende Zeit zählt nie). Ein Eintrag je Fall (case_id unique);
 * Duplikat-Abschlüsse können keine zweite Beobachtung erzeugen.
 *
 * <p>Die Beobachtung gehört zum FALL (Kategorie + aktive Dauer), nicht zur
 * Mitarbeiterin — es werden keine Mitarbeiter-Leistungswerte gespeichert.</p>
 */
@Entity
@Table(name = "effort_observations")
public class EffortObservationEntity {

    @Id
    @Column(name = "id")
    private UUID id = UUID.randomUUID();

    @Column(name = "case_id", nullable = false, unique = true)
    private UUID caseId;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "observed_active_minutes", nullable = false)
    private int observedActiveMinutes;

    /** Optionales Komplexitäts-Feedback (1–10) der Sachbearbeiterin — Kontext, kein Leistungsmaß. */
    @Column(name = "complexity_feedback")
    private Integer complexityFeedback;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected EffortObservationEntity() {
    }

    public EffortObservationEntity(UUID caseId) {
        this.caseId = caseId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getObservedActiveMinutes() {
        return observedActiveMinutes;
    }

    public void setObservedActiveMinutes(int observedActiveMinutes) {
        this.observedActiveMinutes = observedActiveMinutes;
    }

    public Integer getComplexityFeedback() {
        return complexityFeedback;
    }

    public void setComplexityFeedback(Integer complexityFeedback) {
        this.complexityFeedback = complexityFeedback;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
