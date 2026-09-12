package verwaltungsassistent.web.config;

import reasoning.common.model.DocumentCategory;
import reasoning.common.model.WorkspacePhase;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.model.Document;
import reasoning.workspace.api.AttachDocumentCommand;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.service.DemoDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Seeds the fictional demonstration cases for the E-Mail → Fall demo workflow
 * (dev/demo profiles).
 *
 * <p>The demo e-mails in the {@link DemoDataService} catalog token-match these
 * cases so that "Fall öffnen" opens the case belonging to the selected
 * scenario. The seeder is idempotent: existing cases are reused and their
 * phase reset to SETUP so every demo run starts with a coherent "case opened,
 * phase 1" story. Only clearly fictional demo cases are touched; the
 * persisted checklist is cleared so the new per-phase defaults apply.</p>
 *
 * <p>Geovorgänge and demo photographs are NOT created here — they come from
 * the deterministic {@link DemoDataService} (persistent originals →
 * EXIF → GeoService → Geovorgang), invoked on startup when no photo records
 * exist yet.</p>
 */
@Component
@Profile({"dev", "demo & !playwright"})
public class DemoCaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCaseSeeder.class);
    // Phase 2C.5: Pool-Demo-Fälle sind UNZUGEWIESEN (owner null) — das
    // Leitungs-Konto (admin@verwaltungsassistent.local) ist keine operative Zuständigkeit
    // und niemals Eigentümer von Vorgängen.
    private static final String OWNER = null;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Deliberate demo mixture (honest evidence states):
     * <ul>
     *   <li>Fall Müller – Wohngeld: NO documents — the analysis must
     *       explicitly end in insufficient evidence, never in a confidence score.</li>
     *   <li>Ummeldung / Reisepass / Bau / Gewerbe: real corpus documents are
     *       attached, so the case workflow (Ingestion → Analyse) has evidence
     *       to work with.</li>
     * </ul>
     */
    /**
     * Phase 2D.12: triggerSubject/senderEmail beschreiben die AUSLÖSER-E-Mail,
     * die der "source"-Zeile ("E-Mail von …") zugrunde liegt. Der Seeder legt
     * zu jedem Fall eine echte {@link IncomingEmailEntity} mit deterministischer
     * Vor-Analyse an und verknüpft sie über workspace_id — die Fallseite
     * ("Auslöser: …") und der E-Mail-Tab des Falls sind damit konsistent
     * (keine beschreibende Behauptung ohne zugehörige E-Mail).
     *
     * <p>Phase 2D.13: deadlineDate/deadlineTitle erzeugen eine FAKTISCHE
     * Frist (DEADLINE-Timeline-Ereignis) aus der Fall-Geschichte — die
     * BESTEHENDE Prioritätsberechnung (Wartezeit + Frist + Fallart) erreicht
     * damit ehrlich auch die oberen Klassen (Hoch/Sehr hoch); es wird keine
     * Prio-„Etikette" erfunden.</p>
     */
    private record DemoCase(String name, String description, String source, String citizen,
                            String category, List<String> attachDocumentTitles,
                            String triggerSubject, String senderEmail,
                            java.time.LocalDate deadlineDate, String deadlineTitle) {}

    private static final List<DemoCase> DEMO_CASES = List.of(
            new DemoCase("Fall Müller – Wohngeld",
                    "Wohngeldantrag von Erika Müller – Unterlagen unvollständig, Prüfung offen.",
                    "E-Mail von Erika Müller (03.08.2026)", "Erika Müller", "Wohngeld", List.of(),
                    "Wohngeldantrag – welche Unterlagen fehlen?",
                    "erika.mueller@example.de",
                    java.time.LocalDate.of(2026, 8, 20),
                    "Nachfrist: Wohngeld-Unterlagen nachreichen"),
            new DemoCase("Ummeldung nach Umzug",
                    "Ummeldung nach Umzug – benötigte Unterlagen und Fristen klären.",
                    "E-Mail von Thomas Schmidt (05.08.2026)", "Thomas Schmidt", "Ummeldung",
                    List.of("Änderung/Wechsel der Hauptwohnung", "Abmeldung einer Wohnung"),
                    "Ummeldung nach Umzug",
                    "thomas.schmidt@example.de",
                    null, null),
            new DemoCase("Reisepass – Minderjährige",
                    "Reisepass für Minderjährige – benötigte Unterlagen und Anwesenheit der Eltern.",
                    "E-Mail von Julia Weber (04.08.2026)", "Julia Weber", "Reisepass",
                    List.of("Reisepass beantragen", "Personalausweis beantragen"),
                    "Reisepass für Minderjährige beantragen",
                    "julia.weber@example.de",
                    null, null),
            new DemoCase("Baugenehmigung Carport",
                    "Antrag auf Baugenehmigung für ein Carport – fehlende Unterlagen.",
                    "E-Mail von Bernd Becker (06.08.2026)", "Bernd Becker", "Baugenehmigung",
                    List.of("Bauordnung für Berlin (BauO Bln)"),
                    "Baugenehmigung für ein Carport – Antrag",
                    "bernd.becker@example.de",
                    java.time.LocalDate.of(2026, 9, 2),
                    "Nachfrist: Unterlagen zum Carport-Bauantrag nachreichen"),
            new DemoCase("Gewerbeanmeldung",
                    "Gewerbeanmeldung zum 1. September – benötigte Unterlagen und Online-Termin.",
                    "E-Mail von Claudia Fischer (07.08.2026)", "Claudia Fischer", "Gewerbeanmeldung",
                    List.of("info_gewerbe_anmelden.pdf"),
                    "Gewerbeanmeldung zum 1. September",
                    "claudia.fischer@example.de",
                    java.time.LocalDate.of(2026, 9, 1),
                    "Frist: Gewerbeaufnahme zum 1. September"));

    /**
     * Normalized names of the seeded e-mail workflow cases. The deterministic
     * demo reset keeps exactly these admin-owned cases and removes legacy
     * test cases.
     */
    public static final Set<String> SEEDED_CASE_NAMES = DEMO_CASES.stream()
            .map(c -> normalizeName(c.name()))
            .collect(Collectors.toSet());

    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;
    private final JpaEmailAnalysisRepository emailAnalysisRepository;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository casePlanningRepository;
    private final DemoDataService demoDataService;
    private final DemoStateResetter demoStateResetter;
    /** One-Shot-Overnight-Verarbeitung (Phase 2D.11) — Standard AUS. */
    private final boolean overnightEnabled;
    private final int overnightLimit;
    private final int overnightOffset;
    private final verwaltungsassistent.web.mailbox.DemoOvernightEmailProcessor overnightProcessor;

    public DemoCaseSeeder(WorkspaceService workspaceService, DocumentFacade documentFacade,
                          JpaEmailAnalysisRepository emailAnalysisRepository,
                          JpaIncomingEmailRepository incomingEmailRepository,
                          verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository casePlanningRepository,
                          DemoDataService demoDataService,
                          DemoStateResetter demoStateResetter,
                          @org.springframework.beans.factory.annotation.Value("${demo.overnight:false}") boolean overnightEnabled,
                          @org.springframework.beans.factory.annotation.Value("${demo.overnight.limit:0}") int overnightLimit,
                          @org.springframework.beans.factory.annotation.Value("${demo.overnight.offset:0}") int overnightOffset,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          verwaltungsassistent.web.mailbox.DemoOvernightEmailProcessor overnightProcessor) {
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.emailAnalysisRepository = emailAnalysisRepository;
        this.incomingEmailRepository = incomingEmailRepository;
        this.casePlanningRepository = casePlanningRepository;
        this.demoDataService = demoDataService;
        this.demoStateResetter = demoStateResetter;
        this.overnightEnabled = overnightEnabled;
        this.overnightLimit = overnightLimit;
        this.overnightOffset = overnightOffset;
        this.overnightProcessor = overnightProcessor;
    }

    /** Phase 2D.15: Vorbereitungslauf (Dateisystem-Dataset) — normales Demo-Seeding/Reset aus. */
    @org.springframework.beans.factory.annotation.Value("${demo.prepare.enabled:false}")
    private boolean prepareEnabled = false;

    /** Phase 2D.15: Start im VORBEREITETEN Zustand — kein Reset/Seed beim Boot. */
    @org.springframework.beans.factory.annotation.Value("${demo.startup.reset:true}")
    private boolean startupReset = true;

    @Override
    public void run(String... args) {
        if (prepareEnabled) {
            log.warn("demo.prepare.enabled=true — normales Demo-Seeding/Reset übersprungen "
                    + "(die Dataset-Aufbereitung übernimmt den Demo-Zustand).");
            return;
        }
        if (!startupReset) {
            log.info("demo.startup.reset=false — vorbereiteter Demo-Zustand bleibt erhalten "
                    + "(kein Reset/Reseed beim Boot).");
            return;
        }
        try {
            dedupeEmailAnalyses();
            // Kohärenter Workflow-Reset des Demo-Zustands: E-Mails, Analysen,
            // Alt-Demo-Fälle und Demo-Benutzer-Fälle kehren auf den
            // Ausgangszustand zurück, bevor die Seed-Fälle erneut abgeglichen
            // werden (Wissensbasis/Qdrant bleiben unberührt).
            demoStateResetter.reset();
            Map<String, WorkspaceEntity> existing = workspaceService.findAll().stream()
                    .collect(Collectors.toMap(ws -> normalizeName(ws.getName()),
                            ws -> ws, (a, b) -> a));
            for (DemoCase demoCase : DEMO_CASES) {
                seedCase(demoCase, existing.get(normalizeName(demoCase.name())));
            }
            // Geovorgänge + Demo-Fotos: deterministischer Import (nur wenn noch nichts da ist).
            demoDataService.importDemoPhotosIfMissing();
            // Issue 6: Demo-Wissensdokument Straßenbeleuchtung (idempotent) —
            // schließt die Korpus-Lücke für Infrastruktur-Meldungen.
            demoDataService.seedStreetLightingKnowledge();
            // Phase 2D.11 — One-Shot-Overnight-Vorbereitung: bewusst NACH dem
            // vollständigen Demo-Reset/-Reseed (Mailbox neu befüllt, Nutzer
            // sichergestellt) und IM SELBEN Anwendungskontext — kein zweiter
            // Boot, kein zweites GreenMail, keine Wiederholung. Standard AUS
            // (demo.overnight=false); der Einmal-Lauf blockiert den Demo-Start,
            // danach bleibt die Anwendung für die Vorführung laufen.
            if (overnightEnabled && overnightProcessor != null) {
                log.info("DEMO OVERNIGHT: demo.overnight=true — verarbeite Demo-Mailbox "
                        + "(limit={}, offset={}).", overnightLimit, overnightOffset);
                int result = overnightProcessor.run(overnightOffset, overnightLimit);
                if (result == 0) {
                    log.info("DEMO OVERNIGHT: Einmal-Lauf erfolgreich abgeschlossen — "
                            + "die Anwendung startet jetzt im vorbereiteten Morgen-Zustand.");
                } else {
                    log.warn("DEMO OVERNIGHT: Einmal-Lauf mit Fehlern beendet (Exit-Code {}).", result);
                }
            } else if (overnightEnabled && overnightProcessor == null) {
                log.warn("demo.overnight=true, aber der Overnight-Prozessor ist in diesem Profil nicht verfügbar.");
            }
        } catch (Exception e) {
            log.warn("Demo case seeding failed: {}", e.getMessage());
        }
    }

    /**
     * Removes duplicate stored e-mail analyses (same user, same question
     * text), keeping only the newest record per text. Re-analysis already
     * updates instead of inserting; this cleans up duplicates created by
     * older versions so the dashboard feed stays unambiguous.
     */
    private void dedupeEmailAnalyses() {
        try {
            Map<String, EmailAnalysisEntity> newestByKey = new LinkedHashMap<>();
            List<EmailAnalysisEntity> all = emailAnalysisRepository.findAll();
            for (EmailAnalysisEntity a : all) {
                String key = (a.getUserEmail() == null ? "" : a.getUserEmail()) + "|"
                        + (a.getQuestionText() == null ? "" : a.getQuestionText());
                EmailAnalysisEntity existing = newestByKey.get(key);
                if (existing == null || a.getCreatedAt().isAfter(existing.getCreatedAt())) {
                    newestByKey.put(key, a);
                }
            }
            Set<String> keepIds = newestByKey.values().stream()
                    .map(a -> a.getId().toString()).collect(Collectors.toSet());
            List<EmailAnalysisEntity> duplicates = all.stream()
                    .filter(a -> !keepIds.contains(a.getId().toString()))
                    .toList();
            if (!duplicates.isEmpty()) {
                emailAnalysisRepository.deleteAll(duplicates);
                log.info("Removed {} duplicate e-mail analysis record(s)", duplicates.size());
            }
        } catch (Exception e) {
            log.warn("E-mail analysis dedupe skipped: {}", e.getMessage());
        }
    }

    /** Name matching ignores hyphen/en-dash variants and whitespace, so existing cases are reused. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase().replace("-", "").replace("–", "").replace(" ", "");
    }

    private void seedCase(DemoCase demoCase, WorkspaceEntity existing) {
        WorkspaceEntity entity;
        if (existing == null) {
            entity = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                    demoCase.name(), demoCase.description(), "CASE", OWNER));
            log.info("Seeded demo case: {}", demoCase.name());
        } else {
            entity = existing;
            // Alt-Daten aus früheren Versionen (Pool-Marker admin@verwaltungsassistent.local)
            // beim Wiederverwenden auf den Pool-Zustand heben: unzugewiesen.
            if (DemoDataService.DEMO_OWNER.equalsIgnoreCase(entity.getOwnerId())) {
                entity.setOwnerId(null);
            }
            log.info("Reusing existing demo case: {}", demoCase.name());
        }

        // Demo runs start coherent: case opens at Phase 1 (Entwurf), fresh
        // review checklist, no timeline events, no analysis. All state below is
        // deterministic — stale analysis runs, markers, workflow events and
        // workflow-generated phase data from previous demo runs are removed.
        workspaceService.deleteAnalysisRuns(entity.getId().toString());
        workspaceService.replaceTimeline(entity.getId().toString(), List.of());
        entity.setStatus(reasoning.common.model.WorkspaceStatus.DRAFT);
        entity.setPhase(WorkspacePhase.SETUP);
        Map<String, Object> data = parsePhaseData(entity.getPhaseData());
        data.put("source", demoCase.source());
        data.put("citizen", demoCase.citizen());
        data.put("caseCategory", demoCase.category());
        // Der Analyse-MARKER (phaseData.analysis.status) gehört zu den gelöschten
        // Runs: ohne Persistenz-Ergebnis darf kein "COMPLETED"-Marker stehenbleiben.
        // Sonst liest die Fallseite "Analyse abgeschlossen" und die Entscheidungs-
        // Seite "keine Analyse" (kein Run vorhanden) — die Quelle der
        // Entscheidung-↔-Fall-Schleife und des "Vorlage nicht mehr verfügbar"-Fehlers.
        data.remove("analysis");
        // Workflow-generierte Phase-Daten: Checkliste, Notizen, Ingestion-Ack,
        // Schließen-Informationen und E-Mail-Verknüpfungen aus Demo-Sitzungen.
        data.remove("checklist");
        data.remove("notes");
        data.remove("ingestionResolved");
        data.remove("closedAt");
        data.remove("closedBy");
        data.remove("sourceEmailId");
        data.remove("sourceEmailAt");
        data.remove("sourceEmailSubject");
        // Phase-2A-Arbeitszustand: frische Demo-Fälle starten ohne workState/waitingOn.
        data.remove("workState");
        data.remove("waitingOn");
        // Phase 2D.12: die AUSLÖSER-E-Mail des Falls existiert als echte,
        // verknüpfte Eingangs-E-Mail (workspace_id) mit deterministischer
        // Vor-Analyse — der "Auslöser: E-Mail von …"-Kopf und der E-Mail-Tab
        // des Falls bleiben konsistent. Idempotent (Message-ID), kein LLM.
        // Die Auslöser-Verknüpfung (sourceEmailId/-Subject) kommt in DEN
        // EINEN save() des Falls (genau ein Speichern je Fall).
        TriggerLink trigger = seedTriggerEmail(demoCase, entity);
        if (trigger != null) {
            data.put("sourceEmailId", trigger.analysisId().toString());
            data.put("sourceEmailSubject", trigger.subject());
        }
        try {
            entity.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            entity.setPhaseData("{}");
        }
        workspaceService.save(entity);

        // Deterministischer Dokument-Bestand: alle dynamisch angehängten
        // Dokumente werden gelöst, danach die kanonische Seed-Auswahl angehängt.
        for (WorkspaceDocumentLinkEntity link : workspaceService.getWorkspaceDocuments(entity.getId().toString())) {
            try {
                workspaceService.detachDocument(entity.getId().toString(), link.getId());
            } catch (Exception e) {
                log.debug("Lösen von Dokument {} fehlgeschlagen: {}", link.getDocumentId(), e.getMessage());
            }
        }
        if (demoCase.attachDocumentTitles() != null) {
            for (String title : demoCase.attachDocumentTitles()) {
                attachCorpusDocument(entity, title);
            }
        }

        // Phase 2D.13: plausible FRIST aus der Fall-Geschichte (DEADLINE-
        // Ereignis der bestehenden Timeline) — die Prioritätsberechnung
        // erreicht damit ehrlich die oberen Klassen. Keine künstliche
        // Prio-Etikette; die Frist ist als Timeline-Ereignis transparent.
        if (demoCase.deadlineDate() != null && demoCase.deadlineTitle() != null) {
            try {
                workspaceService.addTimelineEvent(
                        entity.getId().toString(), demoCase.deadlineDate(),
                        demoCase.deadlineTitle(),
                        "Frist aus der Vorgangsbearbeitung — fließt in die Prioritätsberechnung ein.",
                        reasoning.workspace.model.TimelineEventType.DEADLINE,
                        null, 1.0, false);
            } catch (Exception e) {
                log.warn("Frist-Ereignis für Demo-Fall '{}' nicht angelegt: {}", entity.getName(), e.getMessage());
            }
        }

        // Planungs-Cache des Vorlaufs entfernen: Der Seeder hat E-Mails und
        // Fristen (Neu-)gesetzt — der nächste Lesevorgang berechnet den
        // Planungsstand (Priorität/Bearbeitbarkeit) frisch, statt einen
        // veralteten Stand aus früheren Läufen zu verwenden.
        try {
            casePlanningRepository.deleteByCaseId(UUID.fromString(entity.getId()));
        } catch (Exception e) {
            log.debug("Planungs-Cache von Demo-Fall '{}' nicht entfernbar: {}", entity.getName(), e.getMessage());
        }
    }

    /** Auslöser-Verknüpfung einer angelegten E-Mail (Analyse-Id + Betreff). */
    private record TriggerLink(UUID analysisId, String subject) {}

    /** Datum aus der Quellen-Zeile ("E-Mail von X (dd.MM.yyyy)") → 09:00 Ortszeit. */
    private static final Pattern TRIGGER_DATE = Pattern.compile("\\((\\d{2}\\.\\d{2}\\.\\d{4})\\)");
    private static final DateTimeFormatter TRIGGER_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static Instant triggerReceivedAt(String source) {
        Matcher m = TRIGGER_DATE.matcher(source != null ? source : "");
        if (!m.find()) {
            return Instant.now();
        }
        try {
            return LocalDate.parse(m.group(1), TRIGGER_DATE_FMT)
                    .atTime(LocalTime.of(9, 0))
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private TriggerLink seedTriggerEmail(DemoCase demoCase, WorkspaceEntity entity) {
        try {
            String messageId = "demo-seed:" + normalizeName(demoCase.name()) + "@verwaltungsassistent.local";
            String subject = demoCase.triggerSubject() != null
                    ? demoCase.triggerSubject() : demoCase.name();
            String text = "Sehr geehrte Damen und Herren,\n\n"
                    + demoCase.description() + "\n\nMit freundlichen Grüßen\n" + demoCase.citizen();
            Instant receivedAt = triggerReceivedAt(demoCase.source());
            IncomingEmailEntity email = incomingEmailRepository.findByMessageId(messageId)
                    .orElseGet(() -> {
                        IncomingEmailEntity created = new IncomingEmailEntity(
                                UUID.randomUUID(), subject, demoCase.citizen(),
                                demoCase.senderEmail(), text, receivedAt,
                                IncomingEmailEntity.AddressedTo.GENERAL, null);
                        created.setMessageId(messageId);
                        return created;
                    });
            // Workflow-Zustand frisch setzen: Der Demo-Reset kennt diese
            // Auslöser-E-Mails nicht (kein Katalog-Eintrag), deshalb wird die
            // Zeile hier deterministisch auf den Ausgangszustand gehoben
            // (Inhalte sind konstruktor-fixiert und pro Message-ID stabil).
            email.setAddressedTo(IncomingEmailEntity.AddressedTo.GENERAL);
            email.setAddressedToEmail(null);
            email.setSourceType(IncomingEmailEntity.SourceType.MAILBOX);
            email.setStatus(IncomingEmailEntity.Status.NEW);
            email.setAssignedTo(null);
            email.setAssignedAt(null);
            email.setCompletedAt(null);
            email.setCompletedBy(null);
            email.setReviewRequired(null);
            email.setWorkspaceId(UUID.fromString(entity.getId()));
            UUID analysisId = createTriggerPreAnalysis(email, entity, demoCase);
            email.setAnalysisId(analysisId);
            incomingEmailRepository.save(email);
            log.info("Auslöser-E-Mail '{}' für Demo-Fall '{}' verknüpft", subject, entity.getName());
            return new TriggerLink(analysisId, subject);
        } catch (Exception e) {
            log.warn("Auslöser-E-Mail für Demo-Fall '{}' nicht angelegt: {}", entity.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Deterministische Vor-Analyse der Auslöser-E-Mail (kein LLM, gleiche
     * Bausteine wie die Mailbox-Vor-Analyse): Thema erkennen + ehrlichen
     * ersten Schritt mit Verweis auf den bereits bestehenden Vorgang.
     */
    private UUID createTriggerPreAnalysis(IncomingEmailEntity email, WorkspaceEntity entity,
                                          DemoCase demoCase) throws Exception {
        String text = email.getText() != null ? email.getText() : "";
        String subject = email.getSubject() != null ? email.getSubject() : "";
        String topic = verwaltungsassistent.web.controller.EmailController
                .detectTopic(subject + "\n" + text);
        String caseLink = "/cases/" + entity.getId();
        List<verwaltungsassistent.web.controller.EmailController.Step> steps = List.of(
                new verwaltungsassistent.web.controller.EmailController.Step(1,
                        "Vorgang ist bereits angelegt",
                        "Die Auslöser-E-Mail gehört zum Vorgang „" + entity.getName() + "“ ("
                                + entity.getWorkspaceCode() + ") — die Bearbeitung erfolgt im Vorgang.",
                        "Zum Vorgang", caseLink, true));
        verwaltungsassistent.web.controller.EmailController.EmailOutcome outcome =
                new verwaltungsassistent.web.controller.EmailController.EmailOutcome(
                        subject, topic, null, null,
                        List.of(), List.of(),
                        verwaltungsassistent.web.controller.EmailController
                                .typicalMissingDocuments(topic),
                        steps, null, null, null, List.of());
        EmailAnalysisEntity analysis = new EmailAnalysisEntity(UUID.randomUUID(),
                verwaltungsassistent.web.mailbox.DemoMailboxServer.MAILBOX_USER,
                text, subject, topic, null, null, Instant.now());
        analysis.setResultJson(MAPPER.writeValueAsString(outcome));
        emailAnalysisRepository.save(analysis);
        return analysis.getId();
    }

    /** Attaches the first corpus document whose title matches (idempotent, never fails the seeding). */
    private void attachCorpusDocument(WorkspaceEntity entity, String title) {
        try {
            List<WorkspaceDocumentLinkEntity> links =
                    workspaceService.getWorkspaceDocuments(entity.getId().toString());
            Set<String> attached = links.stream()
                    .map(WorkspaceDocumentLinkEntity::getDocumentId).collect(Collectors.toSet());
            var filter = new DocumentFilter(null, null, null, null, null, null, null, 0, 200);
            List<Document> candidates = documentFacade.findDocuments(filter).documents();
            for (Document doc : candidates) {
                if (title.equalsIgnoreCase(doc.metadata().title())) {
                    DocumentCategory realType = parseCategory(doc.metadata().category());
                    if (!attached.contains(doc.id().toString())) {
                        var cmd = new AttachDocumentCommand(
                                entity.getId().toString(), doc.id().toString(),
                                realType,
                                doc.metadata().category() != null ? doc.metadata().category() : "general",
                                null);
                        workspaceService.attachDocument(cmd);
                        log.info("Attached corpus document '{}' to demo case '{}'", title, entity.getName());
                    } else {
                        // Older fixtures attached demo documents with a hardcoded
                        // CONTRACT link type — correct the existing link so the
                        // displayed type reflects the real document category.
                        links.stream()
                                .filter(l -> l.getDocumentId().equals(doc.id().toString()))
                                .filter(l -> l.getDocumentType() != realType)
                                .findFirst()
                                .ifPresent(l -> {
                                    workspaceService.updateDocumentLinkType(l.getId(), realType);
                                    log.info("Corrected document type of '{}' in demo case '{}' to {}",
                                            title, entity.getName(), realType);
                                });
                    }
                    return;
                }
            }
            log.debug("Corpus document '{}' not found for demo case '{}'", title, entity.getName());
        } catch (Exception e) {
            log.debug("Document attach skipped for demo case {}: {}", entity.getName(), e.getMessage());
        }
    }

    /** The document's own category as link type (fallback OTHER for unknown strings). */
    private static DocumentCategory parseCategory(String category) {
        if (category == null || category.isBlank()) return DocumentCategory.OTHER;
        try {
            return DocumentCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DocumentCategory.OTHER;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePhaseData(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object parsed = MAPPER.readValue(json, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception e) {
            // fall through
        }
        return new LinkedHashMap<>();
    }
}
