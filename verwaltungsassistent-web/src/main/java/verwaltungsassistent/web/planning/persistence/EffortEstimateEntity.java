package verwaltungsassistent.web.planning.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Empirische Statistik je Fallart (kumulativ über ALLE Beobachtungen, nur
 * im Lern-Intervall neu berechnet). Dient der Nachvollziehbarkeit: Jede
 * Aktualisierung bewahrt die vorherige Schätzung (previousMedianMinutes)
 * und den Zeitstempel — die Mitarbeiterin sieht davon nur "ca. X Minuten".
 */
@Entity
@Table(name = "effort_estimates")
public class EffortEstimateEntity {

    @Id
    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "median_minutes")
    private Integer medianMinutes;

    @Column(name = "p75_minutes")
    private Integer p75Minutes;

    @Column(name = "p90_minutes")
    private Integer p90Minutes;

    @Column(name = "previous_median_minutes")
    private Integer previousMedianMinutes;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    /** INITIAL_CONFIG | EMPIRICAL. */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    protected EffortEstimateEntity() {
    }

    public EffortEstimateEntity(String category) {
        this.category = category;
    }

    public String getCategory() {
        return category;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Integer getMedianMinutes() {
        return medianMinutes;
    }

    public void setMedianMinutes(Integer medianMinutes) {
        this.medianMinutes = medianMinutes;
    }

    public Integer getP75Minutes() {
        return p75Minutes;
    }

    public void setP75Minutes(Integer p75Minutes) {
        this.p75Minutes = p75Minutes;
    }

    public Integer getP90Minutes() {
        return p90Minutes;
    }

    public void setP90Minutes(Integer p90Minutes) {
        this.p90Minutes = p90Minutes;
    }

    public Integer getPreviousMedianMinutes() {
        return previousMedianMinutes;
    }

    public void setPreviousMedianMinutes(Integer previousMedianMinutes) {
        this.previousMedianMinutes = previousMedianMinutes;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
