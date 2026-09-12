package verwaltungsassistent.web.service;

import reasoning.ai.api.AiFacade;
import reasoning.ai.model.AiConversationContext;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.RetrievalScope;
import reasoning.ai.model.SourceCitation;
import reasoning.common.model.WorkspaceStatus;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.application.WorkspaceService;
import reasoning.search.model.SearchFilter;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Erzeugt das „Fallbriefing" für einen Vorgang über die bestehende kommunale
 * Assistenten-Pipeline (Retrieval → Evidence → Grounding → Verifier) — bewusst
 * NICHT über den „Allgemeine Fragen"-Direktpfad. Das Briefing ist eine
 * strukturierte, deterministische Aufbereitung der echten Pipeline-Ergebnisse:
 * Kurzfassung/Sachverhalt/nächste Schritte stammen aus der generierten
 * Antwort, Erkenntnisse aus den Verifier-Findings, Belege aus den Zitaten,
 * Zuständigkeit aus dem Vorgang. Nicht belegte Punkte werden ausdrücklich als
 * solche gekennzeichnet — nichts wird als gesichert dargestellt, was die
 * Evidenz nicht trägt.
 */
@Service
public class CaseBriefingService {

    private static final Logger log = LoggerFactory.getLogger(CaseBriefingService.class);

    private final AiFacade aiFacade;
    private final WorkspaceService workspaceService;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaseBriefingService(AiFacade aiFacade, WorkspaceService workspaceService,
                               JpaIncomingEmailRepository incomingEmailRepository) {
        this.aiFacade = aiFacade;
        this.workspaceService = workspaceService;
        this.incomingEmailRepository = incomingEmailRepository;
    }

    /**
     * Strukturiertes Fallbriefing (deterministisch aus Pipeline-Ergebnissen
     * oder aus der persistierten Fall-Analyse aufgebaut).
     *
     * @param eingabeFingerprint Fingerabdruck der substanziellen Fall-Eingaben
     *                           (Dokumente, E-Mails, Ereignisse, Analyse-Stand)
     *                           zum Erstellungszeitpunkt — ändert er sich, ist
     *                           das Briefing veraltet und darf nicht als
     *                           aktueller Stand präsentiert werden.
     * @param grundlage          "Analyse-Pipeline" (eigener Lauf) oder
     *                           "Fall-Analyse" (aus der persistierten
     *                           Entscheidungsanalyse abgeleitet).
     */
    public record Briefing(
            String vorgangsnummer,
            String fallname,
            String erstelltAm,
            String kurzfassung,
            String sachverhalt,
            List<String> erkenntnisse,
            List<String> belege,
            String zustaendigkeit,
            List<String> offenePunkte,
            List<String> naechsteSchritte,
            String quellenlage,
            boolean aiGeneriert,
            String bearbeitungsstand,
            String eingabeFingerprint,
            String grundlage) {

        public Briefing(String vorgangsnummer, String fallname, String erstelltAm, String kurzfassung,
                        String sachverhalt, List<String> erkenntnisse, List<String> belege,
                        String zustaendigkeit, List<String> offenePunkte, List<String> naechsteSchritte,
                        String quellenlage, boolean aiGeneriert, String bearbeitungsstand) {
            this(vorgangsnummer, fallname, erstelltAm, kurzfassung, sachverhalt, erkenntnisse, belege,
                    zustaendigkeit, offenePunkte, naechsteSchritte, quellenlage, aiGeneriert,
                    bearbeitungsstand, null, null);
        }
    }

    /**
     * True wenn sich die substanziellen Fall-Eingaben seit der Erstellung des
     * Briefings geändert haben (neue/entfernte Dokumente, E-Mails, Ereignisse
     * oder ein neuer Analyse-Stand). Navigation allein erzeugt keine
     * Änderung — das Briefing wird nur bei echten Inhaltsänderungen als
     * veraltet markiert.
     */
    public boolean isStale(String caseId, Briefing briefing) {
        if (briefing == null) {
            return false;
        }
        if (briefing.eingabeFingerprint() == null || briefing.eingabeFingerprint().isBlank()) {
            // Ältere Briefings ohne Fingerabdruck gelten als veraltet, sobald
            // der Fall überhaupt substanzielle Eingaben trägt — sicherer als
            // sie unbesehen als aktuell zu präsentieren.
            return !fingerprint(caseId).isBlank();
        }
        return !fingerprint(caseId).equals(briefing.eingabeFingerprint());
    }

