package verwaltungsassistent.web.controller;

import reasoning.ai.application.DecisionRouter;
import reasoning.ai.application.DecisionRouter.RoutingResult;
import reasoning.ai.application.DomainGate;
import reasoning.ai.model.Domain;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.search.api.SearchFacade;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.*;
import reasoning.workspace.application.WorkspaceService;
import reasoning.workspace.api.WorkspaceEntity;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.analysis.persistence.MailboxEntity;
import verwaltungsassistent.web.analysis.persistence.MailboxRepository;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URLEncoder;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * E-Mail processing workflow for the municipal daily business. A citizen
 * e-mail is analyzed with the EXISTING capabilities (semantic intent via
 * DecisionRouter, hybrid knowledge search, case registry) and presented as
 * an ordered German task list. Nothing is sent anywhere; the employee
 * remains in control of every action.
 */
@Controller
public class EmailController {

    private static final Logger log = LoggerFactory.getLogger(EmailController.class);

    private final JobProgressService progressService;
    private final DecisionRouter decisionRouter;
    private final DomainGate domainGate;
    private final SearchFacade searchFacade;
    private final WorkspaceService workspaceService;
    private final JpaDocumentChunkRepository chunkRepository;
    private final TemplateEngine templateEngine;
    private final JpaEmailAnalysisRepository analysisRepository;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final MailboxRepository mailboxRepository;
    private final reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository;
    private final ObjectMapper objectMapper;
    private final verwaltungsassistent.web.planning.PriorityCalculationService priorityCalculationService;
    private final ExecutorService executor;
    private final reasoning.ai.api.AiFacade aiFacade;
    private final verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard;
    private final verwaltungsassistent.web.service.EmailCaseMatchingService emailCaseMatchingService;

    @Value("${app.version:dev}")
    private String appVersion;

    /** Server-side page size for the unprocessed e-mail queue. */
    public static final int QUEUE_PAGE_SIZE = 20;

    /** Minimaler Fusions-Score für E-Mail-Kandidaten (Rauschen-Filter; die lexikalische Abdeckung ist das eigentliche Gate). */
    private static final double EMAIL_MIN_SCORE = 0.10;

