package verwaltungsassistent.web.planning;

import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimaler, persistierter Arbeitszustand je Fall (phaseData.workState /
 * phaseData.waitingOn) — ein schlanker Overlay über den bestehenden
 * Workflow, KEINE zweite Zustandsmaschine:
 *
 * <pre>
 * workState  = { state: ACTIVE | PAUSED, employee, since, accumulatedMinutes }
 * waitingOn  = { type: DOCUMENTS | CITIZEN | EXTERNAL | OTHER, since, note }
 * </pre>
 *
 * <p>Regeln:</p>
 * <ul>
 *   <li><b>ACTIVE</b> nur durch explizite Mitarbeiter-Aktion (Analyse
 *       starten, Vorgang übernehmen, Fortsetzen). Eine IN_PROGRESS-E-Mail ist
 *       nur ein Hinweis und definiert niemals den Fall als "in Arbeit".</li>
 *   <li><b>PAUSED</b> durch „Vorgang pausieren" mit waitingOn-Typ; der Fall
 *       bleibt zugeordnet, verlässt aber den bearbeitbaren Pool.</li>
 *   <li><b>Messung</b>: Die beobachtete Bearbeitungszeit ist die Summe der
 *       ACTIVE-Intervalle (accumulatedMinutes + aktives Restintervall beim
 *       Abschluss). Pausierte/Wartende Zeit und Kalenderzeit zählen nie.</li>
 * </ul>
 *
 * <p>{@code accumulatedMinutes} ist Fall-Zeit, keine Mitarbeiter-Leistungs-
 * zeit — es werden keine Mitarbeiter-Performance-Werte gespeichert.</p>
 */
@Service
public class CaseWorkStateService {

    private static final Logger log = LoggerFactory.getLogger(CaseWorkStateService.class);

    private final WorkspaceService workspaceService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CaseWorkStateService(WorkspaceService workspaceService, Clock clock) {
        this.workspaceService = workspaceService;
        this.clock = clock;
    }

    /**
     * Invariante (Phase 2B.6): Der AKTIVE-Arbeiter eines Falls ist IMMER der
     * aktuell Zuständige (ownerId). Arbeitszustands-Übergänge (markActive/
     * markPaused/resume) sind nur für die zuständige Mitarbeiterin wirksam —
     * eine Fremdbearbeitung (z. B. Admin-Analyse an einem fremden Fall) setzt
     * keinen ACTIVE-Marker und übernimmt den Fall nicht. Übernahmen laufen
     * ausschließlich über die explizite Zuordnung (CaseAssignmentService:
     * ownerId ändert sich ZUERST, danach der Arbeitszustand).
     */
    private static boolean isOwnerOrNullOwner(WorkspaceEntity ws, String employee) {
        if (employee == null || employee.isBlank() || ws.getOwnerId() == null || ws.getOwnerId().isBlank()) {
            return false;
        }
        return employee.equalsIgnoreCase(ws.getOwnerId());
    }

    /** Mitarbeiterin beginnt explizit am Fall zu arbeiten (nur die Zuständige). */
    public void markActive(String caseId, String employee) {
        if (employee == null || employee.isBlank()) {
            return;
        }
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return;
        }
        if (!isOwnerOrNullOwner(ws, employee)) {
            log.debug("markActive für Fall {} durch Nicht-Zuständige {} ignoriert (ownerId={})",
                    caseId, employee, ws.getOwnerId());
            return;
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> state = workState(data);
        Instant now = clock.instant();
        if (state != null && "ACTIVE".equals(state.get("state"))) {
            if (employee.equals(state.get("employee"))) {
                return; // bereits aktiv von dieser Mitarbeiterin — since nicht zurücksetzen
            }
            // Übernahme durch eine andere Mitarbeiterin: Zeit akkumulieren, Arbeitende wechseln.
            accumulateElapsed(state, now);
            state.put("employee", employee);
            state.put("since", now.toString());
        } else if (state != null && "PAUSED".equals(state.get("state"))) {
            // Fortsetzen
            state.put("state", "ACTIVE");
            state.put("employee", employee);
            state.put("since", now.toString());
            data.remove("waitingOn");
        } else {
            Map<String, Object> fresh = new LinkedHashMap<>();
            fresh.put("state", "ACTIVE");
            fresh.put("employee", employee);
            fresh.put("since", now.toString());
            fresh.put("accumulatedMinutes", 0L);
            data.put("workState", fresh);
        }
        save(ws, data);
    }

    /** Pausiert den Fall mit explizitem Wartegrund (nur die Zuständige). */
    public void markPaused(String caseId, String employee, String type, String note) {
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return;
        }
        if (!isOwnerOrNullOwner(ws, employee)) {
            log.debug("markPaused für Fall {} durch Nicht-Zuständige {} ignoriert (ownerId={})",
                    caseId, employee, ws.getOwnerId());
            return;
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> state = workState(data);
        Instant now = clock.instant();
        if (state != null && "ACTIVE".equals(state.get("state"))) {
            accumulateElapsed(state, now);
        }
        if (state == null) {
            state = new LinkedHashMap<>();
            state.put("accumulatedMinutes", 0L);
            data.put("workState", state);
        }
        state.put("state", "PAUSED");
        state.put("employee", employee);
        state.put("since", now.toString());
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("type", type);
        waiting.put("since", now.toString());
        waiting.put("note", note != null ? note : "");
        data.put("waitingOn", waiting);
        save(ws, data);
    }

