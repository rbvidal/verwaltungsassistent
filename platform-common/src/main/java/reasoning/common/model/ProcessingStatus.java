package reasoning.common.model;

/**
 * Controlled German vocabulary for the administrative processing state
 * ("Bearbeitungsstand") shown on decision templates.
 *
 * <p>This is the user-facing label layer over the existing {@link WorkspacePhase}
 * workflow state. The LLM is not allowed to invent arbitrary status text;
 * production code must map phase/status to one of these values.
 */
public enum ProcessingStatus {
    NEU("Neu"),
    IN_BEARBEITUNG("In Bearbeitung"),
    IN_PRUEFUNG("In Prüfung"),
    WARTEN_AUF_UNTERLAGEN("Warten auf Unterlagen"),
    WARTEN_AUF_RUECKMELDUNG("Warten auf Rückmeldung"),
    WARTEN_AUF_ENTSCHEIDUNG("Warten auf Entscheidung"),
    ABGESCHLOSSEN("Abgeschlossen"),
    ABGEWIESEN("Abgewiesen"),
    ZURUECKGESTELLT("Zurückgestellt");

    private final String label;

    ProcessingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Maps the existing workflow phase to the controlled processing-status label.
     * Additional blocked/waiting states are defined in the enum but are not yet
     * auto-derived from phase alone; they can be selected explicitly once the
     * workflow model captures them.
     */
    public static ProcessingStatus fromPhase(WorkspacePhase phase) {
        if (phase == null) {
            return NEU;
        }
        return switch (phase) {
            case SETUP -> IN_BEARBEITUNG;
            case INGESTION -> WARTEN_AUF_UNTERLAGEN;
            case ANALYSIS -> IN_PRUEFUNG;
            case REVIEW -> WARTEN_AUF_ENTSCHEIDUNG;
            case COMPLETE -> ABGESCHLOSSEN;
        };
    }
}
