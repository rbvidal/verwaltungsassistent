package verwaltungsassistent.web.planning;

/**
 * Strukturierter Bearbeitungszustand als Scheduler-Vertrag (Phase 1.5).
 * Der Scheduler muss fragen können "Ist der Fall bearbeitbar? Warum nicht?
 * Welche Warteart liegt vor?" — ohne deutsche UI-Texte zu parsen.
 *
 * <p>Derzeit zuverlässig ableitbar: {@link #READY_TO_WORK},
 * {@link #DOCUMENT_PROCESSING}, {@link #ANALYSIS_RUNNING},
 * {@link #WAITING_FOR_DOCUMENTS}, {@link #ALREADY_BEING_WORKED},
 * {@link #COMPLETED}.</p>
 *
 * <p>Bewusst NICHT erzeugt (Phase-2-Zustände, Daten fehlen heute):
 * {@link #WAITING_FOR_CITIZEN} und {@link #WAITING_FOR_EXTERNAL} (die
 * Kommunikationsrichtung bzw. eine externe Wartestelle ist nicht persistiert)
 * sowie {@link #NO_INFORMATION} (das Modell unterscheidet "Unterlagen fehlen"
 * nicht von "es ist gar nichts bekannt"). Es wird keine Heuristik erfunden,
 * um diese Zustände zu erraten.</p>
 */
public enum WorkabilityState {

    /** Kann jetzt sinnvoll weiterbearbeitet werden. */
    READY_TO_WORK,

    /** Dokumente werden gerade verarbeitet (Ingestion läuft). */
    DOCUMENT_PROCESSING,

    /** Die KI-Analyse läuft noch — Ergebnis abwarten. */
    ANALYSIS_RUNNING,

    /** Unterlagen fehlen bzw. sind nicht nutzbar (nicht explizit aufgelöst). */
    WAITING_FOR_DOCUMENTS,

    /**
     * Antwort ausstehend: Der Vorgang entstand aus einer offenen Bürger-
     * Anfrage (E-Mail), die die Verwaltung noch nicht beantwortet hat — es
     * fehlen keine Bürger-Unterlagen, sondern die Bearbeitung steht aus.
     * Anders als WAITING_FOR_DOCUMENTS ist dieser Zustand BEARBEITBAR
     * (workable): Die Mitarbeiterin soll den Vorgang als Nächstes bearbeiten.
     */
    RESPONSE_PENDING,

    /** Es wartet eine Bürgerin/ein Bürger auf eine Antwort (expliziter waitingOn-Hinweis). */
    WAITING_FOR_CITIZEN,

    /** Es wird auf eine externe Stelle gewartet (expliziter waitingOn-Hinweis). */
    WAITING_FOR_EXTERNAL,

    /** Pausiert aus sonstigen Gründen (expliziter waitingOn-Hinweis "Sonstiges"). */
    WAITING_FOR_OTHER,

    /** Der Vorgang wird bereits aktiv bearbeitet (zugeordnete E-Mail in Bearbeitung). */
    ALREADY_BEING_WORKED,

    /** Keine Informationen zum Vorgang vorhanden (Phase 2 — Daten fehlen). */
    NO_INFORMATION,

    /** Abgeschlossen bzw. archiviert — kein Kandidat mehr. */
    COMPLETED
}
