package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.analytics.AnalyticsCollector;
import reasoning.ai.analytics.AnalyticsService;
import reasoning.ai.analytics.PipelineDiagnosticReport;
import reasoning.ai.analytics.PipelineDiagnostics;
import reasoning.ai.analytics.PipelineHealthReport;
import reasoning.ai.application.PipelineProfiler;
import reasoning.ai.governance.*;
import reasoning.ai.model.*;
import reasoning.ai.verification.DecisionRepairEngine;
import reasoning.ai.verification.DecisionVerifier;
import reasoning.ai.verification.RepairResult;
import reasoning.ai.verification.VerificationResult;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.ProcessingStatus;
import verwaltungsassistent.web.service.DecisionPdfExporter;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import verwaltungsassistent.web.util.DateTimeFormats;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import reasoning.workspace.api.WorkspaceDocumentDto;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Controller
public class DecisionWorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(DecisionWorkspaceController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormats.GERMAN_DATETIME;

    private final WorkspaceService workspaceService;
    private final reasoning.document.api.DocumentFacade documentFacade;
    private final AiFacade aiFacade;
    private final PipelineProfiler profiler;
    private final DecisionVerifier verifier;
    private final DecisionRepairEngine repairEngine;
    private final DecisionGovernanceService governance;
    private final AnalyticsCollector analyticsCollector;
    private final AnalyticsService analyticsService;
    private final PipelineDiagnostics pipelineDiagnostics;
    private final JobProgressService progressService;
    private final SpringTemplateEngine templateEngine;
    private final DecisionPdfExporter pdfExporter;
    private final verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard;
    private final reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository;
    private final verwaltungsassistent.web.service.AnalysisResultSanitizer analysisResultSanitizer;
    private final verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository;
    private final verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository emailAnalysisRepository;
    private final verwaltungsassistent.web.planning.CaseWorkStateService caseWorkStateService;
    private final verwaltungsassistent.web.service.CaseBriefingService caseBriefingService;
    private final ExecutorService executor;
    /** Generisches Verwaltungsvokabular (gleiche Quelle wie Retrieval/Grounding). */
    private final Set<String> stopWords;
    /** Ablage der Dokument-Quelldateien (gleiche Quelle wie der Dokument-Controller). */
    private final java.nio.file.Path uploadDir;

    public DecisionWorkspaceController(WorkspaceService workspaceService,
                                        reasoning.document.api.DocumentFacade documentFacade,
                                        verwaltungsassistent.web.service.AnalysisResultSanitizer analysisResultSanitizer,
                                        verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository incomingEmailRepository,
                                        verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository emailAnalysisRepository,
                                        verwaltungsassistent.web.planning.CaseWorkStateService caseWorkStateService,
                                        verwaltungsassistent.web.service.CaseBriefingService caseBriefingService,
                                        @Autowired(required = false) AiFacade aiFacade,
                                        @Autowired(required = false) PipelineProfiler profiler,
                                        @Autowired(required = false) DecisionVerifier verifier,
                                        @Autowired(required = false) DecisionRepairEngine repairEngine,
                                        @Autowired(required = false) DecisionGovernanceService governance,
                                        @Autowired(required = false) AnalyticsCollector analyticsCollector,
                                        @Autowired(required = false) AnalyticsService analyticsService,
                                        @Autowired(required = false) PipelineDiagnostics pipelineDiagnostics,
                                        JobProgressService progressService,
                                        SpringTemplateEngine templateEngine,
                                        DecisionPdfExporter pdfExporter,
                                        verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard,
                                        reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository,
                                        @org.springframework.beans.factory.annotation.Value(
                                                "${platform.ai.grounding.stop-words:}") String stopWordsCsv,
                                        @org.springframework.beans.factory.annotation.Value(
                                                "${app.upload-dir:uploads}") String uploadDirPath) {
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.analysisResultSanitizer = analysisResultSanitizer;
        this.incomingEmailRepository = incomingEmailRepository;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.caseWorkStateService = caseWorkStateService;
        this.caseBriefingService = caseBriefingService;
        this.aiFacade = aiFacade;
        this.profiler = profiler;
        this.verifier = verifier;
        this.repairEngine = repairEngine;
        this.governance = governance;
        this.analyticsCollector = analyticsCollector;
        this.analyticsService = analyticsService;
        this.pipelineDiagnostics = pipelineDiagnostics;
        this.progressService = progressService;
        this.templateEngine = templateEngine;
        this.pdfExporter = pdfExporter;
        this.caseAccessGuard = caseAccessGuard;
        this.userAccountRepository = userAccountRepository;
        this.uploadDir = java.nio.file.Paths.get(uploadDirPath).toAbsolutePath().normalize();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "analysis-worker");
            t.setDaemon(true);
            return t;
        });
        Set<String> words = new java.util.LinkedHashSet<>();
        if (stopWordsCsv != null) {
            for (String w : stopWordsCsv.split(",")) {
                String t = w.trim().toLowerCase(java.util.Locale.GERMANY);
                if (!t.isEmpty()) words.add(t);
            }
        }
        this.stopWords = Set.copyOf(words);
    }

    /**
     * Persistierter Analyse-Status aus dem phaseData-Marker (RUNNING /
     * COMPLETED / FAILED), den der Analyse-Worker bei jedem Lauf schreibt —
     * dieselbe Quelle, aus der die Fallseite ihren Analyse-Abschnitt baut.
     * Ohne Marker ("") ist noch nie eine Analyse gestartet worden.
     */
    @SuppressWarnings("unchecked")
    private static String analysisMarkerStatus(WorkspaceDto dto) {
        if (dto.phaseData() == null) {
            return "";
        }
        Object raw = dto.phaseData().get("analysis");
        if (!(raw instanceof Map<?, ?> marker)) {
            return "";
        }
        Object status = marker.get("status");
        return status != null ? String.valueOf(status) : "";
    }

    // ── Fall-Thema (Retrieval-Query + Hervorhebung) ──

    /**
     * Das Fall-Thema (Fallname + Beschreibung) ist der Text, gegen den die
     * Analyse EVIDENZ sucht und hervorhebt — nicht die Instruktions-Hülle
     * ("Analysiere den Fall … Erstelle: 1) Faktenzusammenfassung …"), deren
     * generische Wörter keinerlei Relevanz ausdrücken.
     */
    private static String caseTopicText(WorkspaceDto dto) {
        String name = dto.name() != null ? dto.name() : "";
        String desc = dto.description() != null ? dto.description().trim() : "";
        String topic = name;
        if (!desc.isBlank() && !desc.equalsIgnoreCase(name)) {
            topic = topic + ". " + desc;
        }
        return topic;
    }

    /**
     * Spezifische Hervorhebungs-Begriffe des Fall-Themas (Stoppwort-gefiltert,
     * mit morphologischen Varianten) — die Beleg-Ansicht markiert NUR diese
     * Wörter, niemals generische Boilerplate-Wörter der Analyseanweisung.
     *
     * <p>Reine PRÄSENTATION: die Markierung ist ein Textmarker zur
     * Orientierung im Beleg-Auszug, kein Retrieval-Signal. Deshalb werden
     * hier zusätzlich schwache Allerweltswörter (z. B. "letzte", "neue",
     * Monatsnamen) ausgefiltert, die die Markierung wie eine einfache
     * Stichwortsuche aussehen lassen, ohne inhaltliche Relevanz zu tragen —
     * das Retrieval selbst ist davon unberührt.</p>
     */
    private static final Set<String> WEAK_HIGHLIGHT_TERMS = Set.of(
            "letzte", "letzten", "letztes", "letzter", "nächste", "nächsten",
            "naechste", "naechsten", "aktuelle", "aktuellen", "aktueller", "aktuelles",
            "neue", "neuen", "neuer", "neues", "neuem", "monate", "monaten", "monat",
            "woche", "wochen", "tag", "tage", "tagen", "jahr", "jahre", "jahren");

    private List<String> highlightTerms(String topicText) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String term : reasoning.ai.application.DefaultRetrievalAugmentationService
                .specificTerms(topicText, stopWords)) {
            if (WEAK_HIGHLIGHT_TERMS.contains(term)) {
                continue;
            }
            out.add(term);
            out.addAll(reasoning.common.text.GermanTermVariants.of(term));
        }
        return new ArrayList<>(out);
    }

    // ── Vorgangsdokumente (DOKUMENTE IM VORGANG ≠ Analyse-Belege) ──

    /**
     * Vorschaubilder der Bilddokumente im Vorgang (JPG/PNG): Dateiname/Titel +
     * Base64-Daten-URL aus der echten Quelldatei. Gedrosselt auf kleine Dateien
     * (≤ 2,5 MB) und maximal 6 Bilder, damit das PDF-Exportdokument handlich bleibt.
     */
    private List<Map<String, String>> imageDocumentPreviews(WorkspaceDto dto, String actorId) {
        List<Map<String, String>> images = new ArrayList<>();
        if (dto.documents() == null) {
            return images;
        }
        for (WorkspaceDocumentDto doc : dto.documents()) {
            if (images.size() >= 6) {
                break;
            }
            try {
                var document = documentFacade.getDocument(UUID.fromString(doc.documentId()), actorId);
                var versions = document.versions();
                if (versions == null || versions.isEmpty()) {
                    continue;
                }
                var version = versions.get(versions.size() - 1);
                String contentType = version.contentType();
                if (contentType == null || !contentType.startsWith("image/")
                        || version.sizeBytes() > 2_500_000 || version.storageKey() == null) {
                    continue;
                }
                Path file = uploadDir.resolve(version.storageKey()).normalize();
                if (!file.startsWith(uploadDir) || !Files.isRegularFile(file)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("title", caseDocumentTitle(doc, actorId));
                row.put("dataUrl", "data:" + contentType + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(bytes));
                images.add(row);
            } catch (Exception e) {
                log.debug("Vorschaubild für Dokument {} nicht lesbar: {}", doc.documentId(), e.getMessage());
            }
        }
        return images;
    }

    /** Anzeigename eines Vorgangsdokuments: echter Dokument-Titel, sonst Link-Name, sonst Kurz-ID. */
    private String caseDocumentTitle(WorkspaceDocumentDto doc, String actorId) {
        try {
            var document = documentFacade.getDocument(UUID.fromString(doc.documentId()), actorId);
            if (document.metadata().title() != null && !document.metadata().title().isBlank()) {
                return document.metadata().title();
            }
        } catch (Exception e) {
            // version/name unknown — fall through to the link metadata
        }
        if (doc.documentName() != null && !doc.documentName().isBlank()) {
            return doc.documentName();
        }
        String id = doc.documentId();
        return id != null && id.length() > 8 ? "Dokument " + id.substring(0, 8) : "Dokument";
    }

    /** Anzeigedaten der tatsächlich am Vorgang hängenden Dokumente. */
    private List<CaseDocumentItem> caseDocuments(WorkspaceDto dto, String actorId) {
        List<CaseDocumentItem> items = new ArrayList<>();
        if (dto.documents() != null) {
            for (WorkspaceDocumentDto doc : dto.documents()) {
                items.add(new CaseDocumentItem(doc.documentId(), caseDocumentTitle(doc, actorId)));
            }
        }
        items.sort(Comparator.comparing(CaseDocumentItem::title, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    /** Anzeigedaten für ein Vorgangsdokument in der Entscheidungs-Ansicht. */
    public record CaseDocumentItem(String documentId, String title) {}

    /** Deterministisches Aktenzeichen wie im PDF-Export (51.10-<FALLART>-<JAHR>/<NUMMER>). */
    private static String aktenzeichen(String caseName, String workspaceCode) {
        String code = workspaceCode != null ? workspaceCode : "";
        String numeric = code.replaceAll("[^0-9]", "");
        if (numeric.isEmpty()) numeric = "000345";
        String norm = caseName != null ? caseName.toUpperCase().replaceAll("[^A-ZÄÖÜß]", "") : "";
        if (norm.length() >= 4) norm = norm.substring(0, 4);
        else if (norm.isEmpty()) norm = "ALLG";
        else norm = norm + "ALLG".substring(norm.length());
        return "51.10-" + norm + "-" + java.time.Year.now() + "/" + numeric;
    }

    /** Session-Schlüssel: Analyse-Lauf, der in DIESER Sitzung gestartet wurde. */
    private static final String SESSION_AUTO_OPEN_RUN = "verwaltungsassistent.decisionAutoOpenRun";

    @GetMapping("/cases/{id}/decision")
    public String decisionWorkspace(@PathVariable String id,
                                    @AuthenticationPrincipal AuthenticatedUser user,
                                    jakarta.servlet.http.HttpSession session,
                                    Model model) {
        WorkspaceEntity entity = caseAccessGuard.requireAccess(id, user);
        WorkspaceDto dto = workspaceService.toDto(entity);

        model.addAttribute("caseId", id);
        model.addAttribute("caseName", dto.name() != null ? dto.name() : dto.workspaceCode());
        model.addAttribute("casePhase", CaseDetailController.phaseLabel(dto.phase()));
        int documentCount = dto.documents() != null ? dto.documents().size() : 0;
        model.addAttribute("documentCount", documentCount);
        // DOKUMENTE IM VORGANG: die tatsächlich angehängten Dokumente (Titel +
        // Viewer-Id) — das ist NICHT die Beleg-Liste der Analyse.
        model.addAttribute("caseDocuments", caseDocuments(dto, user != null ? user.email() : "system"));
        String topicText = caseTopicText(dto);
        model.addAttribute("analysisHighlightTerms", String.join(" ", highlightTerms(topicText)));
        model.addAttribute("analysisHighlightQuery",
                java.net.URLEncoder.encode(String.join(" ", highlightTerms(topicText)),
                        java.nio.charset.StandardCharsets.UTF_8));
        // Der PERSISTIERTE Analyse-Marker (phaseData.analysis.status) ist die
        // zustandsautorisierende Quelle der Fallseite (RUNNING/COMPLETED/FAILED,
        // überlebt Neustarts). Die Entscheidungs-Seite muss denselben Marker
        // lesen — sonst zeigt sie eine fehlgeschlagene oder unterbrochene
        // Analyse fälschlich als "keine KI-Analyse" an, und die Navigation
        // Entscheidung ↔ Fall wird zur Endlos-Schleife.
        model.addAttribute("analysisStatus", analysisMarkerStatus(dto));
        // Wird gerade eine Analyse ausgeführt (z. B. vom Fall-Panel gestartet),
        // zeigt die Entscheidungs-Seite sofort den Fortschritt statt der
        // "keine Analyse"-Leere — der Doppel-Button entfällt.
        Job running = progressService.activeJob("analysis:" + id);
        if (running != null) {
            model.addAttribute("activeAnalysis", running);
            addProgressModel(model, running, "/cases/" + id + "/decision/analyze/progress/" + running.jobId,
                    "Analyse läuft",
                    "Die Analyse wird vorbereitet …",
                    "Die Analyse kann einen Moment dauern. Das System sucht relevante Dokumente, prüft ihre Eignung und erstellt eine begründete Empfehlung.");
        }
        // Real case metadata for the page header: Vorgangsnummer and the
        // responsible user (resolved from the account, never invented).
        model.addAttribute("workspaceCode", dto.workspaceCode());
        model.addAttribute("caseOwnerDisplay", ownerDisplayName(entity.getOwnerId()));
        // Fallart (workspaceType) und ein deterministisches Aktenzeichen für die
        // Sachbearbeiter-Ansicht; das Aktenzeichen wird wie im PDF-Export aus
        // Vorgangsnummer und Fallname gebildet.
        model.addAttribute("caseType", dto.workspaceType() != null && !dto.workspaceType().isBlank()
                ? dto.workspaceType() : "Allgemein");
        // E-Mails im Vorgang: ZÄHLUNG über die tatsächliche workspace_id-Zuordnung
        // (nicht über Timeline-Ereignisse abgeleitet).
        model.addAttribute("caseEmailCount", emailCountFor(id));
        model.addAttribute("aktenzeichen", aktenzeichen(dto.name(), dto.workspaceCode()));
        model.addAttribute("caseCreatedAt", entity.getCreatedAt() != null
                ? java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(entity.getCreatedAt().atZone(java.time.ZoneId.systemDefault())) : null);
        model.addAttribute("pageTitle", "Entscheidung: " + (dto.name() != null ? dto.name() : dto.workspaceCode())
                + " · " + dto.workspaceCode());
        model.addAttribute("activeSection", "cases");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Fälle", "/cases"),
                new HomeController.Breadcrumb(dto.name() != null ? dto.name() : dto.workspaceCode(), "/cases/" + id),
                new HomeController.Breadcrumb("Entscheidung", "/cases/" + id + "/decision")));

        // A previously completed analysis is shown from the persisted run:
        // viewing it never re-runs the AI pipeline. The completed state — and
        // damit der "PDF herunterladen"-Button — existiert NUR, wenn der Lauf
        // ein abrufbares Ergebnis trägt. Ein COMPLETED-Lauf ohne Ergebnis ist
        // kein nutzbarer Analyse-Zustand: er fällt in den ehrlichen
        // "Ergebnis nicht verfügbar"-Zustand (Marker COMPLETED) mit
        // funktionierendem Neustart — kein toter "Zum Fall"-Pfad.
        workspaceService.latestCompletedAnalysisRun(id).ifPresent(run -> {
            Map<String, Object> result = workspaceService.deserializeAnalysisResult(run);
            if (result == null) {
                log.info("Analyse-Lauf {} von {} ohne abrufbares Ergebnis — als 'nicht verfügbar' behandelt",
                        run.getVersion(), id);
                return;
            }
            model.addAttribute("latestAnalysis", analysisRunView(run));
            model.addAttribute("analysisRuns",
                    workspaceService.listAnalysisRuns(id).stream()
                            .map(this::analysisRunView).toList());
            model.addAttribute("hasNewEvidence",
                    hasNewEvidence(dto, user, run));
            sanitizeStaleConfidence(result);
            analysisResultSanitizer.sanitize(result);
            model.addAllAttributes(result);
            // The persisted confidence is a plain map after the JSON
            // round-trip; reconstruct the profile the fragment calls.
            model.addAttribute("confidence", toConfidenceProfile(result.get("confidence")));
            model.addAttribute("evidenceGroups", groupEvidence(result.get("evidenceItems")));
            // Einmaliges automatisches PDF-Öffnen nach einem FRISCHEN
            // Abschluss: wurde dieser Lauf in DIESER Sitzung gestartet und
            // der Nutzer kommt erst nach Abschluss auf der Seite an, öffnet
            // die fertige PDF genau einmal — und nur kurz nach dem Abschluss
            // (5 Minuten), nie bei einem späteren Besuch. Der Live-Abschluss
            // über den Fortschritts-Poll verbraucht das Flag ebenfalls, sodass
            // kein zweiter Tab entstehen kann.
            if (session != null) {
                Object autoOpen = session.getAttribute(SESSION_AUTO_OPEN_RUN);
                if (autoOpen instanceof Map<?, ?> marker
                        && Integer.valueOf(run.getVersion()).equals(marker.get("version"))
                        && run.getCompletedAt() != null
                        && run.getCompletedAt().isAfter(
                                Instant.now().minus(Duration.ofMinutes(5)))) {
                    model.addAttribute("autoOpenPdf", true);
                }
                session.removeAttribute(SESSION_AUTO_OPEN_RUN);
            }
        });

        // Issue 2: Ohne Fall-Analyse wird die vorläufige E-Mail-Analyse (Triage)
        // als Kontext gezeigt, falls der Fall aus einer analysierten E-Mail stammt.
        if (model.getAttribute("latestAnalysis") == null) {
            PreliminaryEmailAnalysis preliminary = preliminaryEmailAnalysis(dto);
            if (preliminary != null) {
                model.addAttribute("preliminaryEmailAnalysis", preliminary);
            }
        }

        // Fallbriefing-Zugang auf der Entscheidungs-Seite: nur wenn ein zum
        // aktuellen Stand passendes Briefing existiert (kein veralteter Stand).
        model.addAttribute("briefingAvailable", briefingAvailable(id));
        // Phase 2D.3: dokumentierte Entscheidung der Sachbearbeitung (oder
        // null = noch offen) — ein Vorgangs-Zustand, getrennt vom Analyse-
        // Ergebnis und der KI-Empfehlung.
        model.addAttribute("decisionInfo", decisionInfoOf(id));
        // Phase 2D.7: abgeschlossener Vorgang = Endzustand — die Seite zeigt
        // nur noch lesend; Analyse-/Entscheidungs-Aktionen erfordern die
        // explizite Wiederaufnahme (Serverseite lehnt sie zusätzlich ab).
        model.addAttribute("caseClosed", entity.getStatus()
                == reasoning.common.model.WorkspaceStatus.CLOSED);

        return "cases/decision";
    }

    /**
     * Ein abrufbares, zum aktuellen Fallstand passendes Fallbriefing ist
     * vorhanden (gespeichert und nicht veraltet) — dann bietet die
     * Entscheidungs-Seite "Fallbriefing öffnen" an.
     */
    private boolean briefingAvailable(String caseId) {
        try {
            if (caseBriefingService == null) {
                return false;
            }
            verwaltungsassistent.web.service.CaseBriefingService.Briefing briefing =
                    caseBriefingService.load(caseId);
            return briefing != null && !caseBriefingService.isStale(caseId, briefing);
        } catch (Exception e) {
            log.debug("Fallbriefing-Verfügbarkeit für {} nicht lesbar: {}", caseId, e.getMessage());
            return false;
        }
    }

    /** Anzeige-Daten der vorläufigen E-Mail-Analyse (Triage), sofern vorhanden. */
    public record PreliminaryEmailAnalysis(String subject, String aiAnswer, Boolean grounded,
                                           Integer confidence, List<Map<String, Object>> evidence) {}

    /**
     * Issue 2 — Wiederverwendung statt Doppelarbeit: Ein aus einer E-Mail
     * angelegter Fall trägt die E-Mail-Analyse (Vorprüfung/Triage) bereits in
     * sich (phaseData.sourceEmailId). Liegt NOCH keine Fall-Analyse vor, wird
     * die vorläufige E-Mail-Analyse als Kontext gezeigt — die Analyse wird
     * nicht verworfen, sondern sichtbar wiederverwendet. Sobald eine Fall-
     * Analyse existiert, übernimmt deren Ergebnis die Anzeige. Wird neues
     * Material ergänzt (neue Dokumente), ist die Fall-Analyse über den
     * bestehenden hasNewEvidence-Mechanismus als veraltet markiert.
     */
    private PreliminaryEmailAnalysis preliminaryEmailAnalysis(WorkspaceDto dto) {
        if (dto.phaseData() == null) {
            return null;
        }
        Object source = dto.phaseData().get("sourceEmailId");
        if (!(source instanceof String sourceId) || sourceId.isBlank()) {
            return null;
        }
        try {
            var entity = emailAnalysisRepository.findById(UUID.fromString(sourceId)).orElse(null);
            if (entity == null || entity.getResultJson() == null) {
                return null;
            }
            Map<String, Object> outcome = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(entity.getResultJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object answer = outcome.get("aiAnswer");
            if (answer == null) {
                return null;
            }
            Boolean grounded = outcome.get("aiGrounded") instanceof Boolean b ? b : null;
            Integer confidence = outcome.get("aiConfidence") instanceof Number n ? n.intValue() : null;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evidence = outcome.get("aiEvidence") instanceof List<?> raw
                    ? raw.stream().filter(o -> o instanceof Map<?, ?>).map(o -> (Map<String, Object>) o).toList()
                    : List.of();
            return new PreliminaryEmailAnalysis(
                    entity.getSubject() != null ? entity.getSubject() : "E-Mail",
                    String.valueOf(answer), grounded, confidence, evidence);
        } catch (Exception e) {
            log.debug("Vorläufige E-Mail-Analyse für Fall {} nicht lesbar: {}", dto.id(), e.getMessage());
            return null;
        }
    }

    /** Anzahl der E-Mails, die diesem Fall (workspace_id) tatsächlich zugeordnet sind. */
    private int emailCountFor(String caseId) {
        try {
            return incomingEmailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)).size();
        } catch (Exception e) {
            log.debug("E-Mail-Zählung für Fall {} fehlgeschlagen: {}", caseId, e.getMessage());
            return 0;
        }
    }

    /**
     * Anzeige-Referenzen der zugeordneten E-Mails für das PDF
     * ("E-Mail: <Betreff>", gekürzt): die Entscheidungsvorlage dokumentiert
     * die Bürgerkommunikation, ohne die E-Mail als physisches Dokument zu
     * behandeln.
     */
    private List<String> emailReferencesFor(String caseId) {
        try {
            return incomingEmailRepository
                    .findByWorkspaceIdOrderByReceivedAtDesc(UUID.fromString(caseId)).stream()
                    .map(e -> e.getSubject() != null ? e.getSubject() : "E-Mail")
                    .map(s -> s.length() > 60 ? s.substring(0, 60) + "…" : s)
                    .map(s -> "E-Mail: " + s)
                    .toList();
        } catch (Exception e) {
            log.debug("E-Mail-Referenzen für Fall {} nicht lesbar: {}", caseId, e.getMessage());
            return List.of();
        }
    }

    /** Display name of the responsible user (owner) or the raw e-mail when no account is found. */
    private String ownerDisplayName(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return "—";
        try {
            var account = userAccountRepository.findByEmail(ownerId.trim().toLowerCase());
            if (account.isPresent() && account.get().getDisplayName() != null
                    && !account.get().getDisplayName().isBlank()) {
                return account.get().getDisplayName();
            }
        } catch (Exception e) {
            log.debug("Owner lookup failed for {}: {}", ownerId, e.getMessage());
        }
        return ownerId;
    }

    /**
     * Normalized case category shown in the PDF "Fallart" box. The value is
     * stored in phaseData.caseCategory when the case is created; older cases
     * fall back to workspaceType or the case name heuristic.
     */
    private String caseCategory(WorkspaceDto dto) {
        Map<String, Object> phaseData = dto.phaseData() != null ? dto.phaseData() : Map.of();
        Object stored = phaseData.get("caseCategory");
        if (stored != null && !String.valueOf(stored).isBlank()) {
            return String.valueOf(stored);
        }
        if ("GEO".equalsIgnoreCase(dto.workspaceType())) {
            return "Geovorgang";
        }
        String name = dto.name() != null ? dto.name() : "";
        String lower = name.toLowerCase();
        if (lower.contains("gewerbe")) return "Gewerbeanmeldung";
        if (lower.contains("wohngeld")) return "Wohngeld";
        if (lower.contains("reisepass")) return "Reisepass";
        if (lower.contains("ummeldung")) return "Ummeldung";
        if (lower.contains("bau")) return "Baugenehmigung";
        if (lower.contains("umzug")) return "Ummeldung";
        return dto.workspaceType() != null && !dto.workspaceType().isBlank()
                ? dto.workspaceType() : "Allgemein";
    }

    /** Enriches the model with the owner/Bearbeiter details used by the PDF template. */
    private void enrichOwnerDetails(Map<String, Object> model, String ownerId) {
        model.put("caseOwnerDisplay", ownerDisplayName(ownerId));
        model.put("caseOwnerRole", "Sachbearbeitung Bürgerdienste");
        model.put("caseOwnerRoom", "");
        model.put("caseOwnerPhone", "");
        model.put("caseOwnerEmail", ownerId != null ? ownerId : "");

        if (ownerId == null || ownerId.isBlank()) {
            return;
        }
        try {
            var account = userAccountRepository.findByEmail(ownerId.trim().toLowerCase());
            if (account.isEmpty()) {
                return;
            }
            var user = account.get();
            String position = user.getPosition();
            String department = user.getDepartment();
            String role = position != null && !position.isBlank()
                    ? position
                    : (department != null && !department.isBlank() ? department : "Sachbearbeitung Bürgerdienste");
            model.put("caseOwnerRole", role);
            if (user.getRoom() != null && !user.getRoom().isBlank()) {
                model.put("caseOwnerRoom", "Zimmer: " + user.getRoom());
            }
            if (user.getPhone() != null && !user.getPhone().isBlank()) {
                model.put("caseOwnerPhone", user.getPhone());
            }
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                model.put("caseOwnerEmail", user.getEmail());
            }
        } catch (Exception e) {
            log.debug("Owner detail lookup failed for {}: {}", ownerId, e.getMessage());
        }
    }

    /** Renders one persisted analysis run as the decision result fragment (no AI involved). */
    @GetMapping("/cases/{id}/decision/result/{version}")
    public String analysisResultVersion(@PathVariable String id,
                                        @PathVariable int version,
                                        @AuthenticationPrincipal AuthenticatedUser user,
                                        Model model) {
        caseAccessGuard.requireAccess(id, user);
        var run = workspaceService.findAnalysisRun(id, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Analyse-Version nicht gefunden"));
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Analyse-Version nicht verfügbar");
        }
        Map<String, Object> result = workspaceService.deserializeAnalysisResult(run);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Analyse-Ergebnis nicht verfügbar");
        }
        sanitizeStaleConfidence(result);
        analysisResultSanitizer.sanitize(result);
        model.addAllAttributes(result);
        model.addAttribute("confidence", toConfidenceProfile(result.get("confidence")));
        model.addAttribute("evidenceGroups", groupEvidence(result.get("evidenceItems")));
        model.addAttribute("briefingAvailable", briefingAvailable(id));
        // Hervorhebungs-Begriffe für ältere Runs neu aus dem Fall-Thema ableiten
        // (nur gesetzt, wenn der Run sie noch nicht trägt).
        if (model.getAttribute("analysisHighlightTerms") == null) {
            WorkspaceDto dto = workspaceService.toDto(workspaceService.findById(id).orElseThrow());
            model.addAttribute("analysisHighlightTerms",
                    String.join(" ", highlightTerms(caseTopicText(dto))));
            model.addAttribute("analysisHighlightQuery",
                    java.net.URLEncoder.encode(
                            String.join(" ", highlightTerms(caseTopicText(dto))),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        model.addAttribute("decisionInfo", decisionInfoOf(id));
        model.addAttribute("caseClosed",
                workspaceService.findById(id)
                        .map(e -> e.getStatus()
                                == reasoning.common.model.WorkspaceStatus.CLOSED)
                        .orElse(false));
        return "cases/decision-fragments :: decisionResult";
    }

    /**
     * Phase 2D.3 — Entscheidung dokumentieren: Die zuständige Sachbearbeitung
     * bestätigt explizit, die Entscheidung auf Grundlage der (abgeschlossenen)
     * KI-Analyse zu treffen. Festgehalten wird NUR ein schlanker, persistierter
     * Vorgangs-Zustand (wer, wann, welche Analyse-Version) — die KI-Analyse
     * wird nicht verändert, der Vorgang nicht geschlossen, nichts wird
     * versendet. Leitungs-Konten sind schreibgeschützt (requireWriteAccess);
     * eine bereits dokumentierte Entscheidung wird nie überschrieben.
     */
    @PostMapping("/cases/{id}/decision/confirm")
    public String confirmDecision(@PathVariable String id,
                                  @AuthenticationPrincipal AuthenticatedUser user,
                                  org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        WorkspaceEntity entity = caseAccessGuard.requireWriteAccess(id, user);
        if (entity.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED) {
            redirectAttributes.addFlashAttribute("decisionFlashError",
                    "Der Vorgang ist bereits abgeschlossen — die Entscheidung kann nicht mehr dokumentiert werden.");
            return "redirect:/cases/" + id + "/decision";
        }
        if (decisionInfoOf(id) != null) {
            redirectAttributes.addFlashAttribute("decisionFlashError",
                    "Die Entscheidung wurde bereits dokumentiert und wird nicht überschrieben.");
            return "redirect:/cases/" + id + "/decision";
        }
        var run = workspaceService.latestCompletedAnalysisRun(id).orElse(null);
        if (run == null) {
            redirectAttributes.addFlashAttribute("decisionFlashError",
                    "Für diesen Vorgang liegt keine abgeschlossene Analyse vor. Bitte schließen Sie zuerst die Analyse ab.");
            return "redirect:/cases/" + id + "/decision";
        }
        String actorEmail = user != null && user.email() != null ? user.email() : "system";
        String display = ownerDisplayName(actorEmail);
        if (display == null || display.isBlank() || "—".equals(display)) {
            display = actorEmail;
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("confirmedBy", actorEmail);
        decision.put("confirmedByName", display);
        decision.put("confirmedAt", Instant.now().toString());
        decision.put("analysisVersion", run.getVersion());
        try {
            workspaceService.updatePhaseData(id, Map.of("decision", decision));
        } catch (RuntimeException e) {
            log.warn("Entscheidungs-Dokumentation für Fall {} fehlgeschlagen: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("decisionFlashError",
                    "Die Entscheidung konnte nicht dokumentiert werden. Bitte versuchen Sie es erneut.");
            return "redirect:/cases/" + id + "/decision";
        }
        try {
            workspaceService.addTimelineEvent(id, java.time.LocalDate.now(),
                    "Entscheidung dokumentiert",
                    "Entscheidung dokumentiert durch " + display
                            + " auf Grundlage der Analyse #" + run.getVersion() + ".",
                    reasoning.workspace.model.TimelineEventType.DECISION,
                    null, 1.0, false);
        } catch (Exception e) {
            log.warn("Entscheidungs-Timeline-Eintrag für Fall {} fehlgeschlagen: {}", id, e.getMessage());
        }
        redirectAttributes.addFlashAttribute("decisionFlash",
                "Ihre Entscheidung wurde dokumentiert (Analyse #" + run.getVersion() + "). "
                        + "Die KI-Analyse bleibt unverändert; der Vorgang wird nicht automatisch geschlossen.");
        return "redirect:/cases/" + id + "/decision";
    }

    /**
     * Exact identities of the currently attached documents, sorted
     * ("documentId@version"); a failed version lookup keeps the plain id so
     * the identity remains exact at the document level.
     */
    private List<String> evidenceIdentities(WorkspaceDto dto, String actorId) {
        List<String> identities = new ArrayList<>();
        if (dto.documents() != null) {
            for (WorkspaceDocumentDto doc : dto.documents()) {
                String id = doc.documentId();
                String identity = id;
                try {
                    var document = documentFacade.getDocument(UUID.fromString(id), actorId);
                    identity = id + "@v" + document.currentVersion();
                } catch (Exception e) {
                    // version unknown — the plain id still marks the document
                }
                identities.add(identity);
            }
        }
        Collections.sort(identities);
        return identities;
    }

    /**
     * Evidence changed when the exact set of attached document identities
     * differs from the run's basis (catches replaced/removed documents even
     * when the count is unchanged). Legacy runs without persisted identities
     * fall back to the count comparison.
     */
    private boolean hasNewEvidence(WorkspaceDto dto, AuthenticatedUser user,
                                   reasoning.workspace.api.WorkspaceAnalysisRunEntity run) {
        List<String> current = evidenceIdentities(dto, user != null ? user.email() : "system");
        List<String> stored = workspaceService.deserializeEvidenceIds(run);
        if (stored == null) {
            int currentCount = dto.documents() != null ? dto.documents().size() : 0;
            return currentCount != run.getDocumentCount();
        }
        return !current.equals(stored);
    }

    /**
     * Strips developer-diagnostic entries (records that do not survive a JSON
     * round-trip as objects) from the model before it is persisted as a run.
     */
    private static Map<String, Object> persistableModel(Map<String, Object> m) {
        Map<String, Object> copy = new HashMap<>(m);
        List.of("debugMode", "debugProfile", "debugTotalMs", "debugMetadata",
                        "pipelineHealth", "diagnosticReport", "verificationResult",
                        "repairResult", "governanceComparison", "decisionLineage")
                .forEach(copy::remove);
        return copy;
    }

    /**
     * Legacy runs (persisted by an older pipeline) can carry non-zero
     * confidence values although the run found NO evidence at all. The
     * current pipeline never produces that combination — a run without
     * evidence is always insufficient — so such persisted values are stale
     * and must not be displayed as if they were derived from sources.
     * Forces the insufficient-evidence profile ("nicht bewertbar") and
     * clears the derived display values. Evidence-bearing runs are untouched.
     */
    private static void sanitizeStaleConfidence(Map<String, Object> m) {
        boolean hasEvidence = m.get("evidenceItems") instanceof List<?> evidence && !evidence.isEmpty();
        if (hasEvidence) {
            return;
        }
        ConfidenceProfile insufficient = ConfidenceProfile.insufficientEvidence();
        m.put("confidence", insufficient);
        m.put("confidenceScore", "nicht bewertbar");
        m.put("overallFillClass", fillClass(0));
        m.put("sourceFillClass", fillClass(0));
        m.put("completenessFillClass", fillClass(0));
        m.put("coverageFillClass", fillClass(0));
        m.put("grounded", false);
        // Authority references from a stale run are unsupported without any
        // evidence — never present them as legal foundations.
        m.put("authorities", List.of());
    }

    /** Reconstructs a {@link ConfidenceProfile} from a persisted (map) or live (record) value. */
    private static ConfidenceProfile toConfidenceProfile(Object raw) {
        if (raw instanceof ConfidenceProfile profile) {
            return profile;
        }
        if (raw instanceof Map<?, ?> map) {
            return new ConfidenceProfile(
                    num(map.get("sourceConfidence")),
                    num(map.get("semanticConfidence")),
                    num(map.get("structuralConfidence")),
                    num(map.get("completenessConfidence")),
                    num(map.get("overallConfidence")),
                    map.get("explanation") != null ? String.valueOf(map.get("explanation")) : "");
        }
        return ConfidenceProfile.none();
    }

    private static double num(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /** View data for one analysis run (shown in the decision page). */
    private AnalysisRunView analysisRunView(reasoning.workspace.api.WorkspaceAnalysisRunEntity run) {
        return new AnalysisRunView(run.getVersion(),
                run.getCompletedAt() != null
                        ? DATE_FMT.format(run.getCompletedAt().atZone(ZoneId.systemDefault())) : "—",
                run.getDocumentCount(), run.getTriggeredBy());
    }

    /** Display data for one persisted analysis run. */
    public record AnalysisRunView(int version, String completedAt, int documentCount, String triggeredBy) {
        public int getVersion() { return version; }
        public String getCompletedAt() { return completedAt; }
        public int getDocumentCount() { return documentCount; }
        public String getTriggeredBy() { return triggeredBy; }
    }

    /**
     * Dokumentierte Entscheidung der Sachbearbeitung (Phase 2D.3): bewusst
     * schlank — festgehalten wird NUR, wer wann auf Grundlage welcher Analyse
     * (#version) die Entscheidung im Vorgang getroffen hat. Der Entscheidungs-
     * inhalt selbst lebt im weiteren Bearbeitungsfluss (Antwortentwurf,
     * Vorgangsabschluss); dieser Zustand trennt die KI-Empfehlung sichtbar von
     * der menschlichen Entscheidung.
     */
    public record DecisionInfo(String confirmedByName, String confirmedAt, int analysisVersion) {}

    /**
     * Liest die dokumentierte Entscheidung aus einem phaseData-Objekt (Map,
     * wie sie {@code WorkspaceDto.phaseData()} bzw. ein JSON-Parse liefert) —
     * öffentlich/statisch, damit die Fallseite (Überprüfung/Abschluss,
     * Phase 2D.4) denselben Zustand aus DERSELBEN Quelle anzeigt wie die
     * Entscheidungs-Seite (Phase 2D.3, geschrieben von {@link #confirmDecision}).
     * Ohne Dokumentation oder bei unlesbarem Eintrag null — die Ansicht zeigt
     * dann den „Noch offen"-Zustand.
     */
    public static DecisionInfo decisionFromPhaseData(Map<String, Object> phaseData) {
        if (phaseData == null) {
            return null;
        }
        Object raw = phaseData.get("decision");
        if (!(raw instanceof Map<?, ?> decision)) {
            return null;
        }
        Object at = decision.get("confirmedAt");
        Object version = decision.get("analysisVersion");
        if (!(at instanceof String atIso) || atIso.isBlank() || !(version instanceof Number n)) {
            return null;
        }
        String name = decision.get("confirmedByName") instanceof String s && !s.isBlank()
                ? s
                : (decision.get("confirmedBy") instanceof String by && !by.isBlank() ? by : "—");
        try {
            return new DecisionInfo(name,
                    DateTimeFormats.GERMAN_DATETIME.format(
                            Instant.parse(atIso).atZone(ZoneId.systemDefault())),
                    n.intValue());
        } catch (Exception e) {
            log.warn("Dokumentierte Entscheidung unlesbar (confirmedAt '{}'): {}", atIso, e.getMessage());
            return null;
        }
    }

    /** Lesekomfort-Wrapper über {@link #decisionFromPhaseData} für die Entscheidungs-Seite. */
    private DecisionInfo decisionInfoOf(String caseId) {
        try {
            WorkspaceDto dto = workspaceService.toDto(
                    workspaceService.findById(caseId).orElseThrow());
            return decisionFromPhaseData(dto.phaseData());
        } catch (Exception e) {
            log.warn("Dokumentierte Entscheidung für Fall {} nicht lesbar: {}", caseId, e.getMessage());
            return null;
        }
    }

    @PostMapping("/cases/{id}/decision/analyze")
    public String startAnalysis(@PathVariable String id,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 org.springframework.security.web.csrf.CsrfToken csrfToken,
                                 @RequestParam(value = "debug", required = false) String debug,
                                 @RequestParam(value = "asOf", required = false) String asOfParam,
                                 @RequestParam(value = "redirect", required = false) String redirect,
                                 @RequestParam(value = "advance", required = false) String advance,
                                 jakarta.servlet.http.HttpServletResponse response,
                                 jakarta.servlet.http.HttpSession session,
                                 Model model) {
        caseAccessGuard.requireWriteAccess(id, user);
        // Überführung temporärer View-Anspruch → echte Zuweisung: Der Analyse-
        // Start ist der Pipeline-Start des Falls. War der Vorgang ein freier
        // Arbeitspool-Vorgang mit gültigem Lease der anfragenden Mitarbeiterin,
        // wird er ATOMAR zu ihrer Zuweisung (owner + explizite Arbeitsaufnahme
        // über den bestehenden CaseAssignmentService). Fremder Anspruch → 423.
        // Ohne Anspruch gelten die bisherigen Autorisierungs-Semantiken.
        if (user != null && user.email() != null) {
            var ctx = verwaltungsassistent.web.config.SpringContextProvider.context();
            if (ctx != null) {
                try {
                    var claimSvc = ctx.getBean(
                            verwaltungsassistent.web.planning.CaseViewClaimService.class);
                    var entity = workspaceService.findById(id).orElse(null);
                    if (verwaltungsassistent.web.planning.CaseViewClaimService
                            .isClaimable(entity)
                            && claimSvc.promoteIfClaimedBy(id, user.email())) {
                        ctx.getBean(verwaltungsassistent.web.planning.CaseAssignmentService.class)
                                .assign(id, user.email(), true);
                    }
                } catch (verwaltungsassistent.web.planning.CaseViewClaimService
                        .ClaimConflictException e) {
                    throw new ResponseStatusException(HttpStatus.LOCKED,
                            "Dieser Vorgang wird gerade von einer anderen Mitarbeiterin bearbeitet.");
                }
            }
        }
        // Abgeschlossener Vorgang = Endzustand (Phase 2D.7): eine neue Analyse
        // (inkl. Arbeitsaufnahme) ist erst nach der EXPLIZITEN Wiederaufnahme
        // möglich — sonst würde die Invariante "nie CLOSED mit aktivem
        // Arbeitszustand" (Phase 2B.6) umgangen.
        if (workspaceService.findById(id)
                .map(e -> e.getStatus() == reasoning.common.model.WorkspaceStatus.CLOSED)
                .orElse(false)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Der Vorgang ist abgeschlossen — eine Analyse kann nur nach einer Wiederaufnahme gestartet werden.");
        }
        // Einzelne "Analyse starten"-Aktion aus der Ingestion-Phase: die Aktion
        // führt die Phase INGESTION → ANALYSIS UND startet die Analyse in einem
        // Schritt. Der Phasen-Balken bleibt reine Navigation; es gibt keinen
        // zweiten "Analyse starten"-Schritt mehr vor dem eigentlichen Start.
        if ("true".equals(advance)) {
            try {
                if (workspaceService.findById(id).map(WorkspaceEntity::getPhase)
                        .orElse(null) == reasoning.common.model.WorkspacePhase.INGESTION
                        && workspaceService.advanceBlockReason(id) == null) {
                    workspaceService.advancePhase(id);
                }
            } catch (RuntimeException e) {
                log.warn("Phase advance with analyse start skipped for {}: {}", id, e.getMessage());
            }
        }
        if (aiFacade == null) {
            model.addAttribute("caseId", id);
            model.addAttribute("caseName", caseNameOf(id));
            model.addAttribute("analysisError", "KI-Dienst ist nicht verfügbar. Bitte konfigurieren Sie einen AI-Provider.");
            model.addAttribute("analysisComplete", false);
            return "cases/decision-fragments :: decisionResult";
        }

        Job job = progressService.activeJob("analysis:" + id);
        if (job == null) {
            job = progressService.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
            progressService.registerActive("analysis:" + id, job.jobId);
            String actorEmail = user != null ? user.email() : null;
            final String newJobId = job.jobId;
            workspaceService.recordAnalysisStatus(id, "RUNNING", Map.of());
            int analysisVersion = workspaceService.startAnalysisRun(id, actorEmail);
            final int version = analysisVersion;
            // Merker für das EINMALIGE automatische PDF-Öffnen nach Abschluss:
            // nur der in dieser Sitzung gestartete Lauf darf beim (verspäteten)
            // Eintreffen auf der Seite die PDF automatisch öffnen — und nur
            // kurz nach Abschluss (Zeitfenster in decisionWorkspace).
            if (session != null) {
                session.setAttribute(SESSION_AUTO_OPEN_RUN,
                        java.util.Map.of("version", version, "startedAt", Instant.now()));
            }
            // Analyse starten = explizite Arbeitsaufnahme: workState ACTIVE.
            caseWorkStateService.markActive(id, actorEmail);
            executor.submit(() -> runAnalysis(newJobId, id, actorEmail, debug, version, asOfParam,
                    csrfToken != null ? csrfToken.getToken() : null));
        }
        // Vom Fall-Panel gestartet (redirect=true): die Analyse läuft im
        // Hintergrund und der Browser wird zur Entscheidungs-Seite geführt,
        // die den Fortschritt anzeigt — dort gibt es keinen zweiten
        // "Analyse starten"-Button.
        if ("true".equals(redirect)) {
            response.setHeader("HX-Redirect", "/cases/" + id + "/decision");
        }
        addProgressModel(model, job, "/cases/" + id + "/decision/analyze/progress/" + job.jobId,
                "Analyse läuft",
                "Die Analyse wird vorbereitet …",
                "Die Analyse kann einen Moment dauern. Das System sucht relevante Dokumente, prüft ihre Eignung und erstellt eine begründete Empfehlung.");
        return "fragments/progress :: progressPanel";
    }

    /** Poll endpoint: progress panel while running, rendered result on completion. */
    @GetMapping("/cases/{id}/decision/analyze/progress/{jobId}")
    public ResponseEntity<String> analysisProgress(@PathVariable String id,
                                                   @PathVariable String jobId,
                                                   @AuthenticationPrincipal AuthenticatedUser user,
                                                   jakarta.servlet.http.HttpSession session) {
        caseAccessGuard.requireAccess(id, user);
        Job job = progressService.get(jobId);
        if (job == null || "ERROR".equals(job.state)) {
            Map<String, Object> m = new HashMap<>();
            m.put("caseId", id);
            m.put("caseName", caseNameOf(id));
            m.put("analysisError", job == null
                    ? "Die Analyse ist nicht mehr verfügbar. Bitte starten Sie die Analyse erneut."
                    : "Die Analyse konnte nicht abgeschlossen werden.");
            m.put("analysisComplete", false);
            return html(render("cases/decision-fragments", "decisionResult", m));
        }
        if ("DONE".equals(job.state)) {
            // Der Live-Abschluss öffnet die PDF bereits über das Abschluss-
            // Fragment — das Sitzungs-Flag verbrauchen, damit ein späteres
            // Laden der Seite keinen zweiten Tab öffnet.
            if (session != null) {
                session.removeAttribute(SESSION_AUTO_OPEN_RUN);
            }
            return html((String) job.outcome);
        }
        Map<String, Object> m = progressModel(job, "/cases/" + id + "/decision/analyze/progress/" + job.jobId,
                "Analyse läuft",
                "Die Analyse wird vorbereitet …",
                "Die Analyse kann einen Moment dauern. Das System sucht relevante Dokumente, prüft ihre Eignung und erstellt eine begründete Empfehlung.");
        return html(render("fragments/progress", "progressPanel", m));
    }

    /**
     * Exports the completed analysis as an administrative-style PDF draft.
     * Reuses the structured result stored with the analysis job — the
     * pipeline is never re-run, so no additional AI calls are triggered.
     */
    @GetMapping("/cases/{id}/decision/export-pdf")
    public ResponseEntity<byte[]> exportDecisionPdf(@PathVariable String id,
                                                    @RequestParam(value = "job", required = false) String jobId,
                                                    @RequestParam(value = "inline", required = false) String inline,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireAccess(id, user);
        WorkspaceEntity entity = workspaceService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
        WorkspaceDto dto = workspaceService.toDto(entity);
        // Die Fehlermeldung soll den WIRKLICHEN Zustand nennen: ein fehlgeschlagener
        // oder unterbrochener Lauf ist keine "nicht mehr verfügbare Vorlage".
        String markerStatus = analysisMarkerStatus(dto);
        Job job = jobId != null ? progressService.get(jobId) : null;
        Map<String, Object> model;
        if (job != null && job.outcomeData != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> jobModel = new HashMap<>((Map<String, Object>) job.outcomeData);
            model = jobModel;
        } else {
            // Fall back to the persisted completed run so the decision
            // preparation remains reproducible after restarts / TTL expiry.
            var run = workspaceService.latestCompletedAnalysisRun(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "FAILED".equals(markerStatus)
                                    ? "Die Analyse ist fehlgeschlagen. Bitte starten Sie die Analyse erneut."
                                    : "RUNNING".equals(markerStatus)
                                            ? "Die Analyse läuft noch oder wurde unterbrochen. Bitte starten Sie die Analyse erneut."
                                            : "Die Entscheidungsvorlage ist nicht mehr verfügbar. Bitte führen Sie die Analyse erneut durch."));
            model = workspaceService.deserializeAnalysisResult(run);
            if (model == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "FAILED".equals(markerStatus)
                                ? "Die Analyse ist fehlgeschlagen. Bitte starten Sie die Analyse erneut."
                                : "RUNNING".equals(markerStatus)
                                        ? "Die Analyse läuft noch oder wurde unterbrochen. Bitte starten Sie die Analyse erneut."
                                        : "Die Entscheidungsvorlage ist nicht mehr verfügbar. Bitte führen Sie die Analyse erneut durch.");
            }
        }
        sanitizeStaleConfidence(model);
        analysisResultSanitizer.sanitize(model);
        model.put("caseName", dto.name() != null ? dto.name() : dto.workspaceCode());
        model.put("workspaceCode", dto.workspaceCode());
        model.put("caseType", caseCategory(dto));
        model.put("casePhase", CaseDetailController.phaseLabel(dto.phase()));
        model.put("processingStatus", ProcessingStatus.fromPhase(dto.phase()).getLabel());
        model.put("documentCount", dto.documents() != null ? dto.documents().size() : 0);
        // DOKUMENTE IM VORGANG: die tatsächlich am Fall hängenden Dokumente mit
        // ihrem ECHTEN Titel aus dem Dokument-Service. Der Link speichert
        // keinen Namen (attachDocument übernimmt nur die Notiz), daher war
        // die Box bisher leer — die Analyse-Belege dürfen hier NICHT einfließen.
        String actorId = user != null ? user.email() : "system";
        model.put("documentNames", dto.documents() != null
                ? dto.documents().stream().map(d -> caseDocumentTitle(d, actorId)).toList()
                : List.of());
        // Bilddokumente des Vorgangs als kleine Vorschau in der PDF: echter
        // Titel/Dateiname + Vorschaubild (≈2 cm, Seitenverhältnis erhalten).
        model.put("documentImages", imageDocumentPreviews(dto, actorId));
        // E-Mail-Referenzen der zugeordneten Bürgerkommunikation ("E-Mail: <Betreff>")
        // für den Abschnitt DOKUMENTE IM VORGANG der Entscheidungsvorlage.
        model.put("emailReferences", emailReferencesFor(id));
        model.put("generatedAt", DATE_FMT.format(java.time.LocalDateTime.now()));
        // Context for the professional document layout.
        model.put("caseDescription", dto.description() != null ? dto.description() : "");
        model.put("analysisQuestion", buildAnalysisQuestion(dto));
        enrichOwnerDetails(model, entity.getOwnerId());

        try {
            byte[] pdf = pdfExporter.export(model);
            recordExport(id);
            String fileName = "entscheidungsvorlage-" + (dto.name() != null
                    ? dto.name().replaceAll("[^a-zA-Z0-9äöüÄÖÜß-]", "_") : dto.workspaceCode()) + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/pdf"))
                    // "PDF öffnen" (inline=true): der Browser zeigt die PDF im
                    // eingebauten Viewer an — der reservierte PDF-Tab navigiert zu
                    // diesem Endpoint, NICHT zum Download (attachment), sonst
                    // erscheint ein Speichern-Dialog statt der Anzeige.
                    // Ohne inline=true bleibt es beim expliziten Download.
                    .header("Content-Disposition",
                            ("true".equals(inline) ? "inline" : "attachment") + "; filename=\"" + fileName + "\"")
                    .body(pdf);
        } catch (IOException e) {
            log.error("PDF export failed for case {}: {}", id, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Die PDF-Erstellung ist fehlgeschlagen.");
        }
    }

    /**
     * Popup-Blocker-sicherer Auto-Öffnen-Weg: Der Analyse-Start reserviert beim
     * Klick SOFORT einen Tab mit dieser Warteseite (Nutzer-Geste → kein
     * Popup-Blocker). Die Seite pollt den Analyse-Zustand und navigiert den
     * bereits offenen Tab nach Abschluss zur fertigen PDF — window.open nach
     * einer asynchronen Analyse würde der Browser blockieren.
     */
    @GetMapping("/cases/{id}/decision/pdf-wait")
    public String pdfWaitPage(@PathVariable String id,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              Model model) {
        caseAccessGuard.requireAccess(id, user);
        model.addAttribute("caseId", id);
        model.addAttribute("waitUrl", "/cases/" + id + "/decision/analysis-state");
        model.addAttribute("pdfUrl", "/cases/" + id + "/decision/export-pdf?inline=true");
        model.addAttribute("caseName", caseNameOf(id));
        model.addAttribute("pageTitle", "Entscheidungsvorlage wird erstellt");
        return "cases/pdf-wait";
    }

    /** JSON-Zustand für die Warteseite: Marker-Status + Zeitpunkt des letzten abgeschlossenen Laufs. */
    @GetMapping(value = "/cases/{id}/decision/analysis-state", produces = "application/json")
    public ResponseEntity<Map<String, Object>> analysisState(@PathVariable String id,
                                                             @AuthenticationPrincipal AuthenticatedUser user) {
        caseAccessGuard.requireAccess(id, user);
        Map<String, Object> state = new LinkedHashMap<>();
        try {
            WorkspaceEntity entity = workspaceService.findById(id).orElse(null);
            String marker = entity != null && entity.getPhaseData() != null
                    ? analysisMarkerStatus(workspaceService.toDto(entity)) : "";
            state.put("status", marker.isEmpty() ? "NONE" : marker);
            state.put("completedAt", workspaceService.latestCompletedAnalysisRun(id)
                    .map(run -> run.getCompletedAt() != null ? run.getCompletedAt().toEpochMilli() : null)
                    .orElse(null));
            // Wahrheitsgetreuer Pipeline-Fortschritt: die tatsächlich vom
            // Analyse-Job gemeldeten Verarbeitungsschritte (keine erfundenen
            // Prozentwerte) für die Warteseite. Die Knotenliste (gleiche
            // Ableitung wie die interaktive Pipeline-Visualisierung) erlaubt
            // der Warteseite die orange Knoten-Darstellung mit echten
            // Zuständen (done/active/pending) statt einer reinen Textliste.
            Job running = progressService.activeJob("analysis:" + id);
            if (running != null) {
                state.put("messages", running.messages);
                state.put("stageLabel", running.messages.isEmpty()
                        ? "Analyse wird vorbereitet …"
                        : running.messages.get(running.messages.size() - 1));
                state.put("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport
                        .pipelineNodes(running).stream()
                        .map(n -> java.util.Map.<String, Object>of(
                                "id", n.id(), "label", n.label(), "state", n.state(),
                                "info", n.info() != null ? n.info() : ""))
                        .toList());
            }
        } catch (Exception e) {
            log.warn("Analyse-Zustand für {} nicht lesbar: {}", id, e.getMessage());
            state.put("status", "NONE");
            state.put("completedAt", null);
        }
        return ResponseEntity.ok(state);
    }

    /**
     * Records a successful PDF export as a timeline event. Historical exports
     * were never persisted and cannot be reconstructed; this makes the event
     * available for all future exports.
     */
    private void recordExport(String caseId) {
        try {
            workspaceService.addTimelineEvent(caseId, java.time.LocalDate.now(),
                    "Entscheidungsvorlage exportiert",
                    "PDF-Export der Entscheidungsvorlage",
                    reasoning.workspace.model.TimelineEventType.PUBLICATION,
                    null, 1.0, false);
        } catch (Exception e) {
            log.warn("Could not record export timeline event for case {}: {}", caseId, e.getMessage());
        }
    }

    /** Runs the existing analysis pipeline in the background and stores the rendered result fragment. */
    private void runAnalysis(String jobId, String id, String actorEmail, String debug, int analysisVersion,
                             String asOfParam, String csrfToken) {
        try {
            WorkspaceEntity entity = workspaceService.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            WorkspaceDto dto = workspaceService.toDto(entity);
            Map<String, Object> m = new HashMap<>();
            m.put("caseId", id);
            m.put("caseName", dto.name() != null ? dto.name() : dto.workspaceCode());
            m.put("analysisQuestion", buildAnalysisQuestion(dto));

            // Explicit analysis reference date (Stichtag). ISO date or null → today.
            // Machine/persisted representation stays ISO (asOf); the German UI
            // presentation is derived at the UI boundary (asOfDisplay).
            LocalDate asOf = parseAsOf(asOfParam);
            LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
            m.put("asOf", effectiveAsOf.toString());
            m.put("asOfDisplay", DateTimeFormats.formatDate(effectiveAsOf));
            if (asOfParam != null && asOf == null) {
                log.warn("Invalid asOf parameter '{}' ignored — using today", asOfParam);
            }

            // Der LLM-Prompt erhält die vollständige Analyseanweisung; die
            // EVIDENZ-SUCHE arbeitet gegen das Fall-Thema (Name + Beschreibung),
            // nicht gegen die generische Instruktions-Hülle — sonst matcht
            // einzelne Wort-Überlappung (z. B. "Baugenehmigung" in einer
            // Gaststätten-Erlaubnis) fälschlich als Relevanz.
            String topicText = caseTopicText(dto);
            AiRequest request = new AiRequest(
                    buildAnalysisQuestion(dto), null, null,
                    new AiConversationContext(List.of(), actorEmail, null, null, jobId), 15,
                    RetrievalScope.HYBRID, UUID.fromString(id), asOf, topicText);
            m.put("analysisHighlightTerms", String.join(" ", highlightTerms(topicText)));
            m.put("analysisHighlightQuery",
                    java.net.URLEncoder.encode(String.join(" ", highlightTerms(topicText)),
                            java.nio.charset.StandardCharsets.UTF_8));
            AiResponse response = aiFacade.answer(request);
            ReasonedAnswer answer = response.answer();
            InferenceMetadata metadata = response.metadata();

            // ── Recommendation ──
            m.put("decisionAnswer", answer.answer());
            m.put("grounded", answer.grounded());
            m.put("model", metadata.model() != null ? metadata.model() : "Standard");
            m.put("strategy", strategyLabel(metadata.retrievalStrategy()));
            m.put("requestedAt", metadata.requestedAt() != null
                    ? DATE_FMT.format(metadata.requestedAt().atZone(ZoneId.systemDefault())) : "—");
            m.put("completedAt", metadata.completedAt() != null
                    ? DATE_FMT.format(metadata.completedAt().atZone(ZoneId.systemDefault())) : "—");

            // ── Confidence Profile (all 5 dimensions) ──
            ConfidenceProfile confidence = answer.confidence();
            m.put("confidenceScore", formatPercent(confidence.overallConfidence()));
            m.put("confidence", confidence);
            m.put("overallFillClass", fillClass(confidence.overallConfidence()));
            m.put("sourceFillClass", fillClass(confidence.sourceConfidence()));
            m.put("completenessFillClass", fillClass(confidence.completenessConfidence()));

            // ── Source dossier (needed for coverage fill class below) ──
            SourceDossier dossier = answer.sourceDossier();
            m.put("coverageFillClass", dossier != null ? fillClass(dossier.coverageScore()) : fillClass(0));

            // ── Source citations WITH documentId/page for evidence navigation ──
            List<Map<String, Object>> evidence = new ArrayList<>();
            if (answer.sourceCitations() != null) {
                for (SourceCitation sc : answer.sourceCitations()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("documentId", sc.documentId().toString());
                    item.put("chunkId", sc.chunkId().toString());
                    item.put("documentVersion", sc.documentVersion());
                    item.put("title", sc.title() != null ? sc.title() : sc.documentId().toString());
                    item.put("excerpt", sc.excerpt() != null ? sc.excerpt() : "Kein Auszug");
                    item.put("confidence", formatPercent(sc.confidenceScore()));
                    item.put("confidenceRaw", sc.confidenceScore());
                    item.put("tier", tierLabel(sc.tier() != null ? sc.tier().name() : null));
                    item.put("sourceType", sourceTypeLabel(sc.sourceType() != null ? sc.sourceType().name() : null));
                    item.put("pageNumber", sc.pageNumber());
                    item.put("startOffset", sc.startOffset());
                    item.put("endOffset", sc.endOffset());
                    item.put("publishedAt", sc.publishedAt());
                    item.put("validFrom", sc.validFrom());
                    item.put("validUntil", sc.validUntil());
                    item.put("supersededAt", sc.supersededAt());
                    item.put("fillClass", fillClass(sc.confidenceScore()));
                    evidence.add(item);
                }
            }
            m.put("evidenceItems", evidence);
            m.put("evidenceGroups", groupEvidence(evidence));

            // ── Authority references ──
            List<Map<String, Object>> authorities = new ArrayList<>();
            if (answer.authorityReferences() != null) {
                for (AuthorityReference ar : answer.authorityReferences()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", ar.entryTitle() != null ? ar.entryTitle() : ar.referenceId());
                    item.put("reference", ar.entryNumber() != null ? ar.entryNumber() : ar.referenceId());
                    item.put("excerpt", ar.excerpt() != null ? ar.excerpt() : "—");
                    item.put("domain", ar.domain() != null ? ar.domain() : "—");
                    item.put("basis", ar.basis() != null ? ar.basis() : "—");
                    item.put("relevance", formatPercent(ar.relevanceScore()));
                    item.put("relevanceRaw", ar.relevanceScore());
                    item.put("tier", tierLabel(ar.tier() != null ? ar.tier().name() : null));
                    item.put("fillClass", fillClass(ar.relevanceScore()));
                    authorities.add(item);
                }
            }
            m.put("authorities", authorities);

            // ── Findings WITH governing references for explainability ──
            FindingHierarchy hierarchy = answer.findingHierarchy();
            if (hierarchy != null) {
                List<Map<String, Object>> primaryViews = toFindingViews(hierarchy.primaryFindings(), evidence);
                List<Map<String, Object>> secondaryViews = toFindingViews(hierarchy.secondaryFindings(), evidence);
                List<Map<String, Object>> proceduralViews = toFindingViews(hierarchy.proceduralFindings(), evidence);
                List<Map<String, Object>> supportingViews = toFindingViews(hierarchy.supportingFindings(), evidence);
                m.put("primaryFindings", primaryViews);
                m.put("secondaryFindings", secondaryViews);
                m.put("proceduralFindings", proceduralViews);
                m.put("supportingFindings", supportingViews);
                m.put("findingRelationships", hierarchy.relationships());
                attachSupportedFindings(evidence, primaryViews, secondaryViews, proceduralViews, supportingViews);
            }

            // ── Source dossier (already resolved above) ──
            if (dossier != null) {
                m.put("coverageScore", formatPercent(dossier.coverageScore()));
                m.put("coverageScoreRaw", dossier.coverageScore());
                m.put("presentRoles", dossier.presentRoles());
                m.put("missingRoles", dossier.missingRoles());
                m.put("assessmentNote", dossier.completenessAssessment());
                // User-facing German description of what is missing; the raw
                // role enum names stay internal (debug panel only).
                m.put("missingDocs", dossier.missingRoles() != null
                        ? dossier.missingRoles().stream().map(DecisionWorkspaceController::sourceRoleLabel).toList()
                        : List.of());
            }

            // ── Coverage issues (user-facing, no internal role names) ──
            List<String> issues = new ArrayList<>();
            if (!answer.grounded()) {
                issues.add("Begrenzte Abdeckung: Einige Aspekte konnten nicht durch Quellen belegt werden.");
            }
            m.put("coverageIssues", issues);
            m.put("jobId", jobId);

            // ── Developer Diagnostics (debug mode) ──
            boolean isDebug = "true".equals(debug);
            m.put("debugMode", isDebug);
            if (isDebug && profiler != null) {
                m.put("debugProfile", profiler.getCurrentProfile());
                m.put("debugTotalMs", profiler.totalMs());
                m.put("debugMetadata", metadata);
                // Pass analytics health report
                if (analyticsService != null) {
                    m.put("pipelineHealth", analyticsService.buildReport());
                }
                // Pass diagnostic report
                if (pipelineDiagnostics != null) {
                    m.put("diagnosticReport", pipelineDiagnostics.diagnose());
                }
                // Run verification on this pipeline execution
                if (verifier != null) {
                    VerificationResult vr = verifier.verify(request, response);
                    m.put("verificationResult", vr);

                    // Governance: capture original snapshot
                    DecisionSnapshot originalSnapshot = null;
                    DecisionSnapshot finalSnapshot = null;
                    DecisionComparison comparison = null;

                    if (governance != null) {
                        originalSnapshot = governance.captureSnapshot(
                                buildAnalysisQuestion(dto), response, vr, null, "original");
                    }

                    // Attempt repair if verification found issues
                    if (repairEngine != null && hasIssues(vr)) {
                        RepairResult repair = repairEngine.repair(request, response, vr);
                        m.put("repairResult", repair);
                        if (repair.passed() && repair.repairedResponse() != null) {
                            AiResponse repaired = repair.repairedResponse();
                            m.put("decisionAnswer", repaired.answer().answer());
                            m.put("grounded", repaired.answer().grounded());
                            m.put("confidence", repaired.answer().confidence());
                            m.put("confidenceScore", formatPercent(repaired.answer().confidence().overallConfidence()));

                            // Governance: capture repaired snapshot and judge
                            if (governance != null) {
                                VerificationResult repairedVr = verifier.verify(request, repaired);
                                finalSnapshot = governance.captureSnapshot(
                                        buildAnalysisQuestion(dto), repaired, repairedVr, repair, "repair-cycle-1");
                                comparison = governance.evaluateAndSelect(originalSnapshot, finalSnapshot);
                                m.put("governanceComparison", comparison);
                            }
                        }
                    } else {
                        // No repair needed — final snapshot is the original
                        finalSnapshot = originalSnapshot;
                    }

                    // Build decision lineage
                    if (governance != null && originalSnapshot != null) {
                        if (finalSnapshot == null) finalSnapshot = originalSnapshot;
                        DecisionLineage lineage = governance.buildLineage(
                                buildAnalysisQuestion(dto), response, vr,
                                (RepairResult) m.get("repairResult"),
                                originalSnapshot, finalSnapshot, comparison);
                        m.put("decisionLineage", lineage);

                        // Record analytics
                        if (analyticsCollector != null) {
                            analyticsCollector.record(buildAnalysisQuestion(dto), vr,
                                    (RepairResult) m.get("repairResult"),
                                    originalSnapshot, finalSnapshot, comparison, lineage);
                        }
                    }
                }
            }

            // The live result must present the same honest state as the
            // restored run: no evidence → "nicht bewertbar" (never a score).
            sanitizeStaleConfidence(m);
            analysisResultSanitizer.sanitize(m);
            m.put("analysisComplete", true);
            // Persistieren VOR dem Abschluss-Render: Das Fallbriefing wird aus
            // dem gespeicherten Analyse-Lauf abgeleitet (deterministisch, kein
            // zweiter Pipeline-Lauf) und ist damit im selben Abschluss-Moment
            // verfügbar wie die Entscheidungsvorlage.
            workspaceService.recordAnalysisStatus(id, "COMPLETED",
                    Map.of("sourceCount", evidence.size()));
            workspaceService.completeAnalysisRun(id, analysisVersion,
                    dto.documents() != null ? dto.documents().size() : 0,
                    evidenceIdentities(dto, actorEmail), persistableModel(m));
            boolean briefingReady = false;
            String briefingError = null;
            try {
                if (caseBriefingService != null && caseBriefingService.canReuseAnalysis(id)) {
                    verwaltungsassistent.web.service.CaseBriefingService.Briefing briefing =
                            caseBriefingService.generateFromAnalysis(id, actorEmail);
                    caseBriefingService.store(id, briefing);
                    briefingReady = true;
                }
            } catch (Exception e) {
                log.warn("Fallbriefing nach Analyse von {} nicht erstellt: {}", id, e.getMessage());
                briefingError = "Das Fallbriefing konnte nicht automatisch erstellt werden – "
                        + "bitte über die Fallbriefing-Ansicht im Vorgang erneut versuchen.";
            }
            // autoOpenPdf NUR im Live-Render des Abschlusses: das automatische
            // Öffnen der fertigen PDF gehört ausschließlich in den Moment der
            // erfolgreichen Fertigstellung. Beim erneuten Laden einer
            // gespeicherten Analyse (jobId im persistierten Ergebnis) darf der
            // Tab nicht erneut aufgehen — deshalb wird das Flag nicht in das
            // persistierte Ergebnis übernommen, sondern nur für den Render
            // gesetzt. Gleiches gilt für das automatisch geöffnete
            // Fallbriefing (briefingReady).
            Map<String, Object> liveModel = new HashMap<>(m);
            liveModel.put("autoOpenPdf", true);
            liveModel.put("briefingAvailable", briefingReady);
            // CSRF für Formulare im roh gerenderten Abschluss-Fragment
            // (z. B. "Entscheidung dokumentieren") — MVC-Full-Render erzeugt
            // das Hidden-Feld automatisch, der Worker-Render nicht.
            liveModel.put("_csrfToken", csrfToken);
            // Entscheidungs-Zustand der Sachbearbeitung (Phase 2D.3): nach
            // einer ERNEUTEN Analyse zeigt der Abschluss-Render den aktuellen
            // Stand; im Erstlauf (noch nichts dokumentiert) bleibt der
            // „Noch offen"-Bereich mit der Dokumentations-Aktion sichtbar.
            liveModel.put("decisionInfo", decisionInfoOf(id));
            if (briefingError != null) {
                liveModel.put("briefingAutoFailed", briefingError);
            }
            String rendered = render("cases/decision-fragments", "decisionResult", liveModel);
            progressService.completeWithData(jobId, rendered, m, "Die Analyse wurde abgeschlossen.");
        } catch (Exception e) {
            log.error("AI analysis failed for case {}: {}", id, e.getMessage(), e);
            progressService.fail(jobId);
            workspaceService.recordAnalysisStatus(id, "FAILED", Map.of());
            workspaceService.failAnalysisRun(id, analysisVersion);
        } finally {
            progressService.unregisterActive("analysis:" + id, jobId);
        }
    }

    static LocalDate parseAsOf(String asOfParam) {
        if (asOfParam == null || asOfParam.isBlank()) return null;
        try {
            return LocalDate.parse(asOfParam.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Progress panel model for the startAnalysis response. */
    private void addProgressModel(Model model, Job job, String pollUrl, String title,
                                  String emptyMessage, String hint) {
        model.addAttribute("title", title);
        model.addAttribute("messages", job.messages);
        model.addAttribute("pollUrl", pollUrl);
        model.addAttribute("emptyMessage", emptyMessage);
        model.addAttribute("hint", hint);
        model.addAttribute("error", null);
        model.addAttribute("nodes",
                verwaltungsassistent.web.service.PipelineDiagramSupport.pipelineNodes(job));
    }

    /** Progress panel model map for the poll endpoint. */
    private Map<String, Object> progressModel(Job job, String pollUrl, String title,
                                              String emptyMessage, String hint) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("messages", job.messages);
        m.put("pollUrl", pollUrl);
        m.put("emptyMessage", emptyMessage);
        m.put("hint", hint);
        m.put("error", null);
        m.put("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport.pipelineNodes(job));
        return m;
    }

    private String caseNameOf(String id) {
        return workspaceService.findById(id)
                .map(workspaceService::toDto)
                .map(d -> d.name() != null ? d.name() : d.workspaceCode())
                .orElse(id);
    }

    /** Renders a Thymeleaf fragment to a string (used from background threads). */
    private String render(String template, String fragment, Map<String, Object> attributes) {
        attributes.putIfAbsent("supervisory", Boolean.FALSE);
        attributes.putIfAbsent("decisionInfo", null);
        attributes.putIfAbsent("caseClosed", Boolean.FALSE);
        Context context = new Context(Locale.GERMANY, attributes);
        return templateEngine.process(template, Set.of(fragment), context);
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(body);
    }

    // ── Finding views WITH citation linkage ──

    private List<Map<String, Object>> toFindingViews(List<FindingElement> findings,
                                                      List<Map<String, Object>> allEvidence) {
        if (findings == null) return List.of();
        return findings.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", f.label());
            m.put("description", f.description() != null ? f.description() : "");
            m.put("priority", formatPercent(f.priority()));
            m.put("priorityRaw", f.priority());
            m.put("role", f.role() != null ? findingRoleLabel(f.role()) : "—");
            m.put("fillClass", fillClass(f.priority()));
            // Cross-reference governingReferences with evidence items
            List<Map<String, Object>> linkedEvidence = new ArrayList<>();
            if (f.governingReferences() != null) {
                for (String ref : f.governingReferences()) {
                    for (Map<String, Object> ev : allEvidence) {
                        if (ref.equals(ev.get("title")) || ref.equals(ev.get("documentId"))) {
                            linkedEvidence.add(ev);
                            break;
                        }
                    }
                }
            }
            m.put("evidenceLinks", linkedEvidence);
            m.put("governingRefs", f.governingReferences() != null ? f.governingReferences() : List.of());
            m.put("relatedRefs", f.relatedReferences() != null ? f.relatedReferences() : List.of());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * Groups the ranked evidence items (citations) by document so the
     * Entscheidung panel shows ONE heading per document while every retrieved
     * passage stays individually visible. Pure PRESENTATION grouping: the
     * order of first appearance in the ranked citation list is preserved
     * (no re-ranking), and within a group the original citation order is
     * kept. Group metadata (tier/version/page) comes from the first item.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> groupEvidence(Object rawItems) {
        List<Map<String, Object>> groups = new ArrayList<>();
        if (!(rawItems instanceof List<?> items)) {
            return groups;
        }
        Map<String, Map<String, Object>> byDoc = new LinkedHashMap<>();
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> itemMap)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) itemMap);
            String docId = item.get("documentId") != null
                    ? String.valueOf(item.get("documentId")) : "";
            Map<String, Object> group = byDoc.get(docId);
            if (group == null) {
                group = new LinkedHashMap<>();
                group.put("documentId", docId);
                group.put("title", item.get("title"));
                group.put("tier", item.get("tier"));
                group.put("documentVersion", item.get("documentVersion"));
                group.put("pageNumber", item.get("pageNumber"));
                group.put("items", new ArrayList<Map<String, Object>>());
                byDoc.put(docId, group);
                groups.add(group);
            }
            ((List<Map<String, Object>>) group.get("items")).add(item);
        }
        // Phase 2C.11: je Dokument die EVIDENCE-Chunk-IDs bündeln, damit die
        // UI „Quelle öffnen" genau diese Passagen im Dokument-Viewer markiert
        // (nur tatsächlich genutzte Belege, nichts Erfundenes).
        for (Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groupItems = (List<Map<String, Object>>) group.get("items");
            String chunkIds = groupItems.stream()
                    .map(i -> i.get("chunkId"))
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(","));
            group.put("chunkIds", chunkIds);
        }
        return groups;
    }

    /**
     * Marks each evidence item with the findings it supports ("Trägt bei zu"),
     * so the evidence explorer can show which part of the decision the source
     * underpins. Uses the real governingReferences linkage — nothing invented.
     */
    private void attachSupportedFindings(List<Map<String, Object>> evidence,
                                         List<Map<String, Object>> primary, List<Map<String, Object>> secondary,
                                         List<Map<String, Object>> procedural, List<Map<String, Object>> supporting) {
        if (evidence == null || evidence.isEmpty()) return;
        List<Map<String, Object>> allFindings = new ArrayList<>();
        allFindings.addAll(primary);
        allFindings.addAll(secondary);
        allFindings.addAll(procedural);
        allFindings.addAll(supporting);
        for (Map<String, Object> ev : evidence) {
            String docId = String.valueOf(ev.get("documentId"));
            List<String> labels = new ArrayList<>();
            for (Map<String, Object> f : allFindings) {
                Object links = f.get("evidenceLinks");
                if (!(links instanceof List<?> linked)) continue;
                boolean linkedTo = linked.stream()
                        .filter(l -> l instanceof Map<?, ?> lm)
                        .anyMatch(lm -> docId.equals(String.valueOf(((Map<?, ?>) lm).get("documentId"))));
                if (linkedTo && f.get("label") != null) {
                    labels.add(String.valueOf(f.get("label")));
                }
            }
            // Always set (empty list when no findings link this source), so
            // the template never evaluates a missing map key.
            ev.put("supportedBy", labels);
        }
    }

    private String buildAnalysisQuestion(WorkspaceDto dto) {
        return String.format(
                "Analysiere den Fall \"%s\" (%s). Phase: %s. Dokumente: %d. Erstelle: 1) Faktenzusammenfassung, 2) anwendbare Vorschriften, 3) Handlungsschritte, 4) Risiken, 5) Empfehlung.",
                dto.name() != null ? dto.name() : dto.workspaceCode(),
                dto.description() != null ? dto.description() : "Keine Beschreibung",
                CaseDetailController.phaseLabel(dto.phase()),
                dto.documents() != null ? dto.documents().size() : 0);
    }

    // ── Label helpers ──

    private String formatPercent(double score) {
        return String.format(Locale.GERMANY, "%.0f%%", score * 100);
    }

    private static String fillClass(double score) {
        if (score >= 0.7) return "quality-indicator__fill--high";
        if (score >= 0.4) return "quality-indicator__fill--medium";
        return "quality-indicator__fill--low";
    }

    private static String strategyLabel(String strategy) {
        if (strategy == null) return "Hybride Suche";
        return switch (strategy) {
            case "HYBRID_RETRIEVAL" -> "Hybride Suche";
            case "RULE_ENGINE" -> "Regelbasiert";
            case "GRAPH_REASONING" -> "Hybride Suche";
            case "INDEX_INSPECTION" -> "Index-Prüfung";
            default -> strategy;
        };
    }

    private static String tierLabel(String tier) {
        if (tier == null) return "Unterstützend";
        return switch (tier) {
            case "PRIMARY" -> "Primär";
            case "SUPPORTING" -> "Unterstützend";
            case "BACKGROUND" -> "Hintergrund";
            case "CONTRADICTING" -> "Widersprechend";
            default -> tier;
        };
    }

    private static String sourceTypeLabel(String sourceType) {
        if (sourceType == null) return "Unbekannt";
        return switch (sourceType) {
            case "FACTUAL" -> "Faktisch";
            case "AUTHORITATIVE" -> "Maßgeblich";
            case "LEGAL" -> "Rechtlich";
            case "PROCEDURAL" -> "Verfahrenstechnisch";
            case "REFERENCE" -> "Referenz";
            default -> sourceType;
        };
    }

    private static boolean hasIssues(VerificationResult vr) {
        return !vr.unsupportedFindings().isEmpty()
                || !vr.unsupportedRecommendations().isEmpty()
                || vr.evidenceCount() < 2
                || vr.coverage() < 0.3
                || vr.intentMismatch() != null;
    }

    private static String findingRoleLabel(FindingRole role) {
        return switch (role) {
            case PRIMARY_FINDING -> "Kernfeststellung";
            case SUPPORTING_FINDING -> "Unterstützende Feststellung";
            case PROCEDURAL_FINDING -> "Verfahrensschritt";
            case CONTEXTUAL_FINDING -> "Kontext";
            case COLLATERAL_FINDING -> "Nebenaspekt";
        };
    }

    /** User-facing German label for a source role; internal enum names stay in the debug panel only. */
    private static String sourceRoleLabel(String role) {
        if (role == null) return "";
        return switch (role) {
            case "ESTABLISHING_DOCUMENT" -> "Gründungsunterlagen";
            case "TERMINATION_NOTICE" -> "Kündigungsunterlagen";
            case "FINANCIAL_RECORD" -> "Finanzunterlagen";
            case "WARNING" -> "Abmahnung";
            case "ESCALATION" -> "Eskalationsschreiben";
            case "ACKNOWLEDGMENT" -> "Bestätigungen";
            case "ADMISSION" -> "Anerkenntnisse";
            case "DAMAGES_SUPPORT" -> "Schadensbelege";
            case "CHRONOLOGY" -> "Chronologie";
            case "CORRESPONDENCE" -> "Schriftverkehr";
            default -> "Weitere Unterlagen";
        };
    }
}
