package verwaltungsassistent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Schwellen des semantischen E-Mail→Vorgang-Vorschlags (Phase 2C.7/2C.12):
 * kalibrierte Cosine-Ähnlichkeits-Grenzen — KEINE Wahrscheinlichkeiten.
 *
 * <ul>
 *   <li>{@code suggestionFloor} (0.75): ab hier entsteht ein semantischer
 *       Vorschlag („mittel"); darunter bleibt die E-Mail ohne Vorschlag.</li>
 *   <li>{@code highFloor} (0.82): ab hier gilt der Vorschlag als „hoch".</li>
 * </ul>
 *
 * <p>Werte sind über {@code email.semantic-matching.*} konfigurierbar
 * (application.yml bzw. Profil-Overrides) und werden beim Neustart/Reload der
 * Spring-Konfiguration übernommen — kein Quellcode-Eingriff nötig.</p>
 */
@ConfigurationProperties(prefix = "email.semantic-matching")
public class SemanticMatchingProperties {

    /** Kalibrierte Vorschlags-Schwelle (Phase 2C.12): 0.75. */
    private double suggestionFloor = 0.75;

    /** Kalibrierte Grenze für „hoch": 0.82. */
    private double highFloor = 0.82;

    public SemanticMatchingProperties() {
        validate();
    }

    public double getSuggestionFloor() {
        return suggestionFloor;
    }

    public void setSuggestionFloor(double suggestionFloor) {
        this.suggestionFloor = suggestionFloor;
        validate();
    }

    public double getHighFloor() {
        return highFloor;
    }

    public void setHighFloor(double highFloor) {
        this.highFloor = highFloor;
        validate();
    }

    /** Der Score ist eine normalisierte Ähnlichkeit (0..1); die Grenzen müssen
     *  geordnet sein: 0.0 <= suggestionFloor < highFloor <= 1.0. */
    private void validate() {
        if (suggestionFloor < 0.0 || suggestionFloor >= highFloor || highFloor > 1.0) {
            throw new IllegalArgumentException(
                    "Ungültige email.semantic-matching-Schwellen: suggestionFloor="
                            + suggestionFloor + ", highFloor=" + highFloor
                            + " (erwartet 0.0 <= suggestionFloor < highFloor <= 1.0)");
        }
    }
}