    public EmailController(JobProgressService progressService,
                           DecisionRouter decisionRouter,
                           DomainGate domainGate,
                           SearchFacade searchFacade,
                           WorkspaceService workspaceService,
                           JpaDocumentChunkRepository chunkRepository,
                           TemplateEngine templateEngine,
                           JpaEmailAnalysisRepository analysisRepository,
                           JpaIncomingEmailRepository incomingEmailRepository,
                           MailboxRepository mailboxRepository,
                           reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository,
                           ObjectMapper objectMapper,
                           verwaltungsassistent.web.planning.PriorityCalculationService priorityCalculationService,
                           reasoning.ai.api.AiFacade aiFacade,
                           verwaltungsassistent.web.security.CaseAccessGuard caseAccessGuard,
                           verwaltungsassistent.web.service.EmailCaseMatchingService emailCaseMatchingService) {
        this.progressService = progressService;
        this.decisionRouter = decisionRouter;
        this.domainGate = domainGate;
        this.searchFacade = searchFacade;
        this.workspaceService = workspaceService;
        this.chunkRepository = chunkRepository;
        this.templateEngine = templateEngine;
        this.analysisRepository = analysisRepository;
        this.incomingEmailRepository = incomingEmailRepository;
        this.mailboxRepository = mailboxRepository;
        this.userAccountRepository = userAccountRepository;
        this.objectMapper = objectMapper;
        this.priorityCalculationService = priorityCalculationService;
        this.aiFacade = aiFacade;
        this.caseAccessGuard = caseAccessGuard;
        this.emailCaseMatchingService = emailCaseMatchingService;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "email-worker");
            t.setDaemon(true);
            return t;
        });
    }


    /** Operative E-Mail-Bearbeitung nur für Mitarbeiterinnen — das
     *  Leitungs-Konto liest ausschließlich (Aufsicht statt Bearbeitung). */
    private void requireOperationalUser(AuthenticatedUser user) {
        if (verwaltungsassistent.web.security.CaseAccessGuard.isSupervisory(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Das Leitungs-Konto ist schreibgeschützt — E-Mails können nur lesend eingesehen werden.");
        }
    }

    // ── Mailbox-Abruf (Phase 2C.2) ──

    @Autowired(required = false)
    private verwaltungsassistent.web.mailbox.MailboxIngestionService mailboxIngestionService;

    /** Anhang-Dokumente je E-Mail fuer die Provenienz-/Detail-Sicht (Profil-beans). */
    @Autowired(required = false)
    private verwaltungsassistent.web.mailbox.MailboxAttachmentDocumentIngestionService
            mailboxAttachmentService;

    /**
     * "Postfach abrufen" (Demo, Phase 2C.2): holt neue Nachrichten über den
     * lokalen GreenMail-IMAP-Transport und lässt sie SOFORT automatisch
     * verarbeiten — persistieren, klassifizieren, Fall-/Thread-Matching,
     * deterministische Vor-Analyse (wie bei einem echten Mailbox-Eingang;
     * keine zusätzliche manuelle Analyse nötig, keine Zuordnung).
     * Nur im Demo-/Playwright-Profil verfügbar (Service ist profilgebunden).
     */
    @PostMapping("/emails/mailbox/fetch")
    public String fetchMailbox(@AuthenticationPrincipal AuthenticatedUser user,
                               org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        requireOperationalUser(user);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (mailboxIngestionService == null) {
            redirectAttributes.addFlashAttribute("mailboxImported", -1);
            return "redirect:/emails";
        }
        var result = mailboxIngestionService.fetchAndIngest();
        redirectAttributes.addFlashAttribute("mailboxImported", result.imported());
        redirectAttributes.addFlashAttribute("mailboxAnalysed", result.analysed());
        redirectAttributes.addFlashAttribute("mailboxFailed", result.failed());
        redirectAttributes.addFlashAttribute("mailboxAttachments", result.attachmentsImported());
        redirectAttributes.addFlashAttribute("mailboxNewCases", result.newCases());
        return "redirect:/emails";
    }

    // ── Page ──

    @GetMapping("/emails")
    public String emailPage(@RequestParam(value = "open", required = false) UUID openAnalysisId,
                            @RequestParam(value = "email", required = false) UUID emailId,
                            @RequestParam(value = "page", defaultValue = "1") int page,
                            @RequestParam(value = "q", required = false) String q,
                            @AuthenticationPrincipal AuthenticatedUser user,
                            Model model) {
        addChrome(model);
        model.addAttribute("demoEmails", DEMO_EMAILS);
        model.addAttribute("currentUserEmail", user != null ? user.email() : null);
        // The work queue: unprocessed e-mails the current user may work on,
        // server-side paginated (never a hidden CSS-limited list). Optional
        // Suche über Betreff/Absender/Empfänger/Bearbeiter/Text (fokussiert).
        String searchTerm = q != null ? q.trim() : "";
        if (searchTerm.length() > 120) {
            searchTerm = searchTerm.substring(0, 120);
        }
        QueuePage queue = loadQueue(user, Math.max(1, page), searchTerm);
        model.addAttribute("emailQueue", queue.items());
        model.addAttribute("queuePage", queue.page());
        model.addAttribute("queueTotal", queue.total());
        model.addAttribute("queueTotalPages", queue.totalPages());
        model.addAttribute("queuePageSize", QUEUE_PAGE_SIZE);
        model.addAttribute("queueQuery", searchTerm);
        model.addAttribute("mailboxLabels", mailboxLabels());
        // Zähler der bearbeiteten E-Mails für den Listen-Kopf ("N bearbeitete
        // E-Mails"): Admin = alle bearbeiteten, Mitarbeiter = die eigenen.
        model.addAttribute("processedCount", processedMailCount(user));
        // Kompakte Prioritäts-Anzeige (Phase 1 Arbeitsplanung): deterministische
        // Wartezeit-Dringlichkeit je unerledigter E-Mail. Die Sortierung der
        // Warteschlange folgt derselben Dringlichkeit (höchste zuerst,
        // Phase 2D.12) — die Priorität ist also sichtbar UND sortierend.
        model.addAttribute("emailPriorityBadges", emailPriorityBadges(queue.items()));
        // Echte Analyse-Stufe je Warteschlangen-Eintrag (Phase 2D.12): PRE =
        // nur deterministische Vor-Analyse, FULL = vollständige KI-Analyse
        // liegt vor. Die Warteschlange darf "Voranalysiert" nicht anzeigen,
        // wenn eine vollständige KI-Analyse abgeschlossen wurde.
        Map<UUID, String> analysisKinds = emailAnalysisKinds(queue.items());
        model.addAttribute("emailAnalysisKind", analysisKinds.isEmpty() ? Map.of() : analysisKinds);
        // Vorgang je Warteschlangen-Eintrag (workspace_id): statt des leeren
        // "Vorgang zugeordnet" zeigt die Liste, WELCHER Vorgang gemeint ist.
        Map<UUID, String> caseLabels = emailCaseLabels(queue.items());
        model.addAttribute("emailCaseLabels", caseLabels.isEmpty() ? Map.of() : caseLabels);
        // Vorbelegung für die Button-Beschriftung (wird bei geöffneter E-Mail
        // unten überschrieben): ohne geöffnete E-Mail gibt es keine volle
        // KI-Analyse im Kontext.
        model.addAttribute("currentEmailAnalysisFull", Boolean.FALSE);

        // ?email=<incomingEmailId> opens one incoming e-mail in the work area:
        // its text is restored, its context (An:, status, assignment) shown
        // and the analysis panel is available.
        if (emailId != null && user != null) {
            try {
                IncomingEmailEntity email = incomingEmailRepository.findById(emailId)
                        .filter(e -> canWorkOn(e, user))
                        .orElse(null);
                if (email != null) {
                    model.addAttribute("currentEmail", email);
                    model.addAttribute("preloadedEmailText", email.getText());
                    model.addAttribute("preloadedAnalysis",
                            email.getAnalysisId() != null && analysisRepository.existsById(email.getAnalysisId()));
                    // Laufende Analyse dieser E-Mail: der Button ist deaktiviert
                    // ("Analyse läuft …"), bis der Job terminiert ist.
                    model.addAttribute("emailAnalysisRunning",
                            progressService.activeJob("email:" + email.getId()) != null);
                    // Phase 2D.12: liegt eine VOLLSTÄNDIGE KI-Analyse vor
                    // (nicht nur die deterministische Vor-Analyse)?
                    boolean currentFullAi = false;
                    if (email.getAnalysisId() != null) {
                        var stored = analysisRepository.findById(email.getAnalysisId());
                        currentFullAi = stored.isPresent()
                                && "FULL".equals(analysisKindOf(stored.get()));
                    }
                    model.addAttribute("currentEmailAnalysisFull", currentFullAi);
                    WorkspaceEntity associated = associatedCaseOf(email);
                    if (associated != null) {
                        model.addAttribute("associatedCaseId", associated.getId());
                        model.addAttribute("associatedCaseName",
                                associated.getName() != null ? associated.getName() : associated.getWorkspaceCode());
                    }
                    if (email.getAnalysisId() != null) {
                        analysisRepository.findById(email.getAnalysisId()).ifPresent(entity -> {
                            try {
                                EmailOutcome draftOutcome = readOutcome(entity);
                                model.addAttribute("outcome", draftOutcome);
                                model.addAttribute("emailDraft", emailDraftOf(entity));
                                model.addAttribute("draftAllowed",
                                        verwaltungsassistent.web.service.EmailResponseDraftService
                                                .responseRelevant(draftOutcome));
                                model.addAttribute("analysisId", entity.getId().toString());
                                model.addAttribute("subjectQueryParam",
                                        URLEncoder.encode(entity.getSubject(), StandardCharsets.UTF_8));
                                model.addAttribute("similarQueryParam",
                                        URLEncoder.encode(entity.getSubject() + " " + entity.getQuestionText(), StandardCharsets.UTF_8));
                                model.addAttribute("similarCount",
                                        similarAnalysesFor(entity.getSubject() + " " + entity.getQuestionText(), user.email(), entity.getId()).size());
                                model.addAttribute("emailHighlightTerms",
                                        emailHighlightTerms(entity.getSubject(), entity.getQuestionText()));
                            } catch (Exception e) {
                                log.debug("Stored analysis of e-mail {} not readable", emailId);
                            }
                        });
                    }
                    model.addAttribute("signature", signatureFor(user.email()));
                }
            } catch (Exception e) {
                log.warn("Could not preload incoming e-mail {}: {}", emailId, e.getMessage());
            }
        }
        // ?open=<analysisId> pre-loads a stored analysis into the result panel
        // (case detail "Zur E-Mail-Analyse" navigation and the dashboard feed).
        // The e-mail text itself is restored into the text area and the left
        // demo list entry is highlighted when its content matches exactly —
        // selection is ID-based, never subject-based.
        if (openAnalysisId != null && user != null) {
            try {
                EmailAnalysisEntity entity = analysisRepository.findById(openAnalysisId)
                        .filter(e -> e.getUserEmail().equals(user.email()))
                        .orElse(null);
                if (entity != null) {
                    EmailOutcome outcome = readOutcome(entity);
                    model.addAttribute("outcome", outcome);
                    model.addAttribute("emailDraft", emailDraftOf(entity));
                    model.addAttribute("draftAllowed",
                            verwaltungsassistent.web.service.EmailResponseDraftService
                                    .responseRelevant(outcome));
                    model.addAttribute("subjectQueryParam",
                            URLEncoder.encode(entity.getSubject(), StandardCharsets.UTF_8));
                    model.addAttribute("similarQueryParam",
                            URLEncoder.encode(entity.getSubject() + " " + entity.getQuestionText(), StandardCharsets.UTF_8));
                    model.addAttribute("similarCount", similarAnalysesFor(entity.getSubject() + " " + entity.getQuestionText(), user.email(), entity.getId()).size());
                    model.addAttribute("emailHighlightTerms",
                            emailHighlightTerms(entity.getSubject(), entity.getQuestionText()));
                    model.addAttribute("analysisId", entity.getId().toString());
                    model.addAttribute("preloadedAnalysis", true);
                    model.addAttribute("preloadedEmailText", entity.getQuestionText());
                    model.addAttribute("preloadedDemoEmailId", matchingDemoEmail(entity));
                    WorkspaceEntity associated = associatedCaseOf(findEmailByAnalysisId(entity.getId()));
                    if (associated != null) {
                        model.addAttribute("associatedCaseId", associated.getId());
                        model.addAttribute("associatedCaseName",
                                associated.getName() != null ? associated.getName() : associated.getWorkspaceCode());
                    }
                }
            } catch (Exception e) {
                log.warn("Could not preload e-mail analysis {}: {}", openAnalysisId, e.getMessage());
            }
        }
        return "emails/index";
    }

    // ── Analysis start ──

    @PostMapping("/emails/analyze")
    public String analyze(@RequestParam("emailText") String emailText,
                          @RequestParam(value = "currentEmailId", required = false) UUID currentEmailId,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          org.springframework.security.web.csrf.CsrfToken csrfToken,
                          Model model) {
        requireOperationalUser(user);
        addChrome(model);
        String text = emailText != null ? emailText.trim() : "";
        if (text.isEmpty()) {
            model.addAttribute("analyzeError", "Bitte fügen Sie zuerst eine E-Mail ein oder wählen Sie ein Beispiel aus.");
            return "emails/fragments :: emailResult";
        }
        // Duplikat-Schutz: Solange für diese E-Mail eine Analyse läuft, wird
        // kein zweiter Job gestartet — der Aufrufer erhält den laufenden
        // Fortschritt. Freie Texte ohne E-Mail-Kontext bleiben unbegrenzt
        // (bewusste Entscheidung: dort ist der Nutzer der einzige Auslöser).
        String jobKey = currentEmailId != null ? "email:" + currentEmailId : null;
        Job running = jobKey != null ? progressService.activeJob(jobKey) : null;
        Job job = running != null ? running : progressService.create(JobProgressService.Kind.ASSISTANT, subjectOf(text));
        if (running == null) {
            String actorEmail = user != null ? user.email() : null;
            boolean isAdmin = user != null && user.roles() != null && user.roles().contains("ADMIN");
            // Ownership-Modell: Der Start der Analyse ÜBERNIMMT die E-Mail
            // verbindlich (falls noch niemand zugeordnet ist) — "E-Mail-
            // Bearbeitung übernehmen" ist damit sofort überflüssig, die
            // Kontextleiste zeigt "In Bearbeitung durch Sie" bereits während
            // der Analyse (nicht erst nach deren Abschluss).
            if (currentEmailId != null && actorEmail != null) {
                try {
                    incomingEmailRepository.findById(currentEmailId)
                            .filter(email -> email.effectiveAssignee() == null)
                            .ifPresent(email -> {
                                email.setAssignedTo(actorEmail);
                                email.setAssignedAt(Instant.now());
                                email.setStatus(IncomingEmailEntity.Status.IN_PROGRESS);
                                incomingEmailRepository.save(email);
                            });
                } catch (Exception e) {
                    log.warn("Could not claim incoming e-mail {} at analysis start: {}", currentEmailId, e.getMessage());
                }
            }
            // The rendered fragment contains a native POST form ("Neuen Vorgang
            // anlegen"). The raw TemplateEngine render (worker thread) has no
            // RequestDataValueProcessor, so the CSRF token is captured here on
            // the request thread and passed through explicitly. The employee
            // signature is likewise captured on the request thread — the worker
            // must never guess it.
            String csrf = csrfToken != null ? csrfToken.getToken() : null;
            EmployeeSignature signature = actorEmail != null ? signatureFor(actorEmail) : null;
            if (jobKey != null) {
                progressService.registerActive(jobKey, job.jobId);
            }
            executor.submit(() -> process(job.jobId, text, actorEmail, isAdmin, csrf, currentEmailId, signature,
                    jobKey));
        }

        model.addAttribute("title", "E-Mail wird analysiert");
        model.addAttribute("messages", job.messages);
        model.addAttribute("pollUrl", "/emails/analyze/progress/" + job.jobId);
        model.addAttribute("emptyMessage", "Die E-Mail wird entgegengenommen …");
        model.addAttribute("hint", "Die Bearbeitung kann einen Moment dauern. Das System erkennt das Anliegen, "
                + "durchsucht die Wissensbasis und gleicht bestehende Fälle ab.");
        model.addAttribute("error", null);
        model.addAttribute("nodes",
                verwaltungsassistent.web.service.PipelineDiagramSupport.emailNodes(job));
        return "fragments/progress :: progressPanel";
    }

    /** Poll endpoint: progress panel while running, the rendered workflow result when done. */
    @GetMapping("/emails/analyze/progress/{jobId}")
    public ResponseEntity<String> emailProgress(@PathVariable String jobId) {
        Job job = progressService.get(jobId);
        if (job == null) {
            Map<String, Object> m = new HashMap<>();
            m.put("analyzeError", "Die Analyse ist nicht mehr verfügbar. Bitte starten Sie die Analyse erneut.");
            return html(render("emails/fragments", "emailResult", m));
        }
        if ("ERROR".equals(job.state)) {
            Map<String, Object> m = new HashMap<>();
            m.put("analyzeError", "Die E-Mail konnte nicht vollständig analysiert werden.");
            return html(render("emails/fragments", "emailResult", m));
        }
        if ("DONE".equals(job.state)) {
            return html((String) job.outcome);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("title", "E-Mail wird analysiert");
        m.put("messages", job.messages);
        m.put("pollUrl", "/emails/analyze/progress/" + job.jobId);
        m.put("emptyMessage", "Die E-Mail wird entgegengenommen …");
        m.put("hint", "Die Bearbeitung kann einen Moment dauern. Das System erkennt das Anliegen, "
                + "durchsucht die Wissensbasis und gleicht bestehende Fälle ab.");
        m.put("error", null);
        m.put("nodes", verwaltungsassistent.web.service.PipelineDiagramSupport.emailNodes(job));
        return html(render("fragments/progress", "progressPanel", m));
    }

    // ── Background analysis ──

    private void process(String jobId, String text, String actorEmail, boolean isAdmin, String csrfToken,
                         UUID currentEmailId, EmployeeSignature signature, String jobKey) {
        try {
            long t0 = System.currentTimeMillis();
            recordStage(jobId, "Die E-Mail wird gelesen …");
            // Einmalig laden: Absender-Adresse für die Personen-Identität und
            // der persistente Status für die OOB-Aktualisierung der E-Mail-
            // Kontextleiste (die Zuweisung selbst erfolgt bereits beim Start
            // der Analyse, nicht erst bei deren Abschluss).
            IncomingEmailEntity currentEmail = currentEmailId != null
                    ? incomingEmailRepository.findById(currentEmailId).orElse(null)
                    : null;
            String subject = subjectOf(text);
            progressService.recordStageData(jobId, "email-lesen", java.util.Map.of(
                    "subject", subject != null ? subject : "",
                    "ms", System.currentTimeMillis() - t0));

            long t1 = System.currentTimeMillis();
            recordStage(jobId, "Anliegen und Fachbereich werden erkannt …");
            RoutingResult routing = decisionRouter.route(subject + "\n" + text);
            String domain = routing.intent() != null && routing.intent().domain() != null
                    ? routing.intent().domain().name()
                    : domainGate.classifyDomain(subject + " " + text).name();
            String intentType = routing.intent() != null ? routing.intent().intentType() : null;
            progressService.recordStageData(jobId, "email-routing", java.util.Map.of(
                    "domain", domain,
                    "intentType", intentType != null ? intentType : "",
                    "strategy", routing.strategy().name(),
                    "ms", System.currentTimeMillis() - t1));

            long t2 = System.currentTimeMillis();
            recordStage(jobId, "Die Wissensbasis wird durchsucht …");
            List<DocRef> relevantDocs = searchRelevant(subject + " " + firstWords(text, 80), domain, actorEmail);
            progressService.recordStageData(jobId, "email-retrieval", java.util.Map.of(
                    "sources", relevantDocs.size(),
                    "mode", "HYBRID",
                    "ms", System.currentTimeMillis() - t2));

            long t3 = System.currentTimeMillis();
            recordStage(jobId, "Bestehende Fälle werden abgeglichen …");
            // Thread-Header (In-Reply-To/References) als zusätzliches
            // Zuordnungs-Signal (Phase 2C.1) — objektiv, kein LLM.
            Set<String> threadReferences = currentEmail != null
                    ? new HashSet<>(currentEmail.threadReferences()) : Set.of();
            List<CaseRef> matches = matchCases(text,
                    currentEmail != null ? currentEmail.getSenderEmail() : null,
                    actorEmail, isAdmin, threadReferences);
            progressService.recordStageData(jobId, "email-faelle", java.util.Map.of(
                    "cases", matches.size(),
                    "ms", System.currentTimeMillis() - t3));

            // ── Beantwortung: KI-Pipeline (Retrieval → Belege → LLM →
            // Grounding/Verifikation). Die Pipeline-Stufen laufen mit der
            // jobId als requestId, so dass auch sie in diesem Fortschritts-Job
            // landen; das Ergebnis (Antwort + Belege + Konfidenz) wird
            // beantwortet in die E-Mail-Analyse übernommen. Scheitert die
            // Beantwortung (z. B. keine Quellen), bleibt die Triage vollständig.
            recordStage(jobId, "Die Antwort wird auf Basis der Wissensbasis vorbereitet …");
            AnswerResult answerResult = answerFromKnowledgeBase(subject, text, jobId, actorEmail);

            long t4 = System.currentTimeMillis();
            recordStage(jobId, "Die nächsten Schritte werden zusammengestellt …");
            // Vorschläge bereinigen: ein Fall, dem diese E-Mail bereits
            // zugeordnet ist, ist KEIN „Möglicher passender Fall" — die
            // Zuordnung zeigt der Abschnitt „Zugeordneter Fall". Vergleich
            // über die stabile Vorgangs-ID, nicht über Titel/Namen.
            String assignedWorkspaceId = currentEmail != null
                    && currentEmail.getWorkspaceId() != null
                    ? currentEmail.getWorkspaceId().toString() : null;
            List<CaseRef> displayMatches = matches;
            if (assignedWorkspaceId != null) {
                displayMatches = matches.stream()
                        .filter(m -> m.id() == null || !assignedWorkspaceId.equals(m.id()))
                        .toList();
            }
            EmailOutcome outcome = buildOutcome(subject, text, domain, intentType, relevantDocs,
                    displayMatches, assignedWorkspaceId, answerResult);
            progressService.recordStageData(jobId, "email-schritte", java.util.Map.of(
                    "steps", outcome.steps().size(),
                    "ms", System.currentTimeMillis() - t4));

            UUID analysisId = persistAnalysis(text, subject, detectTopic(subject + "\n" + text), outcome, actorEmail);

            // Manuell eingestellte Nachricht (Phase 2C.1, Szenario B): Wurde
            // kein Katalog-/Eingangs-E-Mail-Objekt analysiert (freier Text),
            // entsteht ein erstklassiges MANUAL-E-Mail-Objekt — die Nachricht
            // ist damit genauso fall- und threadfähig wie eine zugestellte
            // E-Mail (Zuordnung, Kommunikationsverlauf, Antwortentwurf).
            // Idempotent: eine bereits existierende E-Mail mit dieser Analyse
            // wird nie dupliziert (erneute Analyse desselben Texts).
            if (currentEmailId == null && actorEmail != null && analysisId != null) {
                try {
                    boolean alreadyLinked = incomingEmailRepository.findAll().stream()
                            .anyMatch(e -> analysisId.equals(e.getAnalysisId()));
                    if (!alreadyLinked) {
                        IncomingEmailEntity manual = new IncomingEmailEntity(
                                UUID.randomUUID(), subject,
                                senderNameFrom(text) != null ? senderNameFrom(text) : "—",
                                null, text, Instant.now(),
                                IncomingEmailEntity.AddressedTo.GENERAL, null);
                        manual.setSourceType(IncomingEmailEntity.SourceType.MANUAL);
                        manual.setStatus(IncomingEmailEntity.Status.IN_PROGRESS);
                        manual.setAssignedTo(actorEmail);
                        manual.setAssignedAt(Instant.now());
                        manual.setAnalysisId(analysisId);
                        incomingEmailRepository.save(manual);
                        currentEmail = manual;
                    }
                } catch (Exception e) {
                    log.warn("Manuelles E-Mail-Objekt nicht angelegt: {}", e.getMessage());
                }
            }

            // Processing lifecycle: analyzing an incoming e-mail moves it to
            // "In Bearbeitung" and assigns it to the current employee. Die
            // Zuweisung erfolgt bereits beim Start der Analyse (analyze()-
            // Endpunkt) — dieser Block fängt den Fall ab, dass die E-Mail erst
            // hier überhaupt zugeordnet werden kann, und hält den Status
            // konsistent (idempotent: bereits zugewiesen → keine Änderung).
            String emailStatusLabel = null;
            String emailStatusVariant = null;
            if (currentEmailId != null && actorEmail != null) {
                try {
                    IncomingEmailEntity updated = incomingEmailRepository.findById(currentEmailId)
                            .map(email -> {
                                email.setStatus(IncomingEmailEntity.Status.IN_PROGRESS);
                                email.setAnalysisId(analysisId);
                                if (email.getAssignedTo() == null) {
                                    email.setAssignedTo(actorEmail);
                                    email.setAssignedAt(Instant.now());
                                }
                                return incomingEmailRepository.save(email);
                            }).orElse(null);
                    if (updated != null) {
                        currentEmail = updated;
                        emailStatusLabel = statusLabel(updated.getStatus());
                        emailStatusVariant = statusVariant(updated.getStatus());
                    }
                } catch (Exception e) {
                    log.warn("Could not update incoming e-mail {} status: {}", currentEmailId, e.getMessage());
                }
            }

            Map<String, Object> m = new HashMap<>();
            m.put("outcome", outcome);
            m.put("emailDraft", null);
            m.put("draftAllowed",
                    verwaltungsassistent.web.service.EmailResponseDraftService
                            .responseRelevant(outcome));
            m.put("analysisId", analysisId != null ? analysisId.toString() : null);
            m.put("emailText", text);
            m.put("subjectQueryParam", URLEncoder.encode(subject, StandardCharsets.UTF_8));
            m.put("similarQueryParam", URLEncoder.encode(subject + " " + text, StandardCharsets.UTF_8));
            // Die eigene Analyse gehört nicht zu den "ähnlichen früheren
            // Analysen" — sonst öffnet der Dialog die aktuelle Seite.
            m.put("similarCount", analysisId != null
                    ? similarAnalysesFor(subject + " " + text, actorEmail, analysisId).size() : 0);
            m.put("emailHighlightTerms", emailHighlightTerms(subject, text));
            m.put("_csrfToken", csrfToken);
            m.put("signature", signature);
            // Bereits zugeordneter Fall: dann ist "Fall öffnen" der primäre Weg
            // (kein "Vorgang aus E-Mail anlegen" als Normalaktion).
            if (analysisId != null) {
                WorkspaceEntity associated = associatedCaseOf(findEmailByAnalysisId(analysisId));
                if (associated != null) {
                    m.put("associatedCaseId", associated.getId());
                    m.put("associatedCaseName",
                            associated.getName() != null ? associated.getName() : associated.getWorkspaceCode());
                }
            }
            // The e-mail context bar updates via htmx OOB swap right after
            // the analysis — the persisted status is reflected immediately.
            m.put("emailStatusLabel", emailStatusLabel);
            m.put("emailStatusVariant", emailStatusVariant);
            // OOB swap der Aktionsleiste (Bearbeitung übernehmen → In
            // Bearbeitung durch Sie): die E-Mail ist durch die Analyse
            // verbindlich dem aktuellen Mitarbeiter zugeordnet.
            if (currentEmail != null) {
                m.put("emailEntity", currentEmail);
                m.put("currentUserEmail", actorEmail);
            }
            // Abschluss-Render über den gemeinsamen Render-Helper (statt roher
            // templateEngine.process): der Worker-Thread hat keinen MVC-Kontext
            // — der Helper liefert die putIfAbsent-Defaults (supervisory=false,
            // emailDraft, draftAllowed), die emailActionArea/emailDraftArea im
            // Fragment erwarten (Phase 2D.10: fehlendes supervisory führte zum
            // Analysefehler "konnte nicht vollständig analysiert werden").
            String rendered = render("emails/fragments", "emailAnalysis", m);
            progressService.complete(jobId, rendered, "Die Analyse wurde abgeschlossen.");
        } catch (Exception e) {
            log.error("E-Mail analysis failed: {}", e.getMessage(), e);
            progressService.fail(jobId);
        } finally {
            if (jobKey != null) {
                progressService.unregisterActive(jobKey, jobId);
            }
        }
    }

    /**
     * Persists the completed analysis so it survives navigation; returns its id
     * (or null). Re-analyzing the SAME e-mail text updates the existing record
     * instead of inserting a duplicate — the dashboard feed and the analysis
     * history must not show the same e-mail twice just because it was run again.
     */
    UUID persistAnalysis(String text, String subject, String topic,
                         EmailOutcome outcome, String actorEmail) {
        if (actorEmail == null) return null;
        try {
            EmailAnalysisEntity entity = analysisRepository
                    .findByUserEmailAndQuestionTextOrderByCreatedAtDesc(actorEmail, text)
                    .stream().findFirst().orElseGet(() -> new EmailAnalysisEntity(
                            UUID.randomUUID(), actorEmail, text, subject, topic, appVersion,
                            null, null));
            entity.setQuestionText(text);
            entity.setSubject(subject);
            entity.setTopic(topic);
            entity.setAppVersion(appVersion);
            entity.setResultJson(objectMapper.writeValueAsString(outcome));
            entity.setCreatedAt(Instant.now());
            analysisRepository.save(entity);
            return entity.getId();
        } catch (Exception e) {
            log.warn("Could not persist e-mail analysis for {}: {}", actorEmail, e.getMessage());
            return null;
        }
    }

    /**
     * The demo e-mail whose body EXACTLY matches the stored analysis text, or
     * null. Only a content-identical match highlights the left list entry —
     * same subjects never cause the wrong e-mail to be selected.
     */
    private String matchingDemoEmail(EmailAnalysisEntity entity) {
        if (entity.getQuestionText() == null) return null;
        String text = entity.getQuestionText().trim();
        for (DemoEmail demo : DEMO_EMAILS) {
            if (demo.body() != null && demo.body().trim().equals(text)) {
                return demo.id();
            }
        }
        return null;
    }

    /**
     * Re-renders a stored analysis result (history / "Ähnliche Anfragen").
     * Only the owning user may load it.
     */
    @GetMapping("/emails/analyses/{id}")
    public ResponseEntity<String> storedAnalysis(@PathVariable UUID id,
                                                 @AuthenticationPrincipal AuthenticatedUser user,
                                                 org.springframework.security.web.csrf.CsrfToken csrfToken) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        EmailAnalysisEntity entity = analysisRepository.findById(id).orElse(null);
        if (entity == null || !entity.getUserEmail().equals(user.email())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analyse nicht gefunden");
        }
        EmailOutcome outcome = readOutcome(entity);
        Map<String, Object> m = new HashMap<>();
        m.put("outcome", outcome);
        m.put("emailDraft", emailDraftOf(entity));
        m.put("draftAllowed",
                verwaltungsassistent.web.service.EmailResponseDraftService
                        .responseRelevant(outcome));
        m.put("analysisId", entity.getId().toString());
        m.put("subjectQueryParam", URLEncoder.encode(entity.getSubject(), StandardCharsets.UTF_8));
        m.put("similarQueryParam",
                URLEncoder.encode(entity.getSubject() + " " + entity.getQuestionText(), StandardCharsets.UTF_8));
        m.put("similarCount", similarAnalysesFor(entity.getSubject() + " " + entity.getQuestionText(), user.email(), entity.getId()).size());
        m.put("emailHighlightTerms", emailHighlightTerms(entity.getSubject(), entity.getQuestionText()));
        WorkspaceEntity associated = associatedCaseOf(findEmailByAnalysisId(entity.getId()));
        if (associated != null) {
            m.put("associatedCaseId", associated.getId());
            m.put("associatedCaseName",
                    associated.getName() != null ? associated.getName() : associated.getWorkspaceCode());
        }
        m.put("_csrfToken", csrfToken != null ? csrfToken.getToken() : null);
        return html(render("emails/fragments", "emailAnalysis", m));
    }



    // ── Antwortentwurf für eingehende E-Mails (Phase 2C.8) ─────────────

    /** Anzeige-Stand eines Antwortentwurfs (persistiert auf der Analyse).
     *  reviewedAt/reviewedBy = LOKALER Prüfvermerk der Mitarbeiterin — kein
     *  Versand, keine Status-/Falländerung. */
    public record EmailDraft(String text, String generatedAt, boolean edited,
                             String reviewedAt, String reviewedBy) {
        public boolean reviewed() {
            return reviewedAt != null;
        }
    }

    private static final java.time.format.DateTimeFormatter DRAFT_DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final java.time.format.DateTimeFormatter DRAFT_TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private EmailDraft emailDraftOf(EmailAnalysisEntity entity) {
        if (entity == null || entity.getDraftText() == null) {
            return null;
        }
        return new EmailDraft(entity.getDraftText(),
                entity.getDraftGeneratedAt() != null
                        ? DRAFT_TS_FMT.format(entity.getDraftGeneratedAt().atZone(ZoneId.systemDefault())) : null,
                entity.isDraftEdited(),
                entity.getDraftReviewedAt() != null
                        ? DRAFT_TS_FMT.format(entity.getDraftReviewedAt().atZone(ZoneId.systemDefault())) : null,
                entity.getDraftReviewedBy());
    }

    /** Interne Timeline-Vermerke zum lokalen Entwurf (ohne Entwurfsinhalt).
     *  Nur wenn die E-Mail einem Vorgang zugeordnet ist; Fehler sind harmlos. */
    private void recordDraftTimeline(EmailAnalysisEntity entity, String title, String note) {
        try {
            incomingEmailRepository.findAll().stream()
                    .filter(e -> entity.getId().equals(e.getAnalysisId()))
                    .map(IncomingEmailEntity::getWorkspaceId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .ifPresent(wsId -> workspaceService.addTimelineEvent(
                            wsId.toString(), java.time.LocalDate.now(), title, note,
                            reasoning.workspace.model.TimelineEventType.CHANGE,
                            null, 1.0, false));
        } catch (Exception e) {
            log.debug("Timeline-Vermerk zum Antwortentwurf nicht möglich: {}", e.getMessage());
        }
    }

    /** Signatur-Block für den Entwurf — ausschließlich aus dem Mitarbeiter-Profil. */
    private String signatureTextFor(String email) {
        EmployeeSignature s = signatureFor(email);
        if (s == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (s.fullName() != null && !s.fullName().isBlank()) {
            lines.add(s.fullName());
        }
        if (s.position() != null && !s.position().isBlank()) {
            lines.add(s.position());
        }
        String org = (s.department() != null ? s.department() : "")
                + (s.department() != null && !s.department().isBlank()
                        && s.office() != null && !s.office().isBlank() ? " · " : "")
                + (s.office() != null ? s.office() : "");
        if (!org.isBlank()) {
            lines.add(org);
        }
        if (s.phone() != null && !s.phone().isBlank()) {
            lines.add("Telefon: " + s.phone());
        }
        if (s.email() != null && !s.email().isBlank()) {
            lines.add(s.email());
        }
        if (s.room() != null && !s.room().isBlank()) {
            lines.add("Raum " + s.room());
        }
        return String.join("\n", lines);
    }

    /** Bearbeitungs-Zugriff auf den Entwurf: Analyses-Eigentümerin oder
     *  Mitarbeiterin, die die zugehörige E-Mail bearbeiten darf (Leitungs-Konto
     *  wurde bereits über requireOperationalUser abgewiesen). */
    private void requireDraftAccess(EmailAnalysisEntity entity, AuthenticatedUser user) {
        if (user == null || user.email() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (entity.getUserEmail() != null
                && entity.getUserEmail().equalsIgnoreCase(user.email())) {
            return;
        }
        boolean workable = incomingEmailRepository.findAll().stream()
                .filter(e -> entity.getId().equals(e.getAnalysisId()))
                .anyMatch(e -> e.getAssignedTo() == null
                        || e.getAssignedTo().equals(user.email())
                        || (e.getAddressedTo() == IncomingEmailEntity.AddressedTo.EMPLOYEE
                                && user.email().equals(e.getAddressedToEmail())));
        if (!workable) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Keine Berechtigung für den Antwortentwurf dieser E-Mail.");
        }
    }

    /** Fragment-HTML des Entwurfs-Bereichs (nach Erzeugen/Speichern). */
    private ResponseEntity<String> emailDraftAreaHtml(EmailAnalysisEntity entity, EmailOutcome outcome) {
        Map<String, Object> m = new HashMap<>();
        m.put("analysisId", entity.getId().toString());
        m.put("emailDraft", emailDraftOf(entity));
        m.put("draftAllowed",
                verwaltungsassistent.web.service.EmailResponseDraftService
                        .responseRelevant(outcome));
        return html(render("emails/fragments", "emailDraftArea", m));
    }

    /** Antwortentwurf erzeugen (deterministisch aus dem Analyse-Ergebnis;
     *  kein Versand, kein zweiter Pipeline-Lauf). Manuell bearbeitete Entwürfe
     *  werden nur mit force=true ersetzt (die UI bestätigt das explizit). */
    @PostMapping("/emails/{analysisId}/draft/generate")
    public ResponseEntity<String> generateEmailDraft(@PathVariable UUID analysisId,
                                                     @RequestParam(value = "force", required = false) String force,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        EmailAnalysisEntity entity = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Analyse nicht gefunden."));
        requireDraftAccess(entity, user);
        EmailOutcome outcome = readOutcome(entity);
        if (!verwaltungsassistent.web.service.EmailResponseDraftService.responseRelevant(outcome)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Für diese E-Mail ist kein Antwortentwurf vorgesehen.");
        }
        if (entity.isDraftEdited() && !"true".equals(force)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Der Entwurf wurde manuell bearbeitet — nur mit expliziter Bestätigung neu erzeugen.");
        }
        java.time.Instant now = java.time.Instant.now();
        String full = verwaltungsassistent.web.service.EmailResponseDraftService.composeDraft(
                outcome, DRAFT_DATE_FMT.format(now.atZone(ZoneId.systemDefault())),
                signatureTextFor(user != null ? user.email() : null));
        entity.setDraftText(full);
        entity.setDraftGeneratedAt(now);
        entity.setDraftEdited(false);
        entity.setDraftReviewedAt(null);
        entity.setDraftReviewedBy(null);
        analysisRepository.save(entity);
        recordDraftTimeline(entity, "Antwortentwurf erstellt",
                "Lokaler Antwortentwurf im E-Mail-Arbeitsbereich erstellt (kein Versand).");
        return emailDraftAreaHtml(entity, outcome);
    }

    /** Manuelle Bearbeitung des Entwurfs speichern (Arbeitsstand, kein Versand). */
    @PostMapping("/emails/{analysisId}/draft/save")
    public ResponseEntity<String> saveEmailDraft(@PathVariable UUID analysisId,
                                                 @RequestParam("text") String text,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        EmailAnalysisEntity entity = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Analyse nicht gefunden."));
        requireDraftAccess(entity, user);
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Entwurf darf nicht leer sein.");
        }
        if (text.length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Entwurf ist zu lang (max. 20.000 Zeichen).");
        }
        boolean firstEdit = !entity.isDraftEdited();
        entity.setDraftText(text.trim());
        if (entity.getDraftGeneratedAt() == null) {
            entity.setDraftGeneratedAt(java.time.Instant.now());
        }
        entity.setDraftEdited(true);
        // Inhalt nach dem Prüfen geändert → der lokale Prüfvermerk gilt nicht
        // mehr für den neuen Stand (erneutes Prüfen nötig, kein Versand-Bezug).
        if (entity.getDraftReviewedAt() != null) {
            entity.setDraftReviewedAt(null);
            entity.setDraftReviewedBy(null);
        }
        analysisRepository.save(entity);
        if (firstEdit) {
            recordDraftTimeline(entity, "Antwortentwurf bearbeitet",
                    "Lokaler Antwortentwurf wurde von der Mitarbeiterin manuell bearbeitet (kein Versand).");
        }
        return emailDraftAreaHtml(entity, readOutcome(entity));
    }

    /** Lokaler Prüfvermerk: Mitarbeiterin bestätigt, den Entwurf geprüft zu
     *  haben. Rein intern — kein Versand, kein Status-/Fallwechsel. */
    @PostMapping("/emails/{analysisId}/draft/review")
    public ResponseEntity<String> reviewEmailDraft(@PathVariable UUID analysisId,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        EmailAnalysisEntity entity = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Analyse nicht gefunden."));
        requireDraftAccess(entity, user);
        if (entity.getDraftText() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Es existiert noch kein Antwortentwurf zum Prüfen.");
        }
        if (entity.getDraftReviewedAt() == null) {
            entity.setDraftReviewedAt(java.time.Instant.now());
            entity.setDraftReviewedBy(user != null ? user.email() : null);
            analysisRepository.save(entity);
            recordDraftTimeline(entity, "Antwortentwurf geprüft",
                    "Lokaler Antwortentwurf von der Mitarbeiterin geprüft (kein Versand).");
        }
        return emailDraftAreaHtml(entity, readOutcome(entity));
    }

    // ── Email work queue: assignment, status, history, detail ──


    /** "Bearbeitung übernehmen": persist the assignment to the current employee. */
    @PostMapping("/emails/{id}/assign")
    public String assignEmail(@PathVariable UUID id,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        IncomingEmailEntity email = incomingEmailRepository.findById(id)
                // Only genuinely unassigned e-mails (general mailbox, nobody
                // claimed) can be taken over — directly-addressed e-mails are
                // assigned by recipient.
                .filter(e -> e.effectiveAssignee() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "E-Mail nicht gefunden"));
        email.setAssignedTo(user.email());
        email.setAssignedAt(Instant.now());
        incomingEmailRepository.save(email);
        return "redirect:/emails?email=" + id;
    }

    /**
     * "Diesem Fall zuordnen": associates the analysed e-mail with an existing
     * Fall/Vorgang. Several e-mails can belong to the same case (E-Mail 1..n →
     * one Fall); the association is stored on the e-mail ({@code workspaceId}).
     * A timeline event makes the attachment visible in the case. Idempotent.
     */
    @PostMapping("/emails/{analysisId}/assign-case")
    public String assignEmailToCase(@PathVariable UUID analysisId,
                                    @RequestParam("caseId") UUID caseId,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EmailAnalysisEntity analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "E-Mail-Analyse nicht gefunden"));
        WorkspaceEntity caseEntity = workspaceService.findById(caseId.toString())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fall nicht gefunden"));
        // Zugriff auf den Ziel-Vorgang (Admin / Eigentümer / allgemeiner Pool) —
        // eine E-Mail darf nie an einen Vorgang gehängt werden, den die
        // Mitarbeiterin nicht sehen darf.
        caseAccessGuard.requireAccess(caseId.toString(), user);

        IncomingEmailEntity email = findEmailByAnalysisId(analysisId);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "E-Mail nicht gefunden");
        }
        email.setWorkspaceId(caseId);
        incomingEmailRepository.save(email);
        try {
            workspaceService.addTimelineEvent(
                    caseId.toString(), java.time.LocalDate.now(),
                    "E-Mail eingegangen",
                    "Zugeordnete E-Mail: \"" + (analysis.getSubject() != null ? analysis.getSubject() : "") + "\"",
                    reasoning.workspace.model.TimelineEventType.COMMUNICATION,
                    null, 1.0, false);
        } catch (Exception e) {
            log.warn("Timeline-Ereignis für zugeordnete E-Mail {} nicht erfasst: {}", analysisId, e.getMessage());
        }
        log.info("E-Mail '{}' dem Fall '{}' ({}) zugeordnet",
                analysis.getSubject(), caseEntity.getName(), caseId);
        return "redirect:/emails?email=" + email.getId();
    }

    /**
     * "Zuordnung aufheben": entfernt die Fall-Zuordnung einer E-Mail
     * ({@code workspaceId} = null). Die E-Mail bleibt unverändert; sie kann
     * anschließend einem anderen Fall zugeordnet oder neu analysiert werden.
     * Idempotent.
     */
    @PostMapping("/emails/{analysisId}/unassign-case")
    public String unassignEmailFromCase(@PathVariable UUID analysisId,
                                        @RequestParam(value = "redirect", required = false) String redirect,
                                        @RequestParam(value = "caseId", required = false) UUID caseId,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "E-Mail-Analyse nicht gefunden"));
        IncomingEmailEntity email = findEmailByAnalysisId(analysisId);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "E-Mail nicht gefunden");
        }
        email.setWorkspaceId(null);
        incomingEmailRepository.save(email);
        log.info("Fall-Zuordnung der E-Mail '{}' aufgehoben", email.getSubject());
        // Aus der Fall-Ansicht (E-Mail-Tab) zurück zum Fall; sonst zur E-Mail.
        if ("case".equals(redirect) && caseId != null) {
            return "redirect:/cases/" + caseId;
        }
        return "redirect:/emails?email=" + email.getId();
    }

    /** The incoming e-mail whose stored analysis matches the given analysis id. */
    private IncomingEmailEntity findEmailByAnalysisId(UUID analysisId) {
        return incomingEmailRepository.findAll().stream()
                .filter(e -> analysisId.equals(e.getAnalysisId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Der Fall/Vorgang, dem diese E-Mail bereits zugeordnet ist
     * ({@code workspaceId}). Bei bestehender Zuordnung ist der primäre Weg
     * "Fall öffnen" — "Vorgang aus E-Mail anlegen" / "Diesem Fall zuordnen"
     * sind dann keine normalen Aktionen mehr.
     */
    private WorkspaceEntity associatedCaseOf(IncomingEmailEntity email) {
        if (email == null || email.getWorkspaceId() == null) {
            return null;
        }
        try {
            return workspaceService.findById(email.getWorkspaceId().toString()).orElse(null);
        } catch (Exception e) {
            log.debug("Zugeordneter Fall von E-Mail {} nicht lesbar: {}", email.getId(), e.getMessage());
            return null;
        }
    }

    /** "Als erledigt markieren": completes the processing lifecycle. */
    @PostMapping("/emails/{id}/complete")
    public String completeEmail(@PathVariable UUID id,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        requireOperationalUser(user);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        IncomingEmailEntity email = incomingEmailRepository.findById(id)
                .filter(e -> e.getAssignedTo() == null || e.getAssignedTo().equals(user.email())
                        || (user.roles() != null && user.roles().contains("ADMIN")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "E-Mail nicht gefunden"));
        if (email.getAssignedTo() == null) {
            email.setAssignedTo(user.email());
            email.setAssignedAt(Instant.now());
        }
        email.setStatus(IncomingEmailEntity.Status.COMPLETED);
        email.setCompletedAt(Instant.now());
        email.setCompletedBy(user.email());
        incomingEmailRepository.save(email);
        return "redirect:/emails?email=" + id;
    }

    /** "Meine bearbeiteten E-Mails": history of the current employee (admin: all processed). */
    @GetMapping("/emails/mine")
    public String myEmails(@RequestParam(value = "q", required = false) String q,
                           @AuthenticationPrincipal AuthenticatedUser user, Model model) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean isAdmin = user.roles() != null && user.roles().contains("ADMIN");
        String searchTerm = q != null ? q.trim() : "";
        if (searchTerm.length() > 120) {
            searchTerm = searchTerm.substring(0, 120);
        }
        // Suche über die bearbeiteten E-Mails (Betreff, Absender, Absender-E-Mail,
        // Text) — ohne Suchbegriff die vollständige Liste.
        List<IncomingEmailEntity> processed;
        if (searchTerm.isEmpty()) {
            processed = isAdmin
                    ? incomingEmailRepository.findByStatusNotOrderByReceivedAtDesc(IncomingEmailEntity.Status.NEW)
                    : incomingEmailRepository.findByAssignedToOrderByReceivedAtDesc(user.email()).stream()
                            .filter(e -> e.getStatus() != IncomingEmailEntity.Status.NEW)
                            .toList();
        } else {
            processed = isAdmin
                    ? incomingEmailRepository.searchProcessed(IncomingEmailEntity.Status.NEW, searchTerm)
                    : incomingEmailRepository.searchProcessedForAssignee(
                            IncomingEmailEntity.Status.NEW, user.email(), searchTerm);
        }
        model.addAttribute("processedEmails", processed);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("processedCount", processed.size());
        model.addAttribute("processedQuery", searchTerm);
        // Phase 2D.13: gleiche Badge-Sprache wie die Warteschlange — Prioritäts-
        // und Analyse-Stufe stammen aus DENSELBEN Klassifikations-Helfern
        // (emailPriorityBadges/emailAnalysisKinds), kein zweites Mapping.
        Map<UUID, EmailPriorityBadge> priorityBadges = emailPriorityBadges(processed);
        model.addAttribute("emailPriorityBadges",
                priorityBadges.isEmpty() ? Map.of() : priorityBadges);
        Map<UUID, String> kinds = emailAnalysisKinds(processed);
        model.addAttribute("emailAnalysisKind", kinds.isEmpty() ? Map.of() : kinds);
        model.addAttribute("pageTitle", "Meine bearbeiteten E-Mails");
        model.addAttribute("activeSection", "emails");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("E-Mails", "/emails"),
                new HomeController.Breadcrumb("Meine bearbeiteten E-Mails", "/emails/mine")));
        return "emails/mine";
    }

    /** Full detail of one incoming e-mail (text, metadata, analysis, generated answer). */
    @GetMapping("/emails/{id}")
    public String emailDetail(@PathVariable UUID id,
                              @AuthenticationPrincipal AuthenticatedUser user,
                              Model model) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean isAdmin = user.roles() != null && user.roles().contains("ADMIN");
        IncomingEmailEntity email = incomingEmailRepository.findById(id)
                .filter(e -> isAdmin
                        || (e.getAssignedTo() != null && e.getAssignedTo().equals(user.email()))
                        || (e.getAddressedTo() == AddressedTo.EMPLOYEE
                                && user.email().equals(e.getAddressedToEmail()))
                        || (e.getAddressedTo() == AddressedTo.GENERAL
                                && (e.getAssignedTo() == null || e.getAssignedTo().equals(user.email()))))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "E-Mail nicht gefunden"));

        model.addAttribute("email", email);
        model.addAttribute("mailboxLabels", mailboxLabels());
        model.addAttribute("signature", signatureFor(email.getAssignedTo() != null
                ? email.getAssignedTo() : user.email()));
        WorkspaceEntity associated = associatedCaseOf(email);
        if (associated != null) {
            model.addAttribute("associatedCaseId", associated.getId());
            model.addAttribute("associatedCaseName",
                    associated.getName() != null ? associated.getName() : associated.getWorkspaceCode());
            // Kommunikationsverlauf: alle E-Mails des Falls chronologisch
            // (älteste zuerst) — der Mitarbeiterin wird der Verlauf der
            // Bürgerkommunikation auf der E-Mail-Detailseite gezeigt.
            try {
                List<IncomingEmailEntity> thread = new ArrayList<>(
                        incomingEmailRepository.findByWorkspaceIdOrderByReceivedAtDesc(
                                UUID.fromString(associated.getId())));
                java.util.Collections.reverse(thread);
                model.addAttribute("caseThread", thread);
            } catch (Exception e) {
                log.debug("Kommunikationsverlauf von Fall {} nicht lesbar: {}",
                        associated.getId(), e.getMessage());
            }
        }
        if (email.getAnalysisId() != null) {
            analysisRepository.findById(email.getAnalysisId()).ifPresent(entity -> {
                try {
                    EmailOutcome detailOutcome = readOutcome(entity);
                    model.addAttribute("outcome", detailOutcome);
                    model.addAttribute("emailDraft", emailDraftOf(entity));
                    model.addAttribute("draftAllowed",
                            verwaltungsassistent.web.service.EmailResponseDraftService
                                    .responseRelevant(detailOutcome));
                    model.addAttribute("analysisId", entity.getId().toString());
                    model.addAttribute("subjectQueryParam",
                            URLEncoder.encode(entity.getSubject(), StandardCharsets.UTF_8));
                    model.addAttribute("similarQueryParam",
                            URLEncoder.encode(entity.getSubject() + " " + entity.getQuestionText(), StandardCharsets.UTF_8));
                    model.addAttribute("similarCount", similarAnalysesFor(entity.getSubject() + " " + entity.getQuestionText(), user.email(), entity.getId()).size());
                    model.addAttribute("emailHighlightTerms",
                            emailHighlightTerms(entity.getSubject(), entity.getQuestionText()));
                } catch (Exception e) {
                    log.debug("Stored analysis of e-mail {} not readable", id);
                }
            });
        }
        // Phase 2D.12: Status-Label "KI-Analyse abgeschlossen" nur bei
        // tatsächlich vollständiger KI-Analyse (nicht bei reiner Vor-Analyse).
        boolean detailFullAi = false;
        if (email.getAnalysisId() != null) {
            var storedAnalysis = analysisRepository.findById(email.getAnalysisId());
            detailFullAi = storedAnalysis.isPresent()
                    && "FULL".equals(analysisKindOf(storedAnalysis.get()));
        }
        model.addAttribute("emailDetailAnalysisFull", detailFullAi);
        model.addAttribute("pageTitle", "E-Mail: " + email.getSubject());
        model.addAttribute("activeSection", "emails");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("E-Mails", "/emails"),
                new HomeController.Breadcrumb(email.getSubject(), "/emails/" + id)));
        if (email.getMessageId() != null && !email.getMessageId().isBlank()
                && mailboxAttachmentService != null) {
            model.addAttribute("emailAttachments", mailboxAttachmentService.attachmentsOf(email.getMessageId()));
        }
        return "emails/detail";
    }

    /** One server-side page of the unprocessed e-mail queue with its totals. */
    public record QueuePage(List<IncomingEmailEntity> items, int page, long total, int totalPages) {}

    /** Anzahl der bearbeiteten E-Mails (Admin: alle; Mitarbeiter: eigene). */
    private long processedMailCount(AuthenticatedUser user) {
        if (user == null) {
            return 0;
        }
        try {
            if (user.roles() != null && user.roles().contains("ADMIN")) {
                return incomingEmailRepository.countByStatusNot(IncomingEmailEntity.Status.NEW);
            }
            return incomingEmailRepository
                    .countByAssignedToAndStatusNot(user.email(), IncomingEmailEntity.Status.NEW);
        } catch (Exception e) {
            log.warn("Zähler der bearbeiteten E-Mails nicht lesbar: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Unprocessed e-mails the current user may work on, paged: admin sees the
     * complete queue across all users and general mailboxes; other users only
     * their own (directly addressed, claimed, or unassigned general) e-mails.
     *
     * <p>Reihenfolge: höchste Wartezeit-Dringlichkeit zuerst, bei Gleichstand
     * neueste zuerst (Phase 2D.12). Die Reihung erfolgt in Java über
     * {@code PriorityCalculationService.emailPriorityClass} (DERSELBE
     * Klassifikator wie für die Badges) auf der VOLLSTÄNDIGEN Kandidatenliste
     * und erst danach die Paginierung — konsistent über Seiten hinweg. Bewusst
     * keine ORDER-BY-CASE-Sortierung mit benannten Parametern im JPQL
     * (Hibernate-Runtime-Fehler "No parameter named ':mittelCutoff' …",
     * Phase 2D.13-Folge).</p>
     */
    QueuePage loadQueue(AuthenticatedUser user, int page, String q) {
        if (user == null) {
            return new QueuePage(List.of(), 1, 0, 0);
        }
        try {
            boolean admin = user.roles() != null && user.roles().contains("ADMIN");
            String searchTerm = q == null ? null : q.trim();
            List<IncomingEmailEntity> candidates;
            if (admin) {
                candidates = searchTerm == null || searchTerm.isEmpty()
                        ? incomingEmailRepository.findByStatusOrderByReceivedAtDesc(
                                IncomingEmailEntity.Status.NEW)
                        : incomingEmailRepository.searchNewByStatus(
                                IncomingEmailEntity.Status.NEW, searchTerm);
            } else {
                candidates = searchTerm == null || searchTerm.isEmpty()
                        ? incomingEmailRepository.findVisibleQueueList(
                                user.email(), IncomingEmailEntity.Status.NEW, AddressedTo.GENERAL)
                        : incomingEmailRepository.searchVisibleQueueList(
                                user.email(), IncomingEmailEntity.Status.NEW, AddressedTo.GENERAL,
                                searchTerm);
            }
            if (candidates == null) {
                candidates = List.of();
            }
            // Sortierung benötigt eine veränderbare Liste (Repository-Ergebnisse
            // können unveränderlich sein, z. B. List.of in Tests).
            candidates = new ArrayList<>(candidates);
            candidates.sort(Comparator
                    .comparingInt((IncomingEmailEntity e) -> priorityCalculationService
                            .emailPriorityClass(waitingDaysOf(e)).ordinal())
                    .thenComparing(IncomingEmailEntity::getReceivedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())));
            int total = candidates.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) total / QUEUE_PAGE_SIZE));
            int safePage = Math.max(1, Math.min(page, totalPages));
            int from = Math.min((safePage - 1) * QUEUE_PAGE_SIZE, total);
            int to = Math.min(from + QUEUE_PAGE_SIZE, total);
            return new QueuePage(candidates.subList(from, to), safePage, total, totalPages);
        } catch (Exception e) {
            log.warn("Could not load e-mail queue: {}", e.getMessage());
            return new QueuePage(List.of(), 1, 0, 0);
        }
    }

    /** Ganze Kalendertage seit Eingang (lokale Zeit) — dieselbe Basis wie die Badges. */
    private static long waitingDaysOf(IncomingEmailEntity e) {
        return e.getReceivedAt() != null
                ? Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(
                        e.getReceivedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        java.time.LocalDate.now()))
                : 0;
    }

    /**
     * Kompakte Prioritäts-Badges der unerledigten Warteschlange: Klasse und
     * Variante aus der Wartezeit (deterministisch, kein LLM). Reine Anzeige —
     * die Sortierung bleibt der Eingangszeit.
     */
    private Map<UUID, EmailPriorityBadge> emailPriorityBadges(List<IncomingEmailEntity> emails) {
        Map<UUID, EmailPriorityBadge> badges = new LinkedHashMap<>();
        for (IncomingEmailEntity e : emails) {
            long days = 0;
            if (e.getReceivedAt() != null) {
                days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(
                        e.getReceivedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        java.time.LocalDate.now()));
            }
            var priorityClass = priorityCalculationService.emailPriorityClass(days);
            badges.put(e.getId(), new EmailPriorityBadge(
                    // Warteschlangen-Anzeige: oberste Stufe heißt für die
                    // Mitarbeiterin "Kritisch" (nur Anzeige — das Modell und
                    // alle Vergleiche nutzen unverändert PriorityClass).
                    priorityClass == verwaltungsassistent.web.planning.PriorityCalculationService.PriorityClass.SEHR_HOCH
                            ? "Kritisch" : priorityClass.label(),
                    priorityCalculationService.variant(priorityClass),
                    days));
        }
        return badges;
    }

    /** Anzeige-Badge einer unerledigten E-Mail (Klasse, Farb-Variante, Wartezeit). */
    public record EmailPriorityBadge(String label, String variant, long waitingDays) {}

    /**
     * Analyse-Stufe je E-Mail der Warteschlange (Phase 2D.12): PRE = nur die
     * deterministische Vor-Analyse liegt vor; FULL = die vollständige
     * KI-Analyse (Antwort + Belege) wurde abgeschlossen. Beide nutzen
     * dieselbe gespeicherte Analyse (analysis_id) — die Stufe wird aus dem
     * Ergebnisinhalt abgeleitet, nie geraten.
     */
    private Map<UUID, String> emailAnalysisKinds(List<IncomingEmailEntity> emails) {
        Map<UUID, String> kinds = new HashMap<>();
        try {
            List<UUID> analysisIds = emails.stream()
                    .map(IncomingEmailEntity::getAnalysisId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (analysisIds.isEmpty()) {
                return kinds;
            }
            Map<UUID, EmailAnalysisEntity> byId = new HashMap<>();
            for (EmailAnalysisEntity a : analysisRepository.findAllById(analysisIds)) {
                byId.put(a.getId(), a);
            }
            for (IncomingEmailEntity e : emails) {
                EmailAnalysisEntity a = e.getAnalysisId() != null ? byId.get(e.getAnalysisId()) : null;
                if (a != null) {
                    kinds.put(e.getId(), analysisKindOf(a));
                }
            }
        } catch (Exception e) {
            log.warn("Analyse-Stufen der Warteschlange nicht lesbar: {}", e.getMessage());
        }
        return kinds;
    }

    /** FULL, sobald das Ergebnis eine KI-Antwort enthält — sonst PRE. */
    private String analysisKindOf(EmailAnalysisEntity analysis) {
        try {
            EmailOutcome outcome = readOutcome(analysis);
            return outcome.aiAnswer() != null && !outcome.aiAnswer().isBlank() ? "FULL" : "PRE";
        } catch (Exception e) {
            return "PRE";
        }
    }

    /**
     * Anzeige-Referenz des zugeordneten Vorgangs je Warteschlangen-Eintrag
     * (workspace_id → "Name (WS-XXXX)") — die Liste zeigt damit, WELCHER
     * Vorgang hinter "Vorgang zugeordnet" steckt.
     */
    private Map<UUID, String> emailCaseLabels(List<IncomingEmailEntity> emails) {
        Map<UUID, String> labels = new HashMap<>();
        try {
            for (IncomingEmailEntity e : emails) {
                if (e.getWorkspaceId() == null || labels.containsKey(e.getWorkspaceId())) {
                    continue;
                }
                workspaceService.findById(e.getWorkspaceId().toString()).ifPresent(ws -> labels.put(
                        e.getWorkspaceId(),
                        (ws.getName() != null && !ws.getName().isBlank() ? ws.getName() : ws.getWorkspaceCode())
                                + " (" + ws.getWorkspaceCode() + ")"));
            }
        } catch (Exception e) {
            log.warn("Vorgangs-Labels der Warteschlange nicht lesbar: {}", e.getMessage());
        }
        return labels;
    }

    /** Display names of the general demo mailboxes (address → label). */
    Map<String, String> mailboxLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        try {
            for (MailboxEntity m : mailboxRepository.findAllByOrderByAddress()) {
                labels.put(m.getAddress(), m.getDisplayName());
            }
        } catch (Exception e) {
            log.debug("Mailbox labels unavailable: {}", e.getMessage());
        }
        return labels;
    }

    private boolean canWorkOn(IncomingEmailEntity email, AuthenticatedUser user) {
        if (user.roles() != null && user.roles().contains("ADMIN")) {
            return true;
        }
        if (email.getAddressedTo() == AddressedTo.EMPLOYEE) {
            return user.email().equals(email.getAddressedToEmail());
        }
        return email.getAssignedTo() == null || email.getAssignedTo().equals(user.email());
    }

    /** Employee signature for official correspondence — from the account profile, never guessed. */
    private EmployeeSignature signatureFor(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            var account = userAccountRepository.findByEmail(email.trim().toLowerCase());
            if (account.isPresent()) {
                var u = account.get();
                String fullName = (u.getFirstName() != null || u.getLastName() != null)
                        ? ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                                + (u.getLastName() != null ? u.getLastName() : "")).trim()
                        : u.getDisplayName();
                return new EmployeeSignature(
                        fullName,
                        u.getSalutation(),
                        u.getDepartment(),
                        u.getOffice(),
                        u.getPosition(),
                        u.getPhone(),
                        u.getRoom(),
                        u.getEmail());
            }
        } catch (Exception e) {
            log.debug("Signature lookup failed for {}: {}", email, e.getMessage());
        }
        return null;
    }

    /** Signature block for official correspondence (all values from the employee profile). */
    public record EmployeeSignature(String fullName, String salutation, String department,
                                    String office, String position, String phone, String room,
                                    String email) {}

    /**
     * Similar EARLIER analyses of the same user for the "Ähnliche Anfragen"
     * dialog: ranked by the number of SPECIFIC overlapping terms (generic
     * administrative words do not count), newest first within a tie. The
     * currently displayed analysis ({@code excludeId}) is never part of its
     * own result set — a similar result must open a DIFFERENT stored
     * analysis, never the page it came from.
     */
    @GetMapping("/emails/analyses/similar")
    public String similarAnalyses(@RequestParam("q") String question,
                                  @RequestParam(value = "exclude", required = false) UUID excludeId,
                                  @AuthenticationPrincipal AuthenticatedUser user,
                                  Model model) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        model.addAttribute("similarAnalyses",
                similarAnalysesFor(question, user.email(), excludeId));
        return "emails/fragments :: similarAnalyses";
    }

    private List<SimilarAnalysis> similarAnalysesFor(String question, String actorEmail) {
        return similarAnalysesFor(question, actorEmail, null);
    }

    private List<SimilarAnalysis> similarAnalysesFor(String question, String actorEmail, UUID excludeId) {
        if (actorEmail == null) return List.of();
        List<SimilarAnalysis> result = new ArrayList<>();
        try {
            Set<String> questionTokens = tokens(question);
            for (EmailAnalysisEntity a : analysisRepository.findByUserEmailOrderByCreatedAtDesc(actorEmail)) {
                if (excludeId != null && excludeId.equals(a.getId())) {
                    continue;
                }
                Set<String> otherTokens = tokens((a.getSubject() != null ? a.getSubject() : "")
                        + " " + a.getQuestionText());
                Set<String> overlap = new HashSet<>(questionTokens);
                overlap.retainAll(otherTokens);
                long specific = overlap.stream().filter(t -> !GENERIC_TERMS.contains(t)).count();
                if (overlap.size() >= 2 && specific >= 1) {
                    result.add(new SimilarAnalysis(a.getId(), a.getSubject(), a.getCreatedAt(), specific));
                }
            }
            result.sort(Comparator.comparingLong(SimilarAnalysis::specific).reversed()
                    .thenComparing(SimilarAnalysis::createdAt, Comparator.reverseOrder()));
            return result.stream().limit(8).toList();
        } catch (Exception e) {
            log.warn("Similar analysis lookup failed for {}: {}", actorEmail, e.getMessage());
            return List.of();
        }
    }

    private EmailOutcome readOutcome(EmailAnalysisEntity entity) {
        try {
            return objectMapper.readValue(entity.getResultJson(), new TypeReference<EmailOutcome>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analyse nicht lesbar");
        }
    }

    /** A stored analysis entry of the "Ähnliche Anfragen" dialog. */
    public record SimilarAnalysis(UUID id, String subject, Instant createdAt, long specific) {}

    private void recordStage(String jobId, String message) {
        progressService.recordStage(jobId, message);
    }

    // ── Building blocks ──

    /**
     * Lässt die KI-Pipeline die Bürgerfrage direkt beantworten (Retrieval →
     * Belege → LLM → Grounding/Verifikation) und stellt die Belege bereit.
     * Die Pipeline-Stufen laufen unter der jobId, damit der Fortschritts-Dialog
     * sie anzeigt; das Ergebnis wird der E-Mail-Analyse beigefügt. Bei
     * Fehlschlag (keine Quellen, Fehler) bleibt die Triage vollständig nutzbar.
     */
    private AnswerResult answerFromKnowledgeBase(String subject, String text, String jobId, String actorEmail) {
        long start = System.currentTimeMillis();
        try {
            String question = (subject != null && !subject.isBlank() ? subject + "\n" : "") + text;
            var request = new reasoning.ai.model.AiRequest(
                    question, null, null,
                    new reasoning.ai.model.AiConversationContext(
                            List.of(), actorEmail, null, null, jobId), 8,
                    reasoning.ai.model.RetrievalScope.HYBRID, null, null);
            var response = aiFacade.answer(request);
            reasoning.ai.model.ReasonedAnswer answer = response.answer();
            long ms = System.currentTimeMillis() - start;

            List<AiEvidence> evidence = new ArrayList<>();
            if (answer.sourceCitations() != null) {
                for (reasoning.ai.model.SourceCitation sc : answer.sourceCitations()) {
                    evidence.add(new AiEvidence(
                            sc.title() != null ? sc.title() : "",
                            sc.excerpt() != null ? sc.excerpt() : "",
                            sc.pageNumber(),
                            sc.confidenceScore(),
                            sc.tier() != null ? sc.tier().name() : null,
                            sc.documentId() != null ? sc.documentId().toString() : null,
                            sc.chunkId() != null ? sc.chunkId().toString() : null));
                }
            }
            Integer confidence = answer.confidence() != null
                    ? (int) Math.round(answer.confidence().overallConfidence() * 100) : null;
            boolean grounded = answer.grounded();

            progressService.recordStageData(jobId, "email-belege", java.util.Map.of(
                    "evidence", evidence.size(),
                    "sources", evidence.size(),
                    "ms", ms));
            int supportedClaims = answer.findingHierarchy() != null
                    ? answer.findingHierarchy().primaryFindings().size() : 0;
            progressService.recordStageData(jobId, "email-feststellungen", java.util.Map.of(
                    "claims", supportedClaims,
                    "ms", ms));
            progressService.recordStageData(jobId, "email-antwort", java.util.Map.of(
                    "status", "EXECUTED",
                    "grounded", grounded,
                    "confidence", confidence != null ? confidence : 0,
                    "ms", ms));
            return new AnswerResult(answer.answer(), grounded, confidence, evidence, ms);
        } catch (Exception e) {
            log.warn("KI-Beantwortung der E-Mail fehlgeschlagen (Triage bleibt erhalten): {}", e.getMessage());
            progressService.recordStageData(jobId, "email-antwort", java.util.Map.of(
                    "status", "FEHLER", "ms", System.currentTimeMillis() - start));
            return new AnswerResult(null, false, null, List.of(), System.currentTimeMillis() - start);
        }
    }

    EmailOutcome buildOutcome(String subject, String text, String domain,
                              String intentType, List<DocRef> docs, List<CaseRef> matches,
                              AnswerResult answerResult) {
        return buildOutcome(subject, text, domain, intentType, docs, matches, null, answerResult);
    }

    /**
     * Wie die 7-Parameter-Variante, zusätzlich mit dem bereits zugeordneten
     * Vorgang: {@code matches} enthält nur echte Vorschläge (der eigene
     * Vorgang der E-Mail ist bereits herausgefiltert — er wird im Abschnitt
     * „Zugeordneter Fall" gezeigt, nicht als „Möglicher passender Fall").
     * {@code assignedWorkspaceId} hält den Primär-Bezug für die nächsten
     * Schritte, wenn es keinen anderen Treffer gibt.
     */
    EmailOutcome buildOutcome(String subject, String text, String domain,
                              String intentType, List<DocRef> docs, List<CaseRef> matches,
                              String assignedWorkspaceId, AnswerResult answerResult) {
        String topic = detectTopic(subject + "\n" + text);
        String domainLabel = domainLabel(domain);
        CaseRef primary;
        if (assignedWorkspaceId != null && !assignedWorkspaceId.isBlank()) {
            primary = new CaseRef(assignedWorkspaceId, "", "Bereits zugeordneter Vorgang", "ASSIGNED");
        } else {
            primary = matches.isEmpty() ? null : matches.get(0);
        }

        List<Step> steps = new ArrayList<>();
        steps.add(new Step(1,
                "Angaben zur Person prüfen",
                "Name, Anschrift und Anliegen aus der E-Mail mit dem Vorgang abgleichen.",
                primary != null ? "Fall öffnen" : "Neuen Fall anlegen",
                primary != null ? "/cases/" + primary.id() : "/cases/new",
                true));
        steps.add(new Step(2,
                "Unterlagen prüfen",
                "Prüfen, welche der typischen Unterlagen bereits vorliegen und welche fehlen.",
                "Dokumente öffnen",
                "/documents",
                true));
        steps.add(new Step(3,
                "Fehlende Unterlagen vormerken",
                "Offene Unterlagen in der Checkliste des Falls festhalten.",
                primary != null ? "Checkliste öffnen" : "Nicht verfügbar — zuerst Fall anlegen",
                primary != null ? "/cases/" + primary.id() : null,
                primary != null));
        steps.add(new Step(4,
                "Relevante Rechtsgrundlagen prüfen",
                "Passende Vorschriften und Regelungen in der Wissensbasis nachlesen.",
                "Wissensbasis öffnen",
                "/knowledge?q=" + URLEncoder.encode(subject, StandardCharsets.UTF_8),
                true));
        steps.add(new Step(5,
                "Entscheidungsvorbereitung starten",
                "Eine begründete Entscheidungsvorlage aus den Falldaten erstellen.",
                primary != null ? "Öffnen" : "Nicht verfügbar — zuerst Fall anlegen",
                primary != null ? "/cases/" + primary.id() + "/decision" : null,
                primary != null));
        steps.add(new Step(6,
                "Antwortentwurf erstellen",
                "Einen Entwurf der Antwort an die Bürgerin bzw. den Bürger vorbereiten.",
                primary != null ? "Antwortentwurf öffnen" : "Nicht verfügbar — zuerst Fall anlegen",
                primary != null ? "/cases/" + primary.id() + "/draft" : null,
                primary != null));

        return new EmailOutcome(subject, topic, domainLabel, intentType, matches, docs,
                missingDocuments(topic), steps,
                answerResult != null ? answerResult.answer() : null,
                answerResult != null ? answerResult.grounded() : null,
                answerResult != null ? answerResult.confidence() : null,
                answerResult != null && answerResult.evidence() != null
                        ? answerResult.evidence() : List.of());
    }

    /**
     * Top relevant documents using the same presentation rule as the
     * Wissensbasis. When the routed/classified intent has a specific domain
     * (e.g. GEWERBE for an Imbiss/Gewerbeanmeldung e-mail), documents that
     * the domain gate rejects for that domain are excluded — semantically
     * close but unrelated material (e.g. Binnenschifffahrt) must not be
     * presented as relevant evidence for a different topic.
     *
     * <p>Hybrid scores saturate for citizen-service queries (vector-only
     * scores cluster around 0.65-0.73 for ANY municipal service document, see
     * {@link KnowledgeController}), so the raw ranking alone cannot separate
     * topic documents from incidental matches. The detected topic therefore
     * anchors the presentation: when at least one candidate contains a topic
     * anchor term (the {@link #TOPIC_RULES} terms of the detected topic),
     * candidates without such a term are dropped. When NO candidate contains
     * an anchor term (topic terms absent from the corpus, e.g. "umzug"/"ummelden"),
     * the raw ranking is kept unchanged. More candidates are fetched than
     * presented so the anchor filter does not starve the result.</p>
     */
    private List<DocRef> searchRelevant(String queryText, String domainName, String actorEmail) {
        try {
            SearchQuery query = new SearchQuery(queryText, SearchMode.HYBRID,
                    new SearchFilter(null, null, null, null, null, null, null, null, List.of()),
                    new SearchRequestContext(actorEmail != null ? actorEmail : "system", null, null, null), 0, 8);
            SearchResultPage page = searchFacade.search(query);

            java.util.Set<String> rejectedTitles = java.util.Set.of();
            if (domainName != null && !domainName.isBlank() && !"GENERAL".equals(domainName)) {
                try {
                    Domain domain = Domain.of(domainName);
                    List<String> titles = page.results().stream()
                            .map(r -> r.citation() != null && r.citation().title() != null
                                    ? r.citation().title() : r.chunk().title())
                            .filter(t -> t != null)
                            .distinct()
                            .toList();
                    rejectedTitles = new java.util.HashSet<>(
                            domainGate.filterByDomain(domain, titles).rejected());
                } catch (Exception e) {
                    log.debug("Domain filter skipped for e-mail search ({}): {}", domainName, e.getMessage());
                }
            }
            final java.util.Set<String> rejected = rejectedTitles;

            // Niedrigere Schwelle als die Wissensbasis: Die lexikalische
            // Abdeckung (siehe unten) ist das eigentliche Relevanz-Gate; die
            // Fusions-Schwelle hier filtert nur noch offensichtliches Rauschen.
            List<reasoning.search.model.SearchResult> candidates =
                    page.results().stream()
                            .filter(r -> r.score() >= EMAIL_MIN_SCORE)
                            .filter(r -> KnowledgeController.hasTextualEvidence(
                                    chunkRepository, r.chunk().documentId(), queryText))
                            .filter(r -> {
                                if (rejected.isEmpty()) return true;
                                String title = r.citation() != null && r.citation().title() != null
                                        ? r.citation().title() : r.chunk().title();
                                return title == null || !rejected.contains(title);
                            })
                            .toList();

            // Lexikalische Abdeckung als Relevanz-Gate: Ein Kandidat ist nur
            // dann „relevant", wenn er einen substanziellen Anteil der
            // Frage-Lexeme enthält (keywordScore = Abdeckung 0..1). Die reine
            // Vektor-Ähnlichkeit saturiert auf diesem Korpus (~0.65-0.75 für
            // beliebige Verwaltungsdokumente) und qualifiziert allein nie.
            // Schwelle = max(0.25, Hälfte der besten Abdeckung im Treffer-Satz).
            // 0.25 trennt z. B. die Ummeldungs-Dokumente (0.27-0.36) von einem
            // Einzelwort-Treffer wie „Ufer" in einer Schifffahrtsverordnung (0.20).
            double maxCoverage = candidates.stream()
                    .mapToDouble(reasoning.search.model.SearchResult::keywordScore)
                    .max().orElse(0);
            double coverageFloor = Math.max(0.28, maxCoverage * 0.5);
            List<reasoning.search.model.SearchResult> covered = candidates.stream()
                    .filter(r -> r.keywordScore() >= coverageFloor)
                    .toList();
            if (covered.size() < candidates.size()) {
                log.info("Lexikalische Abdeckung: {} von {} Kandidaten unter Schwelle {} verworfen",
                        candidates.size() - covered.size(), candidates.size(),
                        String.format(java.util.Locale.GERMANY, "%.2f", coverageFloor));
            }
            if (log.isDebugEnabled()) for (var dbg : candidates) {
                log.debug("Coverage-Kandidat: {} kw={} vec={} fused={}",
                        dbg.citation() != null ? dbg.citation().title() : dbg.chunk().title(),
                        String.format(java.util.Locale.GERMANY, "%.3f", dbg.keywordScore()),
                        String.format(java.util.Locale.GERMANY, "%.3f", dbg.vectorScore()),
                        String.format(java.util.Locale.GERMANY, "%.3f", dbg.score()));
            }
            candidates = covered;

            String topic = detectTopic(queryText);
            List<reasoning.search.model.SearchResult> anchored = candidates.stream()
                    .filter(r -> containsTopicAnchor(chunkRepository, r.chunk().documentId(), topic))
                    .toList();
            List<reasoning.search.model.SearchResult> selected =
                    anchored.isEmpty() ? candidates : anchored;

            return selected.stream()
                    .limit(3)
                    .map(r -> new DocRef(
                            r.chunk().documentId().toString(),
                            r.citation() != null && r.citation().title() != null
                                    ? r.citation().title() : r.chunk().title(),
                            r.score()))
                    .toList();
        } catch (Exception e) {
            log.warn("Knowledge search during e-mail analysis failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * True when at least one chunk of the document contains at least one
     * anchor term of the detected topic (the {@link #TOPIC_RULES} terms).
     * Topics without rules ("Allgemeines Anliegen") return false, so the
     * caller's fallback keeps the raw ranking for them.
     */
    static boolean containsTopicAnchor(JpaDocumentChunkRepository chunks, UUID documentId, String topic) {
        if (topic == null) return false;
        String[] anchors = TOPIC_RULES.stream()
                .filter(r -> r.topic().equals(topic))
                .findFirst()
                .map(TopicRule::terms)
                .orElse(null);
        if (anchors == null || anchors.length == 0) return false;
        for (var chunk : chunks.findByDocumentIdOrderByChunkIndex(documentId)) {
            String text = chunk.getText() != null ? chunk.getText().toLowerCase() : "";
            for (String anchor : anchors) {
                if (text.contains(anchor)) return true;
            }
        }
        return false;
    }

    /**
    /**
     * Fall-Abgleich einer E-Mail — delegiert an den gemeinsamen
     * {@link verwaltungsassistent.web.service.EmailCaseMatchingService}
     * (EINE Quelle der Matching-Semantik für die manuelle Analyse UND die
     * automatische Mailbox-Ingestion; keine parallele Logik).
     */
    List<CaseRef> matchCases(String text, String senderEmail, String actorEmail, boolean isAdmin) {
        return matchCases(text, senderEmail, actorEmail, isAdmin, Set.of());
    }

    List<CaseRef> matchCases(String text, String senderEmail, String actorEmail, boolean isAdmin,
                             Set<String> threadReferences) {
        return emailCaseMatchingService.matchCases(text, senderEmail, actorEmail, isAdmin, threadReferences);
    }

    /** Absender-NAME (z. B. "Erika Schulze") aus der E-Mail-Signatur, sofern vorhanden. */
    private static String senderNameFrom(String text) {
        return verwaltungsassistent.web.service.EmailCaseMatchingService.senderNameFrom(text);
    }

    private Set<String> tokens(String text) {
        return verwaltungsassistent.web.service.EmailCaseMatchingService.tokensOf(text);
    }

    /** Gemeinsames generisches Verwaltungsvokabular (siehe EmailCaseMatchingService). */
    public static final Set<String> GENERIC_TERMS =
            verwaltungsassistent.web.service.EmailCaseMatchingService.GENERIC_TERMS;

    /** Gemeinsame Stoppwörter (siehe EmailCaseMatchingService). */
    public static final Set<String> STOPWORDS =
            verwaltungsassistent.web.service.EmailCaseMatchingService.STOPWORDS;

    public static String statusLabel(IncomingEmailEntity.Status status) {
        if (status == null) return "—";
        return switch (status) {
            case NEW -> "Neu / Unerledigt";
            case IN_PROGRESS -> "In Bearbeitung";
            case COMPLETED -> "Erledigt";
        };
    }

    /**
     * Anzeige-Status einer E-Mail (Phase 2C.1): Eine unerledigte E-Mail MIT
     * vorhandenem Analyse-Ergebnis ist "Voranalysiert" — das System hat sie
     * bereits gesehen (automatische Zustellung/Voranalyse), die Bearbeitung
     * steht noch aus. Das ersetzt die irreführende Darstellung "Neu /
     * Unerledigt + E-Mail analysieren" für bereits voranalysierte Nachrichten.
     *
     * <p>Phase 2D.12 — ehrliche Unterscheidung: Nur wenn das gespeicherte
     * Analyse-Ergebnis tatsächlich eine vollständige KI-Analyse enthält
     * ({@code fullAiAnalysis=true}), lautet das Label "KI-Analyse
     * abgeschlossen". Ohne diese Information bleibt es bei "Voranalysiert"
     * (deterministische Vor-Analyse) — es wird nie eine KI-Analyse behauptet,
     * die nicht stattgefunden hat.</p>
     */
    public static String statusLabelFor(IncomingEmailEntity email) {
        return statusLabelFor(email, Boolean.FALSE);
    }

    public static String statusLabelFor(IncomingEmailEntity email, Boolean fullAiAnalysis) {
        if (email == null) return "—";
        if (email.getStatus() == IncomingEmailEntity.Status.NEW && email.getAnalysisId() != null) {
            return Boolean.TRUE.equals(fullAiAnalysis)
                    ? "KI-Analyse abgeschlossen" : "Voranalysiert";
        }
        return statusLabel(email.getStatus());
    }

    public static String statusVariantFor(IncomingEmailEntity email) {
        return statusVariantFor(email, Boolean.FALSE);
    }

    public static String statusVariantFor(IncomingEmailEntity email, Boolean fullAiAnalysis) {
        if (email == null) return "neutral";
        if (email.getStatus() == IncomingEmailEntity.Status.NEW && email.getAnalysisId() != null) {
            return Boolean.TRUE.equals(fullAiAnalysis) ? "success" : "info";
        }
        return statusVariant(email.getStatus());
    }

    public static String statusVariant(IncomingEmailEntity.Status status) {
        if (status == null) return "neutral";
        return switch (status) {
            case NEW -> "warning";
            case IN_PROGRESS -> "info";
            case COMPLETED -> "success";
        };
    }

    private static String subjectOf(String text) {
        String firstLine = text.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("");
        if (firstLine.toLowerCase().startsWith("betreff:")) {
            firstLine = firstLine.substring("betreff:".length()).trim();
        }
        if (firstLine.length() > 120) firstLine = firstLine.substring(0, 120) + "…";
        return firstLine.isEmpty() ? "E-Mail ohne Betreff" : firstLine;
    }

    private static String firstWords(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) return text;
        return String.join(" ", Arrays.copyOf(words, maxWords));
    }

    // ── Topic templates (deterministic, explainable keyword rules) ──

    record TopicRule(String topic, String[] terms) {}

    static final List<TopicRule> TOPIC_RULES = List.of(
            new TopicRule("Wohngeld", new String[]{"wohngeld", "wohnberechtigungsschein"}),
            new TopicRule("Bau / Baugenehmigung",
                    new String[]{"baugenehmigung", "bauantrag", "carport", "bauaufsicht", "bebauung", "bauvorhaben"}),
            new TopicRule("Gewerbe", new String[]{"gewerbe", "gewerbeanmeldung"}),
            new TopicRule("Ausweisdokumente", new String[]{"reisepass", "personalausweis", "ausweis"}),
            new TopicRule("An- / Ummeldung",
                    new String[]{"ummeldung", "umzug", "anmeldung", "wohnungsgeber", "umgemeldet"}),
            new TopicRule("Terminanfrage", new String[]{"termin", "sprechstunde"}),
            new TopicRule("Statusanfrage", new String[]{"status", "sachstand", "bearbeitungsstand", "aktenzeichen"}));

    /**
     * Server-gelieferte Hervorhebungs-Begriffe für die Beleg-Auszüge der
     * E-Mail-Analyse: die Themen-Anker des erkannten Themas plus spezifische
     * (generisches Verwaltungsvokabular-gefilterte) Begriffe aus Betreff und
     * Text. Die Beleg-Ansicht markiert NUR diese Begriffe — nie beliebige
     * Einzelwörter des E-Mail-Texts, die wie eine einfache Stichwortsuche
     * aussehen würden.
     */
    private String emailHighlightTerms(String subject, String text) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String topic = detectTopic((subject != null ? subject + "\n" : "")
                + (text != null ? text : ""));
        TOPIC_RULES.stream()
                .filter(r -> r.topic().equals(topic))
                .findFirst()
                .ifPresent(rule -> {
                    for (String t : rule.terms()) {
                        terms.add(t);
                        terms.addAll(reasoning.common.text.GermanTermVariants.of(t));
                    }
                });
        tokens((subject != null ? subject : "") + " "
                + (text != null ? firstWords(text, 80) : ""))
                .stream()
                .filter(t -> !GENERIC_TERMS.contains(t))
                .forEach(terms::add);
        if (terms.size() > 12) {
            List<String> capped = new ArrayList<>(terms).subList(0, 12);
            return String.join(" ", capped);
        }
        return String.join(" ", terms);
    }

    public static String detectTopic(String text) {
        String lower = text.toLowerCase();
        for (TopicRule rule : TOPIC_RULES) {
            for (String term : rule.terms()) {
                if (lower.contains(term)) return rule.topic();
            }
        }
        return "Allgemeines Anliegen";
    }

    private static final Map<String, List<String>> MISSING_DOCUMENTS = Map.of(
            "Wohngeld", List.of(
                    "Mietvertrag / Mietbescheinigung",
                    "Einkommensnachweise der letzten Monate",
                    "Nachweis über bereits eingereichte Unterlagen"),
            "Bau / Baugenehmigung", List.of(
                    "Bauzeichnungen / Lageplan",
                    "Baubeschreibung",
                    "Nachweis der Grundstücksverhältnisse"),
            "Gewerbe", List.of(
                    "Ausgefüllte Gewerbeanmeldung",
                    "Ausweisdokument",
                    "Ggf. Handelsregisterauszug"),
            "Ausweisdokumente", List.of(
                    "Biometrisches Lichtbild",
                    "Bisheriges Ausweisdokument",
                    "Geburtsurkunde (bei erstmaliger Beantragung)"),
            "An- / Ummeldung", List.of(
                    "Wohnungsgeberbestätigung",
                    "Ausweisdokument der betroffenen Personen"),
            "Terminanfrage", List.of(
                    "Gewünschter Zeitraum",
                    "Kurze Beschreibung des Anliegens"),
            "Statusanfrage", List.of(
                    "Aktenzeichen / Vorgangsnummer",
                    "Datum der Einreichung"),
            "Allgemeines Anliegen", List.of(
                    "Name und Anschrift der anfragenden Person",
                    "Bezug zu einem bestehenden Vorgang (falls vorhanden)"));

    private static List<String> missingDocuments(String topic) {
        return MISSING_DOCUMENTS.getOrDefault(topic, MISSING_DOCUMENTS.get("Allgemeines Anliegen"));
    }

    /** Typische Unterlagen zum Thema (deterministisch) — gemeinsame Quelle der Fallseite. */
    public static List<String> typicalMissingDocuments(String topic) {
        if (topic == null) {
            return MISSING_DOCUMENTS.get("Allgemeines Anliegen");
        }
        return MISSING_DOCUMENTS.getOrDefault(topic, MISSING_DOCUMENTS.get("Allgemeines Anliegen"));
    }

    private static String domainLabel(String domain) {
        if (domain == null) return null;
        return switch (domain.toUpperCase()) {
            case "BUILDING" -> "Bau";
            case "TRAVEL" -> "Reisekosten";
            case "HR" -> "Personal / Besoldung";
            case "PROCUREMENT" -> "Vergabe";
            case "GEWERBE" -> "Gewerbe / Gastronomie";
            case "GENERAL" -> null;
            default -> domain;
        };
    }

    // ── Rendering helpers ──

    private String render(String template, String fragment, Map<String, Object> attributes) {
        attributes.putIfAbsent("supervisory", Boolean.FALSE);
        attributes.putIfAbsent("emailDraft", null);
        attributes.putIfAbsent("draftAllowed", Boolean.FALSE);
        Context context = new Context(Locale.GERMANY, attributes);
        return templateEngine.process(template, Set.of(fragment), context);
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(body);
    }

    private void addChrome(Model model) {
        model.addAttribute("pageTitle", "E-Mails");
        model.addAttribute("activeSection", "emails");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("E-Mails", "/emails")));
    }

    // ── Result DTOs ──

    /** The rendered workflow result of an analyzed citizen e-mail. */
    public record EmailOutcome(String subject, String topicLabel, String domainLabel, String intentType,
                               List<CaseRef> matchedCases, List<DocRef> relevantDocuments,
                               List<String> missingDocuments, List<Step> steps,
                               String aiAnswer, Boolean aiGrounded, Integer aiConfidence,
                               List<AiEvidence> aiEvidence,
                               verwaltungsassistent.web.mailbox.MailboxIntakeService.IntakeInfo intake) {
        /** Manuell analysierte E-Mails (ohne automatischen Mailbox-Intake). */
        public EmailOutcome(String subject, String topicLabel, String domainLabel, String intentType,
                            List<CaseRef> matchedCases, List<DocRef> relevantDocuments,
                            List<String> missingDocuments, List<Step> steps,
                            String aiAnswer, Boolean aiGrounded, Integer aiConfidence,
                            List<AiEvidence> aiEvidence) {
            this(subject, topicLabel, domainLabel, intentType, matchedCases, relevantDocuments,
                    missingDocuments, steps, aiAnswer, aiGrounded, aiConfidence, aiEvidence, null);
        }
    }

    /**
     * @param matchType "EMAIL_IDENTITY" (Absender-Adresse im Vorgang belegt),
     *                  "NAME_IDENTITY" (nur Namens-Übereinstimmung) oder
     *                  "SIMILAR" (nur thematische Ähnlichkeit: Referenz).
     */
    public record CaseRef(String id, String name, String reason, String matchType) {}

    public record DocRef(String id, String title, double score) {}

    /** Ein Beleg der KI-Beantwortung (aus den tatsächlichen Zitaten der Pipeline). */
    /** Beleg einer KI-Beantwortung inkl. Quell-Referenz für die Provenienz-
     *  Sicht (Phase 2C.11): documentId/chunkId stammen aus der bestehenden
     *  Citation — nur damit kann „Quelle öffnen" die exakte Passage öffnen. */
    public record AiEvidence(String title, String excerpt, Integer pageNumber, Double score, String tier,
                             String documentId, String chunkId) {
        public AiEvidence(String title, String excerpt, Integer pageNumber, Double score, String tier) {
            this(title, excerpt, pageNumber, score, tier, null, null);
        }
    }

    /** Ergebnis der KI-Beantwortung; bei Fehlschlag sind alle Felder null/leer. */
    public record AnswerResult(String answer, Boolean grounded, Integer confidence,
                               List<AiEvidence> evidence, long ms) {}

    public record Step(int order, String title, String description,
                       String actionLabel, String actionUrl, boolean available) {}

    // ── Demo e-mails (fictional examples, no real personal data) ──

    public record DemoEmail(String id, String subject, String from, String preview, String body) {}

    private static final List<DemoEmail> DEMO_EMAILS = List.of(
            new DemoEmail("demo-1", "Wohngeldantrag – welche Unterlagen fehlen noch?",
                    "Erika Müller <erika.mueller@example.de>",
                    "Frage zum eingereichten Wohngeldantrag und zu fehlenden Unterlagen.",
                    """
                    Betreff: Wohngeldantrag – welche Unterlagen fehlen noch?

                    Guten Tag,

                    ich habe vor zwei Wochen meinen Antrag auf Wohngeld eingereicht.
                    Ich bin mir aber nicht sicher, ob alle Unterlagen angekommen sind.
                    Können Sie bitte prüfen, ob noch etwas fehlt? Mein Mietvertrag und
                    die Einkommensnachweise der letzten drei Monate liegen dem Antrag bei.

                    Mit freundlichen Grüßen
                    Erika Müller
                    Friedrich-Ebert-Straße 79, 14469 Potsdam"""),
            new DemoEmail("demo-2", "Ummeldung nach Umzug – was wird benötigt?",
                    "Thomas Schmidt <thomas.schmidt@example.de>",
                    "Bitte um Auskunft zu den Unterlagen für die Ummeldung.",
                    """
                    Betreff: Ummeldung nach Umzug – was wird benötigt?

                    Sehr geehrte Damen und Herren,

                    ich bin am 1. August in eine neue Wohnung gezogen und möchte mich
                    ummelden. Welche Unterlagen muss ich zur Ummeldung mitbringen und
                    wie schnell muss die Ummeldung erfolgen?

                    Vielen Dank
                    Thomas Schmidt"""),
            new DemoEmail("demo-3", "Reisepass für meine Tochter beantragen",
                    "Julia Weber <julia.weber@example.de>",
                    "Frage zu den Unterlagen für einen Reisepass für Minderjährige.",
                    """
                    Betreff: Reisepass für meine Tochter beantragen

                    Guten Tag,

                    meine Tochter ist 14 Jahre alt und braucht einen neuen Reisepass.
                    Welche Unterlagen benötigen wir für die Beantragung eines
                    Reisepasses für Minderjährige und muss mein Mann mitkommen?

                    Mit freundlichen Grüßen
                    Julia Weber"""),
            new DemoEmail("demo-4", "Baugenehmigung für ein Carport – fehlende Unterlagen",
                    "Bernd Becker <bernd.becker@example.de>",
                    "Nachfrage zum Stand des Bauantrags und zu fehlenden Unterlagen.",
                    """
                    Betreff: Baugenehmigung für ein Carport – fehlende Unterlagen

                    Sehr geehrte Damen und Herren,

                    ich habe einen Antrag auf Baugenehmigung für ein Carport auf
                    meinem Grundstück gestellt. Leider habe ich noch keine Rückmeldung
                    erhalten. Welche Unterlagen fehlen meinem Bauantrag noch?

                    Mit freundlichen Grüßen
                    Bernd Becker"""),
            new DemoEmail("demo-5", "Gewerbeanmeldung – welche Unterlagen werden benötigt?",
                    "Claudia Fischer <claudia.fischer@example.de>",
                    "Frage zu den Unterlagen für eine Gewerbeanmeldung.",
                    """
                    Betreff: Gewerbeanmeldung – welche Unterlagen werden benötigt?

                    Guten Tag,

                    ich möchte zum 1. September ein kleines Gewerbe anmelden.
                    Welche Unterlagen benötige ich für die Gewerbeanmeldung und
                    kann ich den Termin auch online vereinbaren?

                    Viele Grüße
                    Claudia Fischer"""),
            new DemoEmail("demo-6", "Sachstand zu meinem Antrag",
                    "Peter Hoffmann <peter.hoffmann@example.de>",
                    "Bitte um Auskunft zum Bearbeitungsstand eines Antrags.",
                    """
                    Betreff: Sachstand zu meinem Antrag

                    Sehr geehrte Damen und Herren,

                    können Sie mir bitte den aktuellen Status meines Antrags
                    mitteilen? Ich habe die Unterlagen am 12. Juli eingereicht
                    und bislang keine Rückmeldung erhalten. Mein Aktenzeichen
                    lautet 2026-0417-H.

                    Mit freundlichen Grüßen
                    Peter Hoffmann"""),
            new DemoEmail("demo-7", "Termin im Bürgeramt vereinbaren",
                    "Sabine Lehmann <sabine.lehmann@example.de>",
                    "Bitte um einen Termin zur Verlängerung des Personalausweises.",
                    """
                    Betreff: Termin im Bürgeramt vereinbaren

                    Guten Tag,

                    ich möchte gerne einen Termin im Bürgeramt vereinbaren, um
                    meinen Personalausweis zu verlängern. Welche Zeiten stehen
                    zur Verfügung und welche Unterlagen muss ich zum Termin
                    mitbringen?

                    Mit freundlichen Grüßen
                    Sabine Lehmann"""),
            new DemoEmail("demo-8", "Personalausweis verloren – was ist zu tun?",
                    "Markus Wagner <markus.wagner@example.de>",
                    "Frage zum Vorgehen nach dem Verlust des Personalausweises.",
                    """
                    Betreff: Personalausweis verloren – was ist zu tun?

                    Sehr geehrte Damen und Herren,

                    ich habe meinen Personalausweis verloren. Was muss ich jetzt
                    tun und welche Unterlagen brauche ich für die Neuausstellung?
                    Muss ich den Verlust irgendwo melden?

                    Mit freundlichen Grüßen
                    Markus Wagner"""));
}