    /**
     * Deterministischer Fingerabdruck der substanziellen Fall-Eingaben, die
     * das Briefing (bzw. die Analyse) konsumiert: Name/Beschreibung, Status,
     * Dokumente, zugeordnete E-Mails, Timeline-Ereignisse und der
     * Analyse-Stand (Marker + letzter abgeschlossener Lauf). Reine
     * UI-/Zustandsänderungen (Notizen, Checklisten) fließen bewusst NICHT ein.
     */
    public String fingerprint(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            if (ws == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("name=").append(nvl(ws.getName())).append('\n');
            sb.append("desc=").append(nvl(ws.getDescription())).append('\n');
            sb.append("status=").append(nvl(String.valueOf(ws.getStatus()))).append('\n');
            sb.append("phase=").append(nvl(String.valueOf(ws.getPhase()))).append('\n');
            // Planungsrelevante phaseData-Schlüssel: Phasenwechsel, explizite
            // Ingestion-Auflösung, Fallart und Mitarbeiter-Dringlichkeit ändern
            // Priorität/Bearbeitbarkeit, ohne Dokumente/E-Mails/Analyse anzufassen.
            Map<String, Object> planningKeys = ws.getPhaseDataMap();
            sb.append("ingestionResolved=")
                    .append(nvl(String.valueOf(planningKeys.get("ingestionResolved")))).append('\n');
            sb.append("caseCategory=")
                    .append(nvl(String.valueOf(planningKeys.get("caseCategory")))).append('\n');
            sb.append("geoPriority=")
                    .append(nvl(String.valueOf(planningKeys.get("geoPriority")))).append('\n');
            // Arbeitszustand (ACTIVE/PAUSED) und Wartehinweis: ändern die
            // Bearbeitbarkeit und die aktive Zeitmessung, ohne Dokumente/
            // E-Mails/Analyse anzufassen.
            sb.append("workState=")
                    .append(nvl(String.valueOf(planningKeys.get("workState")))).append('\n');
            sb.append("waitingOn=")
                    .append(nvl(String.valueOf(planningKeys.get("waitingOn")))).append('\n');
            List<String> docs = workspaceService.getWorkspaceDocuments(caseId).stream()
                    .map(WorkspaceDocumentLinkEntity::getDocumentUuid)
                    .filter(Objects::nonNull)
                    .map(UUID::toString)
                    .sorted()
                    .toList();
            sb.append("docs=").append(docs).append('\n');
            List<String> emails = new ArrayList<>();
            try {
                incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId))
                        .forEach(e -> emails.add(e.getId() + "@"
                                + (e.getReceivedAt() != null ? e.getReceivedAt().toString() : "") + "@"
                                + nvl(e.getSubject()) + "@" + nvl(e.getSenderEmail())));
            } catch (Exception e) {
                log.debug("E-Mail-Fingerprint für Fall {} nicht lesbar: {}", caseId, e.getMessage());
            }
            emails.sort(Comparator.naturalOrder());
            sb.append("emails=").append(emails).append('\n');
            List<String> events = workspaceService.getTimeline(caseId).stream()
                    .map(ev -> ev.getId() + "@"
                            + (ev.getEventDate() != null ? ev.getEventDate().toString() : "") + "@"
                            + nvl(ev.getTitle()))
                    .sorted()
                    .toList();
            sb.append("events=").append(events).append('\n');
            Object marker = ws.getPhaseDataMap().get("analysis");
            sb.append("analysis=").append(marker != null ? String.valueOf(marker) : "").append('\n');
            String run = workspaceService.latestCompletedAnalysisRun(caseId)
                    .map(r -> r.getVersion() + "@" + r.getDocumentCount() + "@"
                            + (r.getCompletedAt() != null ? r.getCompletedAt().toString() : ""))
                    .orElse("");
            sb.append("run=").append(run);
            return sha256(sb.toString());
        } catch (Exception e) {
            log.warn("Fingerprint für Fall {} nicht berechenbar: {}", caseId, e.getMessage());
            return "";
        }
    }

    /**
     * True wenn das Fallbriefing aus der persistierten Entscheidungsanalyse
     * abgeleitet werden kann: ein abgeschlossener, abrufbarer Analyse-Lauf
     * existiert und seine Dokumentengrundlage entspricht dem aktuellen Stand
     * (kein neues/entferntes Dokument seit der Analyse).
     */
    public boolean canReuseAnalysis(String caseId) {
        try {
            var run = workspaceService.latestCompletedAnalysisRun(caseId).orElse(null);
            if (run == null || !"COMPLETED".equals(run.getStatus())) {
                return false;
            }
            Map<String, Object> result = workspaceService.deserializeAnalysisResult(run);
            if (result == null || result.get("decisionAnswer") == null) {
                return false;
            }
            List<String> current = workspaceService.getWorkspaceDocuments(caseId).stream()
                    .map(WorkspaceDocumentLinkEntity::getDocumentUuid)
                    .filter(Objects::nonNull)
                    .map(UUID::toString)
                    .sorted()
                    .toList();
            List<String> stored = workspaceService.deserializeEvidenceIds(run);
            if (stored != null) {
                List<String> storedIds = stored.stream()
                        .map(s -> s.contains("@") ? s.substring(0, s.indexOf('@')) : s)
                        .sorted()
                        .toList();
                if (!storedIds.equals(current)) {
                    return false;
                }
            } else if (run.getDocumentCount() != current.size()) {
                return false;
            }
            // E-Mails, die NACH dem Analyse-Lauf eingegangen und zugeordnet
            // wurden, sind neue Fall-Informationen — die persistierte Analyse
            // darf dann nicht ungeprüft als Briefing-Grundlage dienen. Als
            // Zeitanker gilt der Abschlusszeitpunkt; fehlt er (beschädigter
            // Alt-Lauf), der Erstellungszeitpunkt des Laufs.
            Instant runEnd = run.getCompletedAt() != null ? run.getCompletedAt() : run.getCreatedAt();
            if (runEnd != null) {
                try {
                    boolean newerEmail = incomingEmailRepository
                            .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)).stream()
                            .anyMatch(e -> e.getReceivedAt() != null
                                    && e.getReceivedAt().isAfter(runEnd));
                    if (newerEmail) {
                        return false;
                    }
                } catch (Exception e) {
                    log.debug("E-Mail-Prüfung für Analyse-Wiederverwendung nicht möglich: {}",
                            e.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("Analyse-Wiederverwendung für Fall {} nicht möglich: {}", caseId, e.getMessage());
            return false;
        }
    }

    /**
     * Baut das Fallbriefing aus der PERSISTIERTEN Entscheidungsanalyse —
     * kein erneuter Retrieval-/Reasoning-Lauf. Die Analyse trägt bereits
     * Fakten, Belege, Feststellungen, offene Punkte und Konfidenz; das
     * Briefing ist eine strukturierte Aufbereitung dieser Artefakte.
     */
    public Briefing generateFromAnalysis(String caseId, String actorEmail) {
        WorkspaceEntity ws = workspaceService.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Fall nicht gefunden: " + caseId));
        var run = workspaceService.latestCompletedAnalysisRun(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Keine abgeschlossene Analyse: " + caseId));
        Map<String, Object> result = workspaceService.deserializeAnalysisResult(run);
        String answer = result != null && result.get("decisionAnswer") != null
                ? String.valueOf(result.get("decisionAnswer")) : "Keine Kurzfassung verfügbar.";

        List<String> erkenntnisse = labelsOf(result, "primaryFindings");
        List<String> schritte = labelsOf(result, "proceduralFindings");
        List<String> belege = new ArrayList<>();
        List<String> offenePunkte = new ArrayList<>();
        if (result != null) {
            if (result.get("evidenceItems") instanceof List<?> ev) {
                for (Object o : ev) {
                    if (o instanceof Map<?, ?> m && m.get("title") != null) {
                        String t = String.valueOf(m.get("title"));
                        if (!belege.contains(t)) {
                            belege.add(t);
                        }
                    }
                }
            }
            if (result.get("coverageIssues") instanceof List<?> ci) {
                ci.forEach(o -> offenePunkte.add(String.valueOf(o)));
            }
            if (result.get("missingDocs") instanceof List<?> md) {
                md.forEach(o -> offenePunkte.add("Fehlend: " + o));
            }
        }
        if (offenePunkte.isEmpty() && !Boolean.TRUE.equals(result != null ? result.get("grounded") : null)) {
            offenePunkte.add("Die Quellenlage ist eingeschränkt — die Analyse ist nicht vollständig belegt.");
        }
        int evidenceCount = belege.size();
        Object conf = result != null ? result.get("confidenceScore") : null;
        String quellenlage = (Boolean.TRUE.equals(result != null ? result.get("grounded") : null)
                ? "Belegt" : "Eingeschränkt")
                + " · " + evidenceCount + " Beleg(e) · "
                + (conf != null && !String.valueOf(conf).isBlank()
                        ? String.valueOf(conf) + " Konfidenz"
                        : "Konfidenz nicht verfügbar");
        return new Briefing(
                ws.getWorkspaceCode(),
                ws.getName(),
                erstelltAm(),
                answer,
                sachverhalt(ws, Map.of()),
                erkenntnisse,
                belege,
                zustaendigkeit(ws),
                offenePunkte,
                schritte.isEmpty() ? List.of("Empfehlung der Analyse fachlich prüfen.") : schritte,
                quellenlage,
                true,
                statusLabel(ws.getStatus()),
                fingerprint(caseId),
                "Fall-Analyse");
    }

    @SuppressWarnings("unchecked")
    private static List<String> labelsOf(Map<String, Object> result, String key) {
        List<String> labels = new ArrayList<>();
        if (result == null || !(result.get(key) instanceof List<?> raw)) {
            return labels;
        }
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m && m.get("label") != null) {
                String label = String.valueOf(m.get("label"));
                if (!labels.contains(label)) {
                    labels.add(label);
                }
            }
        }
        return labels;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Führt die kommunale Pipeline fallbezogen aus (Retrieval nur über die
     * Vorgangsdokumente) und baut daraus das strukturierte Briefing.
     */
    public Briefing generate(String caseId, String actorEmail) {
        return generate(caseId, actorEmail, UUID.randomUUID().toString());
    }

    /**
     * Wie {@link #generate(String, String)}, aber der Pipeline-Request trägt
     * die übergebene request id — der Fortschritts-Listener leitet die
     * Pipeline-Stufen über diese id an das zugehörige Job-Progress-Objekt
     * weiter (Job-Erstellung und Pipeline-Lauf teilen sich die id).
     */
    public Briefing generate(String caseId, String actorEmail, String requestId) {
        WorkspaceEntity ws = workspaceService.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Fall nicht gefunden: " + caseId));
        List<UUID> docIds = workspaceService.getWorkspaceDocuments(caseId).stream()
                .map(WorkspaceDocumentLinkEntity::getDocumentUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
        SearchFilter filter = new SearchFilter(Set.copyOf(docIds), null, null, null, null, null, null, null, List.of());
        String question = "Erstelle ein Fallbriefing für den Vorgang \"" + ws.getName()
                + "\". Fasse den Sachverhalt zusammen, benenne gesicherte Erkenntnisse, "
                + "offene Punkte und mögliche nächste Schritte. Unterscheide klar zwischen "
                + "Belegtem und Nicht-Belegtem.";
        AiRequest request = new AiRequest(question, null, filter,
                new AiConversationContext(List.of(), actorEmail, null, null, requestId),
                10, RetrievalScope.CURRENT_WORKSPACE, UUID.fromString(caseId), null);
        AiResponse response = aiFacade.answer(request);

        var answer = response.answer();
        Map<String, String> sections = splitSections(answer.answer());
        var findings = answer.findingHierarchy();
        List<String> supported = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (findings != null) {
            findings.primaryFindings().forEach(f -> supported.add(f.label()));
            findings.secondaryFindings().forEach(f -> unsupported.add(f.label()));
        }
        List<String> citations = answer.sourceCitations().stream()
                .map(SourceCitation::title)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();
        String zustaendigkeit = zustaendigkeit(ws);
        List<String> offenePunkte = new ArrayList<>(unsupported);
        if (!answer.grounded() && offenePunkte.isEmpty()) {
            offenePunkte.add("Die Quellenlage ist eingeschränkt — die Antwort ist nicht vollständig belegt.");
        }
        String quellenlage = (answer.grounded() ? "Belegt" : "Eingeschränkt")
                + " · " + citations.size() + " Beleg(e) · "
                + (answer.confidence() != null
                        ? Math.round(answer.confidence().overallConfidence() * 100) + " % Konfidenz"
                        : "Konfidenz nicht verfügbar");
        return new Briefing(
                ws.getWorkspaceCode(),
                ws.getName(),
                erstelltAm(),
                sections.getOrDefault("KURZANTWORT", "Keine Kurzfassung verfügbar."),
                sachverhalt(ws, sections),
                supported,
                citations,
                zustaendigkeit,
                offenePunkte,
                naechsteSchritte(sections),
                quellenlage,
                true,
                statusLabel(ws.getStatus()),
                fingerprint(caseId),
                "Analyse-Pipeline");
    }

    private static String erstelltAm() {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'")
                .format(Instant.now().atZone(ZoneId.systemDefault()));
    }

    /** Persistiert das Briefing im phaseData des Vorgangs (Schlüssel "briefing"). */
    public void store(String caseId, Briefing briefing) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            if (ws == null) return;
            Map<String, Object> data = new LinkedHashMap<>(ws.getPhaseDataMap());
            data.put("briefing", briefing);
            ws.setPhaseData(mapper.writeValueAsString(data));
            workspaceService.save(ws);
        } catch (Exception e) {
            log.warn("Fallbriefing konnte nicht gespeichert werden: {}", e.getMessage());
        }
    }

    /** Lädt das gespeicherte Briefing (oder null). */
    public Briefing load(String caseId) {
        try {
            WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
            if (ws == null) return null;
            Object raw = ws.getPhaseDataMap().get("briefing");
            if (raw == null) return null;
            return mapper.convertValue(raw, Briefing.class);
        } catch (Exception e) {
            log.warn("Fallbriefing konnte nicht geladen werden: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, String> splitSections(String rawAnswer) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (rawAnswer == null || rawAnswer.isBlank()) return sections;
        String[] markers = {"KURZANTWORT", "ENTSCHEIDUNG", "RECHTSGRUNDLAGE", "VERFAHREN", "NÄCHSTER SCHRITT"};
        for (int i = 0; i < markers.length; i++) {
            int start = rawAnswer.indexOf(markers[i]);
            if (start < 0) continue;
            int contentStart = start + markers[i].length();
            int end = rawAnswer.length();
            for (int j = i + 1; j < markers.length; j++) {
                int next = rawAnswer.indexOf(markers[j], contentStart);
                if (next >= 0 && next < end) end = next;
            }
            String section = rawAnswer.substring(contentStart, end)
                    .replaceAll("\\*\\*", "").replaceAll("__", "")
                    .replaceAll("\\n+", " ").trim();
            if (!section.isBlank()) sections.put(markers[i], section);
        }
        return sections;
    }

    private static String sachverhalt(WorkspaceEntity ws, Map<String, String> sections) {
        StringBuilder sb = new StringBuilder();
        if (ws.getDescription() != null && !ws.getDescription().isBlank()) {
            sb.append(ws.getDescription());
        }
        String entscheidung = sections.get("ENTSCHEIDUNG");
        if (entscheidung != null && !entscheidung.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(entscheidung);
        }
        return sb.length() == 0 ? "Keine Angaben zum Sachverhalt verfügbar." : sb.toString();
    }

    private static List<String> naechsteSchritte(Map<String, String> sections) {
        String step = sections.get("NÄCHSTER SCHRITT");
        if (step == null || step.isBlank()) {
            return List.of("Keine konkreten nächsten Schritte aus der Analyse verfügbar.");
        }
        return java.util.Arrays.stream(step.split("(?<=[.!?])\\s+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String zustaendigkeit(WorkspaceEntity ws) {
        if (ws.getGeoAuthority() != null && !ws.getGeoAuthority().isBlank()) {
            String district = ws.getGeoDistrict() != null && !ws.getGeoDistrict().isBlank()
                    ? " · " + ws.getGeoDistrict() : "";
            return ws.getGeoAuthority() + district;
        }
        if (ws.getOwnerId() != null && !ws.getOwnerId().isBlank()) {
            return "Bearbeiter/in: " + ws.getOwnerId();
        }
        return "Nicht bestimmt.";
    }

    private static String statusLabel(WorkspaceStatus status) {
        if (status == null) return "—";
        return switch (status) {
            case ACTIVE -> "Aktiv";
            case DRAFT -> "Entwurf";
            case ARCHIVED -> "Archiviert";
            case CLOSED -> "Abgeschlossen";
        };
    }
}