    /** Fortsetzen: PAUSED → ACTIVE, waitingOn wird entfernt (nur die Zuständige). */
    public void resume(String caseId, String employee) {
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return;
        }
        if (!isOwnerOrNullOwner(ws, employee)) {
            log.debug("resume für Fall {} durch Nicht-Zuständige {} ignoriert (ownerId={})",
                    caseId, employee, ws.getOwnerId());
            return;
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> state = workState(data);
        if (state == null || !"PAUSED".equals(state.get("state"))) {
            return;
        }
        state.put("state", "ACTIVE");
        state.put("employee", employee);
        state.put("since", clock.instant().toString());
        data.remove("waitingOn");
        save(ws, data);
    }

    /**
     * Übergabe an eine neue Zuständige (explizite Zuordnung): Die bisherige
     * AKTIVE Zeit wird abgeschlossen (akkumuliert), der Arbeitszustand geht in
     * PAUSED mit dem Hinweis "Übergeben an …" über — die neue Mitarbeiterin
     * beginnt erst mit EXPLIZITER Arbeitsaufnahme (Bearbeitung fortsetzen /
     * Analyse starten / Vorgang übernehmen). Die gesammelte aktive Zeit bleibt
     * Fall-Zeit und geht nicht verloren (Invariante: effort gehört zum Fall).
     * Ohne bestehenden Arbeitszustand bleibt der Fall unbegonnen (kein Marker).
     */
    public void handoffTo(String caseId, String newOwner) {
        if (newOwner == null || newOwner.isBlank()) {
            return;
        }
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return;
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> state = workState(data);
        if (state != null && "ACTIVE".equals(state.get("state"))) {
            accumulateElapsed(state, clock.instant());
        }
        if (state != null) {
            state.put("state", "PAUSED");
            state.put("employee", newOwner);
            state.put("since", clock.instant().toString());
            Map<String, Object> waiting = new LinkedHashMap<>();
            waiting.put("type", "OTHER");
            waiting.put("since", clock.instant().toString());
            waiting.put("note", "Übergeben an " + newOwner);
            data.put("waitingOn", waiting);
            save(ws, data);
        }
    }

    /** Entfernt einen veralteten waitingOn-Hinweis (z. B. Unterlagen sind inzwischen da). */
    public void clearWaitingOn(String caseId) {
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return;
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        if (data.remove("waitingOn") != null) {
            save(ws, data);
        }
    }

    /**
     * Abschluss des Falls: akkumuliert das letzte ACTIVE-Intervall, entfernt
     * die Marker und liefert die beobachtete aktive Bearbeitungszeit in
     * Minuten zurück (0, wenn nie aktiv gearbeitet wurde).
     */
    public long completeOnClose(String caseId) {
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return 0;
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> state = workState(data);
        long total = 0;
        if (state != null && "ACTIVE".equals(state.get("state"))) {
            accumulateElapsed(state, clock.instant());
            total = longOf(state.get("accumulatedMinutes"));
        } else if (state != null) {
            total = longOf(state.get("accumulatedMinutes"));
        }
        data.remove("workState");
        data.remove("waitingOn");
        save(ws, data);
        return total;
    }

    /** Aktueller Zustand (für Anzeige/Integration), oder null. */
    public Map<String, Object> currentState(String caseId) {
        WorkspaceEntity ws = findCase(caseId);
        if (ws == null) {
            return Map.of();
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> out = new LinkedHashMap<>();
        if (data.get("workState") instanceof Map<?, ?> m) {
            out.put("workState", m);
        }
        if (data.get("waitingOn") instanceof Map<?, ?> m) {
            out.put("waitingOn", m);
        }
        return out;
    }

    private void accumulateElapsed(Map<String, Object> state, Instant now) {
        long accumulated = longOf(state.get("accumulatedMinutes"));
        Instant since = instantOf(state.get("since"));
        long elapsed = elapsedMinutes(since, now);
        if (elapsed > 0) {
            state.put("accumulatedMinutes", accumulated + elapsed);
        }
    }

    private static long elapsedMinutes(Instant since, Instant now) {
        if (since == null || !now.isAfter(since)) {
            return 0;
        }
        long minutes = ChronoUnit.MINUTES.between(since, now);
        return minutes == 0 ? 1 : minutes;
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static Instant instantOf(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workState(Map<String, Object> data) {
        Object raw = data.get("workState");
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private WorkspaceEntity findCase(String caseId) {
        try {
            return workspaceService.findById(caseId).orElse(null);
        } catch (Exception e) {
            log.debug("Fall {} für workState nicht lesbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    private void save(WorkspaceEntity ws, Map<String, Object> data) {
        try {
            ws.setPhaseData(mapper.writeValueAsString(data));
            workspaceService.save(ws);
        } catch (Exception e) {
            log.warn("workState von Fall {} nicht speicherbar: {}", ws.getId(), e.getMessage());
        }
    }
}
