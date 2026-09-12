package verwaltungsassistent.web.service;

import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.auth.model.Role;
import reasoning.common.model.DocumentCategory;
import reasoning.common.model.DocumentFileType;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.DocumentIngestionProcessor;
import reasoning.document.model.Document;
import reasoning.workspace.api.AttachDocumentCommand;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.EmailAnalysisEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.Status;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.analysis.persistence.MailboxEntity;
import verwaltungsassistent.web.analysis.persistence.MailboxRepository;
import verwaltungsassistent.web.geo.GeoPhotoEntity;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import verwaltungsassistent.web.geo.SyntheticPhotoFactory;
import verwaltungsassistent.web.geo.GeoPhotoService.ExtractedGps;
import verwaltungsassistent.web.service.GeoService.GeoAddress;
import verwaltungsassistent.web.service.GeoService.Jurisdiction;
import verwaltungsassistent.web.util.DateTimeFormats;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic demo-data importer for the demo appliance.
 *
 * <p>Concept: persistent demo assets → deterministic demo-data importer →
 * database records → indexing.</p>
 *
 * <ul>
 *   <li>Persistent assets: original document files stay on disk ({@code uploads/}).
 *       Synthetic demo photographs ({@code uploads/photos/}) are regenerated
 *       on every reset so their GPS coordinates and scene labels stay
 *       deterministic and match the current demo data set.</li>
 *   <li>The importer ({@link #reset()}) removes the complete demo database
 *       state (demo users, all incoming e-mails, analyses, photos, mailboxes,
 *       demo workspaces) and recreates it deterministically: 20 demo users,
 *       2 general mailboxes, a controlled Brandenburg-flavored e-mail catalog,
 *       and Geovorgänge derived from the real EXIF-geotagged photographs.</li>
 *   <li>Recreated evidence documents are run through the existing ingestion
 *       pipeline so the reset is complete without a manual re-index.</li>
 * </ul>
 *
 * <p>Running {@code reset()} twice produces essentially the same logical
 * dataset (IDs are generated, contents are fixed).</p>
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);
    public static final String DEMO_PASSWORD = "demo1234";
    public static final String DEMO_EMAIL_SUFFIX = "@verwaltungsassistent.local";
    public static final int DEMO_USER_COUNT = 20;
    public static final String MAILBOX_KONTAKT = "kontakt@verwaltungs-demo.de";
    public static final String MAILBOX_INFO = "info@verwaltungs-demo.de";
    public static final String DEMO_OWNER = "admin@verwaltungsassistent.local";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserAccountRepository userAccountRepository;
    private final WorkspaceService workspaceService;
    private final JpaIncomingEmailRepository incomingEmailRepository;
    private final JpaEmailAnalysisRepository analysisRepository;
    private final MailboxRepository mailboxRepository;
    private final GeoPhotoRepository photoRepository;
    private final GeoPhotoService photoService;
    private final GeoService geoService;
    private final DocumentFacade documentFacade;
    private final DocumentIngestionProcessor ingestionProcessor;
    private final reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository refreshTokenSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Path uploadDir;

    public DemoDataService(UserAccountRepository userAccountRepository,
                           WorkspaceService workspaceService,
                           JpaIncomingEmailRepository incomingEmailRepository,
                           JpaEmailAnalysisRepository analysisRepository,
                           MailboxRepository mailboxRepository,
                           GeoPhotoRepository photoRepository,
                           GeoPhotoService photoService,
                           GeoService geoService,
                           DocumentFacade documentFacade,
                           DocumentIngestionProcessor ingestionProcessor,
                           reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository refreshTokenSessionRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.upload-dir:uploads}") String uploadDirPath) {
        this.userAccountRepository = userAccountRepository;
        this.workspaceService = workspaceService;
        this.incomingEmailRepository = incomingEmailRepository;
        this.analysisRepository = analysisRepository;
        this.mailboxRepository = mailboxRepository;
        this.photoRepository = photoRepository;
        this.photoService = photoService;
        this.geoService = geoService;
        this.documentFacade = documentFacade;
        this.ingestionProcessor = ingestionProcessor;
        this.refreshTokenSessionRepository = refreshTokenSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
    }

    private static String demoEmail(int n) {
        return String.format("demo%02d", n) + DEMO_EMAIL_SUFFIX;
    }

    private static String demoName(int n) {
        String[] first = {"Anna", "Ben", "Clara", "David", "Eva", "Felix", "Greta", "Hannes", "Ida", "Jonas",
                "Karla", "Lukas", "Mira", "Nils", "Olga", "Paul", "Quirin", "Rosa", "Simon", "Tina"};
        String[] last = {"Bergmann", "Cordes", "Dietrich", "Engel", "Fischer", "Grünewald", "Hoffmann",
                "Jansen", "Krüger", "Lange", "Meyer", "Neumann", "Ostermann", "Peters", "Richter",
                "Scholz", "Thiel", "Vogel", "Wagner", "Zimmermann"};
        return first[n % first.length] + " " + last[n % last.length];
    }

    private static String demoDepartment(int n) {
        String[] depts = {"Abt. Bürgerdienste", "Abt. Stadtentwicklung", "Abt. Ordnungsangelegenheiten",
                "Abt. Soziales und Wohnen", "Abt. Finanzen"};
        return depts[n % depts.length];
    }

    // ── Public operations ────────────────────────────────────────────────────

    /**
     * Full deterministic demo rebuild: removes the demo database state and
     * recreates users, mailboxes, the e-mail catalog, the photo records and
     * Geovorgänge from the persistent originals, then triggers indexing.
     * Safe to repeat; never touches persistent upload files.
     */
    @Transactional
    public int reset() {
        cleanupDemoNamespace();
        int created = 0;
        created += createMailboxes();
        for (int i = 1; i <= DEMO_USER_COUNT; i++) {
            created += createDemoUser(i);
        }
        created += createEmailCatalog();
        // GEO-Register immer DETERMINISTISCH NEU aufbauen: alte Foto-Datensätze
        // mit verwaisten Anzeige-Kopien würden sonst den Import überspringen
        // ("Bild nicht anzeigbar") und Geovorgänge fehlen. Nur GEO-Bestand wird
        // entfernt; E-Mails, Analysen, CASE-Vorgänge und Dokumente bleiben
        // unberührt (siehe refreshGeoDemoState).
        created += refreshGeoDemoState();
        created += seedCompletedScenario();
        created += seedPreAnalyzedEmails();
        log.info("Demo-Daten zurückgesetzt: {} Datensätze erzeugt", created);
        return created;
    }

    /**
     * Fokussierte, idempotente GEO-Demo-Auffrischung: entfernt ALLE Geo-Foto-
     * Datensätze und GEO-Vorgänge (nur das Demo-GEO-Register) und importiert
     * Fotos + Geovorgänge deterministisch neu — inklusive mindestens zwei
     * eigener Geovorgänge je Demo-Mitarbeiterin (demo01..demo20). E-Mails,
     * Analysen, CASE-Vorgänge und Wissensdokumente bleiben unberührt.
     *
     * @return Anzahl neu erzeugter Datensätze (Fotos + Geovorgänge)
     */
    @Transactional
    public int refreshGeoDemoState() {
        for (WorkspaceEntity ws : workspaceService.findAll()) {
            if ("GEO".equalsIgnoreCase(ws.getWorkspaceType())) {
                try {
                    workspaceService.deleteWorkspace(ws.getId().toString());
                } catch (Exception e) {
                    log.warn("GEO-Vorgang {} nicht löschbar: {}", ws.getId(), e.getMessage());
                }
            }
        }
        photoRepository.deleteAll();
        int created = importDemoPhotos();
        log.info("GEO-Demo-Register neu aufgebaut (Fotos + >=2 Geovorgänge je Mitarbeiterin).");
        return created;
    }

    /** Kept as an alias — the admin "Demo-Daten erzeugen" quick action calls the same reset. */
    public int generate() {
        return reset();
    }

    /**
     * Allgemeiner Arbeitspool (Phase 2B): admin-owned Vorgänge sind der
     * unzugewiesene, von JEDER Mitarbeiterin sichtbare Arbeitsbestand —
     * Empfehlung + EXPLIZITE Übernahme ("Vorgang übernehmen"), niemals
     * automatische Zuordnung. Abgeschlossene/archivierte Pool-Vorgänge sind
     * von der Empfehlung ausgeschlossen (planningFor liefert für sie null).
     */
    public static boolean isGeneralPoolOwner(String ownerId) {
        // Phase 2C.3b: auch bewusst NICHT zugewiesene Vorgänge (owner null,
        // Mailbox-Intake an die allgemeine Mailbox) gehören zum allgemeinen
        // Arbeitspool — sichtbar für jede Mitarbeiterin, explizite Übernahme.
        // Zusätzlich gelten Vorgänge, deren owner die allgemeine Demo-Mailbox
        // selbst ist (Intake-Variante mit Mailbox-Konto als Zuständige), als
        // Pool — sie sind für keine einzelne Mitarbeiterin "privat".
        return ownerId == null || DEMO_OWNER.equalsIgnoreCase(ownerId)
                || MAILBOX_INFO.equalsIgnoreCase(ownerId)
                || MAILBOX_KONTAKT.equalsIgnoreCase(ownerId);
    }

    /** Sichtbarkeit einer Mitarbeiterin: eigene Vorgänge + allgemeiner Pool. */
    public static boolean isEmployeeVisible(String ownerId, String userEmail) {
        return isGeneralPoolOwner(ownerId)
                || (userEmail != null && userEmail.equalsIgnoreCase(ownerId));
    }

    /** Titel des Demo-Wissensdokuments für Straßenbeleuchtung (Issue 6). */
    public static final String STREET_LIGHTING_DOC_TITLE =
            "Straßenbeleuchtung – Zuständigkeit und Meldung defekter Beleuchtung";

    /**
     * Demo-Wissensdokument "Straßenbeleuchtung" (idempotent): Der Demo-Korpus
     * enthält KEIN Dokument zu Straßen-/Beleuchtungs-Infrastruktur — dadurch
     * liefert die Suche für "defekte Straßenlaterne" nur schwache
     * Nächst-Nachbar-Treffer (z. B. GewO/BMG). Das kleine Wissensdokument
     * schließt diese Lücke; die Indexierung läuft über die bestehende Pipeline
     * (Extract → Chunk → Embed → Qdrant). Existiert bereits ein Dokument mit
     * diesem Titel, wird nichts angelegt.
     */
    @Transactional
    public void seedStreetLightingKnowledge() {
        String title = STREET_LIGHTING_DOC_TITLE;
        try {
            for (int page = 0; ; page++) {
                DocumentFilter filter = new DocumentFilter(null, null, null, null, null, null, null, page, 100);
                var pageDocs = documentFacade.findDocuments(filter);
                if (pageDocs.documents().isEmpty()) {
                    break;
                }
                for (Document doc : pageDocs.documents()) {
                    if (title.equalsIgnoreCase(doc.metadata().title())) {
                        log.info("Demo-Wissensdokument '{}' ist bereits vorhanden", title);
                        return;
                    }
                }
                if (pageDocs.documents().size() < 100) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Existenzprüfung für '{}' fehlgeschlagen: {}", title, e.getMessage());
            return;
        }
        String content = String.join("\n",
                "Straßenbeleuchtung in der Stadtverwaltung – Zuständigkeit und Meldung defekter Straßenlaternen",
                "",
                "Die öffentliche Straßenbeleuchtung ist eine Aufgabe der Stadtverwaltung. Zuständig ist das",
                "Ordnungsamt beziehungsweise der Fachbereich Tiefbau und Verkehr.",
                "",
                "Meldung einer defekten Straßenlaterne:",
                "Eine defekte Straßenlaterne können Bürgerinnen und Bürger über den Mängelmelder der Stadt oder",
                "telefonisch beim Bürgertelefon melden. Bitte geben Sie den Standort der Laterne möglichst genau an,",
                "zum Beispiel Straße und Hausnummer.",
                "",
                "Bearbeitung:",
                "Die Reparatur der Straßenbeleuchtung wird an einen beauftragten Dienstleister vergeben und erfolgt",
                "in der Regel innerhalb weniger Tage. Stellt die defekte Beleuchtung eine Gefahr dar (zum Beispiel",
                "herabhängende Teile), wird die Gefahrenstelle sofort abgesichert.");
        String fileName = "Straßenbeleuchtung-Zustaendigkeit.txt";
        String storageKey = "knowledge/" + fileName;
        try {
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path file = uploadDir.resolve(storageKey);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            String checksum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).toString();
            Document doc = documentFacade.createDocument(new CreateDocumentCommand(
                    title, DocumentFileType.TXT, fileName, "text/plain;charset=UTF-8",
                    bytes.length, "local", storageKey, checksum,
                    "OTHER", Set.of("municipal"), "INTERNAL", DEMO_OWNER, "default"));
            try {
                if (hasActiveIngestionJob(doc.id())) {
                    log.info("Demo-Wissensdokument '{}' wird bereits indexiert — kein zweiter Job (2D.16)", title);
                } else {
                    var job = documentFacade.createIngestionJob(doc.id(), DEMO_OWNER);
                    documentFacade.startIngestion(job.id(), DEMO_OWNER);
                    ingestionProcessor.ingest(doc.id());
                    documentFacade.completeIngestion(job.id(), DEMO_OWNER);
                    log.info("Demo-Wissensdokument '{}' angelegt und indexiert", title);
                }
            } catch (Exception e) {
                log.warn("Demo-Wissensdokument '{}' angelegt, Indexierung fehlgeschlagen: {}", title, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Demo-Wissensdokument '{}' konnte nicht angelegt werden: {}", title, e.getMessage());
        }
    }

    /**
     * Startup seeding: creates the controlled e-mail catalog when the queue
     * is empty (fresh database). Never duplicates an existing queue.
     */
    @Transactional
    public int seedEmailQueueIfEmpty() {
        if (incomingEmailRepository.count() > 0) {
            return 0;
        }
        int created = createMailboxes();
        created += createEmailCatalog();
        created += seedCompletedScenario();
        created += seedPreAnalyzedEmails();
        log.info("Demo-E-Mail-Katalog erzeugt: {} Datensätze", created);
        return created;
    }

    /**
     * Startup demo-state reset: returns every catalog e-mail to its catalog
     * definition (status, assignment, completion timestamps) and clears the
     * analysis link generated by the previous demo run. The e-mail rows
     * themselves are kept (stable ids); only the workflow state is reset.
     * Missing catalog entries are re-created. Idempotent.
     */
    @Transactional
    public int resetEmailQueue() {
        Instant now = Instant.now();
        List<IncomingEmailEntity> existing = new ArrayList<>(incomingEmailRepository.findAll());
        int reset = 0;
        for (SeedEmail s : EMAIL_CATALOG) {
            IncomingEmailEntity email = existing.stream()
                    .filter(e -> s.subject().equals(e.getSubject())
                            && s.senderEmail().equals(e.getSenderEmail()))
                    .findFirst()
                    .orElseGet(() -> {
                        IncomingEmailEntity created = new IncomingEmailEntity(
                                UUID.randomUUID(), s.subject(), s.senderName(), s.senderEmail(), s.body(),
                                now.minus(s.receivedAgoMinutes(), ChronoUnit.MINUTES),
                                recipientAddressedTo(s.recipient()), recipientEmail(s.recipient()));
                        existing.add(created);
                        incomingEmailRepository.save(created);
                        log.info("Demo-E-Mail neu angelegt (Reset): {}", s.subject());
                        return created;
                    });
            // Adressierung an die Katalog-Definition angleichen: Bestands-
            // E-Mails aus früheren Katalogständen können eine abweichende
            // Empfänger-Zuordnung tragen (z. B. allgemeine Mailbox statt der
            // heute vorgesehenen Mitarbeiterin) — der Reset stellt den
            // Katalog-Zustand her (Subjekt+Sender bleiben der Zuordnungsschlüssel).
            email.setAddressedTo(recipientAddressedTo(s.recipient()));
            email.setAddressedToEmail(recipientEmail(s.recipient()));
            applyCatalogState(email, s, now);
            reset++;
        }
        incomingEmailRepository.saveAll(existing);
        log.info("Demo-E-Mail-Warteschlange zurückgesetzt: {} E-Mails im Katalog-Zustand", reset);
        return reset;
    }

    /** Deletes every stored e-mail analysis (previous demo-run products). */
    @Transactional
    public void deleteAllEmailAnalyses() {
        long count = analysisRepository.count();
        if (count == 0) {
            return;
        }
        analysisRepository.deleteAll();
        log.info("E-Mail-Analysen zurückgesetzt: {} Datensätze entfernt", count);
    }

    /**
     * Startup seeding: imports the persistent demo photographs and their
     * Geovorgänge when no photo records exist yet. Bei bereits vorhandenen
     * Datensätzen werden nur die Bilddateien auf den aktuellen ausgelieferten
     * Stand gehoben (z. B. Upgrade von synthetisch auf Archivaufnahmen) —
     * die Datensätze selbst bleiben unverändert.
     */
    @Transactional
    public int importDemoPhotosIfMissing() {
        if (photoRepository.count() > 0) {
            refreshDemoPhotoFiles();
            return 0;
        }
        int created = importDemoPhotos();
        log.info("Demo-Fotos importiert: {} Datensätze", created);
        return created;
    }

    /**
     * Hebt vorhandene Demo-Foto-Dateien auf den aktuellen Stand der
     * mitgelieferten Archivaufnahmen: weicht die Quelldatei vom Asset ab
     * (z. B. noch aus der früheren synthetischen Erzeugung), wird sie
     * ersetzt; die Anzeige-Kopie bestehender Datensätze wird immer auf den
     * aktuellen Stand gebracht (Pixel-Vergleich), damit keine alten Bilder
     * in der UI zurückbleiben.
     */
    private void refreshDemoPhotoFiles() {
        Path imagesDir = uploadDir.resolve("photos");
        LocalDateTime baseCapture = LocalDateTime.of(2026, 8, 20, 9, 0);
        for (SyntheticPhoto spec : BRANDENBURG_PHOTOS) {
            String fileName = spec.fileName();
            try {
                Path file = imagesDir.resolve(fileName);
                byte[] demoBytes = demoPhotoBytes(spec, baseCapture, null);
                if (!Files.exists(file)
                        || !java.util.Arrays.equals(Files.readAllBytes(file), demoBytes)) {
                    Files.write(file, demoBytes);
                    log.info("Demo-Foto aktualisiert (Archivaufnahme): {}", fileName);
                }
                refreshExistingDisplayCopy(fileName, demoBytes);
            } catch (Exception e) {
                log.warn("Demo-Foto '{}' konnte nicht aktualisiert werden: {}", fileName, e.getMessage());
            }
        }
    }

    // ── Mailboxes ────────────────────────────────────────────────────────────

    private int createMailboxes() {
        int created = 0;
        created += saveMailbox(MAILBOX_KONTAKT, "Kontakt Stadtverwaltung",
                "Allgemeine Bürgeranfragen und Anliegen");
        created += saveMailbox(MAILBOX_INFO, "Info Stadtverwaltung",
                "Auskünfte und allgemeine Informationen");
        return created;
    }

    private int saveMailbox(String address, String displayName, String description) {
        if (mailboxRepository.findByAddress(address).isPresent()) {
            return 0;
        }
        mailboxRepository.save(new MailboxEntity(UUID.randomUUID(), address, displayName, description));
        return 1;
    }

    // ── Demo users ───────────────────────────────────────────────────────────

    private int createDemoUser(int i) {
        int created = 0;
        Instant now = Instant.now();
        String email = demoEmail(i);
        String name = demoName(i);
        String[] parts = name.split(" ");
        UserAccountEntity user = new UserAccountEntity(
                email, passwordEncoder.encode(DEMO_PASSWORD), name, Set.of(Role.USER, Role.ANALYST));
        user.setFirstName(parts[0]);
        user.setLastName(parts[1]);
        user.setDepartment(demoDepartment(i));
        user.setOffice("Bürgeramt Potsdam");
        user.setRoom("Raum " + (1 + (i % 12)) + "." + String.format("%02d", i));
        user.setPosition("Sachbearbeitung Bürgerangelegenheiten");
        user.setSalutation(i % 2 == 0 ? "Frau" : "Herr");
        // Telefonnummer aus dem Benutzerprofil — die Signatur- und PDF-Blöcke
        // (BEARBEITET VON) lesen ausschließlich dieses Feld.
        user.setPhone("0331 289-" + (1000 + i));
        if (i % 5 == 0) {
            LocalDate from = LocalDate.now().plusDays(14);
            user.setVacations(List.of(new UserAccountEntity.VacationPeriod(
                    from, from.plusDays(7), "Urlaub", "Demo-Daten")));
        }
        userAccountRepository.save(user);
        created++;

        createDemoCase(user, "Wohngeldantrag " + name + " – Unterlagen prüfen",
                "Eingegangener Wohngeldantrag mit unvollständigen Unterlagen.",
                WorkspaceStatus.ACTIVE, WorkspacePhase.REVIEW, now.minus(3, ChronoUnit.DAYS));
        createDemoCase(user, "Ummeldung " + name + " – Meldeangelegenheit",
                "Ummeldung nach Umzug innerhalb der Stadt.",
                WorkspaceStatus.CLOSED, WorkspacePhase.COMPLETE, now.minus(20, ChronoUnit.DAYS));
        created += 2;
        return created;
    }

    private void createDemoCase(UserAccountEntity owner, String name, String description,
                                WorkspaceStatus status, WorkspacePhase phase, Instant updatedAt) {
        WorkspaceEntity workspace = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(name, description + " (Demo-Daten)", "CASE", owner.getEmail()));
        workspace.setStatus(status);
        workspace.setPhase(phase);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demo", true);
        data.put("citizen", owner.getDisplayName());
        // Auslöser: beide Demo-Fallarten stammen aus einer Bürger-E-Mail —
        // die Fallseite zeigt den Ursprung im Kopf ("Auslöser: E-Mail vom …").
        data.put("source", "E-Mail vom " + DateTimeFormats.formatInstant(
                updatedAt.minus(1, ChronoUnit.DAYS), null));
        try {
            workspace.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            workspace.setPhaseData("{}");
        }
        workspaceService.save(workspace);
    }

    // ── E-mail catalog (deterministic, Brandenburg-flavored) ─────────────────

    /**
     * Abgeschlossene Demo-Szenarien (kohärenter Lebenszyklus): der Bürgerfall
     * ist abgeschlossen, die zugehörige Dankes-/Rückmeldungs-E-Mail ist
     * erledigt und dem abgeschlossenen Vorgang zugeordnet.
     *
     * <p>2D.16 — Kohärenz geschlossener Vorgänge: Jedes Abschluss-Szenario
     * trägt eine ECHTE zuständige Mitarbeiterin (ownerEmail), eine dokumentierte
     * Entscheidung der Sachbearbeitung (inkl. abgeschlossenem Analyse-Lauf,
     * auf den sie sich bezieht), Abschluss-Zeitpunkt/-Person und einen
     * abgeschlossenen COMPLETE-Checklistenstand. Die Bearbeitungshistorie
     * zeigt damit reale Mitarbeiter-Akteure statt „—"-Zeilen, und der
     * Abschluss verletzt keine Entscheidungs-Invariante.</p>
     */
    public static final String CASE_MUELLSAECKE = "Müllsäcke abgeholt";
    public static final String CASE_STRASSENLATERNE = "Straßenlaterne repariert";
    public static final String CASE_TERMIN = "Termin im Bürgeramt wahrgenommen";
    public static final Set<String> COMPLETED_CASE_NAMES = Set.of(
            CASE_MUELLSAECKE, CASE_STRASSENLATERNE, CASE_TERMIN);

    private record CompletedCase(String name, String description, String citizen,
                                 String source, String category, String ownerEmail,
                                 String decisionAnswer) {}

    private static final List<CompletedCase> COMPLETED_CASES = List.of(
            new CompletedCase(CASE_MUELLSAECKE,
                    "Müllablagerung neben der Sammelstelle – Abholung veranlasst und von der Familie bestätigt.",
                    "Familie Nowak", "E-Mail von Familie Nowak (29.08.2026)", "Müllablagerung",
                    "demo02@verwaltungsassistent.local",
                    String.join("\n",
                            "KURZANTWORT",
                            "Die wilde Müllablagerung an der Sammelstelle wurde beseitigt; die Familie Nowak hat die Abholung bestätigt.",
                            "",
                            "ENTSCHEIDUNG",
                            "Der Vorgang ist abgeschlossen: Die Ablagerung wurde über den beauftragten Entsorgungsdienst beseitigt und der Bereich kontrolliert.",
                            "",
                            "VERFAHREN",
                            "Meldung sichten, Entsorgung veranlassen, Rückmeldung der Bürgerin bzw. des Bürgers abwarten und dokumentieren.",
                            "",
                            "NÄCHSTER SCHRITT",
                            "Keine weiteren Schritte — der Vorgang ist abgeschlossen und wird im Aktenbestand dokumentiert.")),
            new CompletedCase(CASE_STRASSENLATERNE,
                    "Defekte Straßenlaterne – Reparatur veranlasst und vom Bürger bestätigt.",
                    "Horst Günther", "E-Mail von Horst Günther (27.08.2026)", "Straßenbeleuchtung",
                    "demo07@verwaltungsassistent.local",
                    String.join("\n",
                            "KURZANTWORT",
                            "Die defekte Straßenlaterne wurde repariert; der Bürger hat die Reparatur bestätigt.",
                            "",
                            "ENTSCHEIDUNG",
                            "Der Vorgang ist abgeschlossen: Die Reparatur der Straßenbeleuchtung wurde beauftragt, ausgeführt und vom Bürger bestätigt.",
                            "",
                            "VERFAHREN",
                            "Meldung sichten, Reparaturauftrag vergeben, Ausführung kontrollieren und die Rückmeldung des Bürgers dokumentieren.",
                            "",
                            "NÄCHSTER SCHRITT",
                            "Keine weiteren Schritte — der Vorgang ist abgeschlossen und wird im Aktenbestand dokumentiert.")),
            new CompletedCase(CASE_TERMIN,
                    "Termin im Bürgeramt wahrgenommen – Unterlagen vollständig, Anliegen erledigt.",
                    "Anke Dörr", "E-Mail von Anke Dörr (24.08.2026)", "Terminanfrage",
                    "demo10@verwaltungsassistent.local",
                    String.join("\n",
                            "KURZANTWORT",
                            "Der Termin im Bürgeramt wurde wahrgenommen; die Unterlagen waren vollständig, das Anliegen ist erledigt.",
                            "",
                            "ENTSCHEIDUNG",
                            "Der Vorgang ist abgeschlossen: Die Bürgerin hat ihren Termin wahrgenommen, alle Unterlagen waren vollständig und das Anliegen wurde erledigt.",
                            "",
                            "VERFAHREN",
                            "Termin koordinieren, Unterlagen prüfen, Anliegen bearbeiten und den Abschluss dokumentieren.",
                            "",
                            "NÄCHSTER SCHRITT",
                            "Keine weiteren Schritte — der Vorgang ist abgeschlossen und wird im Aktenbestand dokumentiert.")));

    private record SeedEmail(String subject, String senderName, String senderEmail, String body,
                             String recipient, int receivedAgoMinutes, Status status,
                             String caseName, String messageId, String inReplyTo, String references) {}

    private static final List<SeedEmail> EMAIL_CATALOG = buildEmailCatalog();

    private static List<SeedEmail> buildEmailCatalog() {
        List<SeedEmail> list = new ArrayList<>();
        // recipient: "kontakt", "info" or a demo user index "demo05"
        list.add(email("Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Erika Schulze", "erika.schulze@example.de",
                "Betreff: Wohngeldantrag\n\nSehr geehrte Damen und Herren,\n\nich habe vor zwei Wochen meinen Antrag auf Wohngeld in der Stadtverwaltung eingereicht. Können Sie bitte prüfen, ob noch Unterlagen fehlen? Mein Mietvertrag und die Einkommensnachweise der letzten drei Monate liegen bei.\n\nMit freundlichen Grüßen\nErika Schulze, Potsdam-Babelsberg",
                "demo01", 120, Status.NEW));
        list.add(email("Ummeldung nach Umzug nach Oranienburg",
                "Thomas Krüger", "thomas.krueger@example.de",
                "Betreff: Ummeldung\n\nGuten Tag,\n\nich bin vor einer Woche nach Oranienburg gezogen. Welche Unterlagen muss ich zur Ummeldung mitbringen und wie schnell muss die Ummeldung erfolgen?\n\nVielen Dank\nThomas Krüger",
                "kontakt", 180, Status.NEW));
        list.add(email("Defekte Straßenlaterne in der Lehnitzer Straße",
                "Frank Behrendt", "frank.behrendt@example.de",
                "Betreff: Straßenbeleuchtung\n\nSehr geehrte Damen und Herren,\n\ndie Straßenlaterne gegenüber der Lehnitzer Straße 12 fällt seit drei Nächten komplett aus. Bitte veranlassen Sie eine Reparatur.\n\nMit freundlichen Grüßen\nFrank Behrendt, Oranienburg",
                "demo02", 300, Status.NEW));
        list.add(email("Müllablagerung am Havelufer in der Altstadt",
                "Sabine Wagner", "sabine.wagner@example.de",
                "Betreff: Müll am Ufer\n\nGuten Tag,\n\nam Havelufer in der Altstadt liegen mehrere Säcke mit Abfall seit über einer Woche. Können Sie das Ordnungsamt veranlassen?\n\nMit freundlichen Grüßen\nSabine Wagner, Brandenburg an der Havel",
                "info", 420, Status.NEW));
        list.add(email("Beschädigtes Verkehrsschild am Bahnhof",
                "Klaus Neumann", "klaus.neumann@example.de",
                "Betreff: Verkehrsschild beschädigt\n\nSehr geehrte Damen und Herren,\n\nam Bahnhof ist ein Verkehrsschild umgeknickt und beschädigt. Die Vorfahrtssituation ist dadurch nicht mehr erkennbar.\n\nMit freundlichen Grüßen\nKlaus Neumann, Brandenburg an der Havel",
                "demo03", 480, Status.NEW));
        list.add(email("Lärmbelästigung durch Gaststätte in der Innenstadt",
                "Martina Sommer", "martina.sommer@example.de",
                "Betreff: Lärmbelästigung\n\nGuten Tag,\n\ndie Gaststätte in meiner Nachbarschaft spielt bis nach Mitternacht Musik im Biergarten. Wir bitten um Prüfung der Lärmschutzregelung.\n\nMit freundlichen Grüßen\nMartina Sommer, Potsdam",
                "kontakt", 600, Status.NEW));
        list.add(email("Behindertenparkplatz zugeparkt",
                "Jörg Hoffmann", "joerg.hoffmann@example.de",
                "Betreff: Parken auf dem Gehweg\n\nSehr geehrte Damen und Herren,\n\nvor dem Rathaus wird der Behindertenparkplatz regelmäßig von einem weißen Transporter zugeparkt. Bitte veranlassen Sie eine Kontrolle.\n\nMit freundlichen Grüßen\nJörg Hoffmann",
                "demo04", 720, Status.NEW));
        list.add(email("Kita-Platz für mein Kind – Anmeldung",
                "Nicole Winter", "nicole.winter@example.de",
                "Betreff: Kita-Anmeldung\n\nGuten Tag,\n\nmein Sohn wird im Januar drei Jahre alt. Wie melde ich ihn für einen Kita-Platz an und welche Unterlagen werden benötigt?\n\nMit freundlichen Grüßen\nNicole Winter, Werder (Havel)",
                "kontakt", 840, Status.NEW));
        list.add(email("Bauanfrage: Garage in Werder (Havel)",
                "Andreas Vogt", "andreas.vogt@example.de",
                "Betreff: Bauanfrage Garage\n\nSehr geehrte Damen und Herren,\n\nich möchte auf meinem Grundstück eine Garage bauen. Ist dafür eine Baugenehmigung erforderlich und welche Unterlagen brauche ich?\n\nMit freundlichen Grüßen\nAndreas Vogt",
                "demo05", 1080, Status.NEW));
        list.add(email("Hundesteuer – Anmeldung eines Hundes",
                "Petra Braun", "petra.braun@example.de",
                "Betreff: Hundesteuer\n\nGuten Tag,\n\nwir haben einen Hund aus dem Tierheim aufgenommen. Wie melde ich die Hundehaltung an und wo bekomme ich die Steuermarke?\n\nMit freundlichen Grüßen\nPetra Braun, Teltow",
                "info", 1200, Status.NEW));
        list.add(email("Straßenschaden in der Zeppelinstraße",
                "Michael Kruse", "michael.kruse@example.de",
                "Betreff: Straßenschaden\n\nSehr geehrte Damen und Herren,\n\nin der Zeppelinstraße ist ein Schlagloch entstanden, das für Radfahrer gefährlich ist. Bitte um Reparatur.\n\nMit freundlichen Grüßen\nMichael Kruse, Potsdam",
                "demo06", 1320, Status.NEW));
        list.add(email("Termin im Bürgeramt – online nicht buchbar",
                "Katrin Vogel", "katrin.vogel@example.de",
                "Betreff: Termin Bürgeramt\n\nGuten Tag,\n\nfür die Verlängerung meines Personalausweises finde ich online keinen Termin. Gibt es eine Warteliste?\n\nMit freundlichen Grüßen\nKatrin Vogel",
                "kontakt", 1560, Status.NEW));
        list.add(email("Parkverbotsschild beschädigt – Teltow",
                "Stefan Wolf", "stefan.wolf@example.de",
                "Betreff: Verkehrsschild\n\nSehr geehrte Damen und Herren,\n\ndas Parkverbotsschild in der Rathausstraße ist angefahren und hängt schief. Bitte um Ausbesserung.\n\nMit freundlichen Grüßen\nStefan Wolf, Teltow",
                "demo07", 1800, Status.NEW));
        list.add(email("Gewerbeanmeldung in Falkensee",
                "Daniel Roth", "daniel.roth@example.de",
                "Betreff: Gewerbeanmeldung\n\nGuten Tag,\n\nich möchte zum 1. Oktober ein kleines Gewerbe anmelden. Welche Unterlagen benötige ich und kann ich den Termin online vereinbaren?\n\nViele Grüße\nDaniel Roth, Falkensee",
                "info", 2040, Status.NEW));
        list.add(email("Wasserrohrbruch – feuchter Keller",
                "Heike Brandt", "heike.brandt@example.de",
                "Betreff: Wasserrohrbruch\n\nSehr geehrte Damen und Herren,\n\nin der Breiten Straße tritt Wasser aus dem Gehweg, unser Keller ist feucht. Bitte prüfen Sie die Wasserleitung.\n\nMit freundlichen Grüßen\nHeike Brandt",
                "demo08", 2280, Status.NEW));
        list.add(email("Fahrradfund – welches Amt ist zuständig?",
                "Christoph Lang", "christoph.lang@example.de",
                "Betreff: Fundfahrrad\n\nGuten Tag,\n\nich habe ein Fahrrad gefunden und möchte es abgeben. Wo ist das Fundbüro und was muss ich beachten?\n\nMit freundlichen Grüßen\nChristoph Lang",
                "kontakt", 2520, Status.NEW));
        list.add(email("Sachstand zu meinem Bauantrag in Bernau",
                "Monika Krause", "monika.krause@example.de",
                "Betreff: Sachstand Bauantrag\n\nSehr geehrte Damen und Herren,\n\nkönnen Sie mir den Bearbeitungsstand meines Bauantrags (Carport) mitteilen? Mein Aktenzeichen lautet 2026-0881-B.\n\nMit freundlichen Grüßen\nMonika Krause, Bernau bei Berlin",
                "demo09", 2760, Status.NEW));
        list.add(email("Zaun am Spielplatz beschädigt",
                "Ralf Berger", "ralf.berger@example.de",
                "Betreff: Spielplatz\n\nGuten Tag,\n\nam Spielplatz in der Lindenstraße ist der Zaun umgefallen. Bitte veranlassen Sie eine Reparatur.\n\nMit freundlichen Grüßen\nRalf Berger, Königs Wusterhausen",
                "info", 3000, Status.NEW));
        list.add(email("Wohnmobil parkt seit Wochen im Wohngebiet",
                "Ines Fuchs", "ines.fuchs@example.de",
                "Betreff: Ruhender Verkehr\n\nSehr geehrte Damen und Herren,\n\nvor unserem Haus parkt seit Wochen ein Wohnmobil. Es versperrt die Sicht und nimmt dauerhaft den Parkplatz ein.\n\nMit freundlichen Grüßen\nInes Fuchs",
                "demo10", 3240, Status.NEW));
        list.add(email("Personalausweis für mein Kind – was mitbringen?",
                "Alexander Hartmann", "alexander.hartmann@example.de",
                "Betreff: Personalausweis Kind\n\nGuten Tag,\n\nmein Sohn braucht einen Personalausweis. Welche Unterlagen müssen wir mitbringen und muss mein Mann zustimmen?\n\nMit freundlichen Grüßen\nAlexander Hartmann",
                "kontakt", 3480, Status.NEW));
        list.add(email("Baulärm am Wochenende – Baustelle läuft",
                "Birgit Lorenz", "birgit.lorenz@example.de",
                "Betreff: Baulärm\n\nSehr geehrte Damen und Herren,\n\nauf der Baustelle in der Hauptstraße wird auch sonntags gearbeitet. Ist das mit der Baugenehmigung vereinbar?\n\nMit freundlichen Grüßen\nBirgit Lorenz",
                "demo11", 3720, Status.NEW));
        list.add(email("Öffentliche Toilette am Strandbad defekt",
                "Markus Klein", "markus.klein@example.de",
                "Betreff: Öffentliche Toilette\n\nGuten Tag,\n\ndie öffentliche Toilette am Strandbad ist seit Tagen verschlossen und verschmutzt. Bitte um Reinigung.\n\nMit freundlichen Grüßen\nMarkus Klein",
                "info", 3960, Status.NEW));
        list.add(email("Baum verdeckt Verkehrsschild",
                "Sylvia Arnold", "sylvia.arnold@example.de",
                "Betreff: Verkehrsschild zugewachsen\n\nSehr geehrte Damen und Herren,\n\nvor dem Kindergarten wird das Tempo-30-Schild durch einen Baum verdeckt. Bitte um Rückschnitt.\n\nMit freundlichen Grüßen\nSylvia Arnold",
                "demo12", 4200, Status.NEW));
        list.add(email("Auskunft zu meinem Grundstück – Kataster",
                "Peter Falk", "peter.falk@example.de",
                "Betreff: Katasterauskunft\n\nGuten Tag,\n\nich benötige eine Auskunft zu meinem Grundstück aus dem Liegenschaftskataster. Wie stelle ich den Antrag?\n\nMit freundlichen Grüßen\nPeter Falk",
                "kontakt", 4440, Status.NEW));
        list.add(email("Wohngeld – Einkommensnachweis nachgereicht",
                "Gisela Maier", "gisela.maier@example.de",
                "Betreff: Wohngeld Nachreichung\n\nSehr geehrte Damen und Herren,\n\nwie besprochen reiche ich den Einkommensnachweis meines Arbeitgebers nach. Bitte um Bestätigung des Eingangs.\n\nMit freundlichen Grüßen\nGisela Maier",
                "demo13", 1800, Status.IN_PROGRESS));
        list.add(email("Ummeldung – Wohnungsgeberbestätigung",
                "Uwe Sommer", "uwe.sommer@example.de",
                "Betreff: Ummeldung Unterlagen\n\nGuten Tag,\n\nmeine Wohnungsgeberbestätigung ist gerade eingetroffen – ich hänge sie an. Bitte um Prüfung meiner Ummeldung.\n\nMit freundlichen Grüßen\nUwe Sommer",
                "kontakt", 1200, Status.IN_PROGRESS));
        // Abgeschlossene Szenarien: Erledigt-E-Mails mit kohärenter Vorgangs-
        // zuordnung (caseName → abgeschlossener Vorgang aus COMPLETED_CASES).
        // Der Müllsäcke-Thread trägt echte Threading-Header (messageId /
        // inReplyTo) — die Folge-E-Mail unten referenziert ihn über
        // References, damit die Header-basierte Thread-/Fall-Erkennung
        // demonstrierbar ist (Phase 2C.1).
        list.add(email("Müllsäcke vor der Sammelstelle",
                "Familie Nowak", "familie.nowak@example.de",
                "Betreff: Müllsäcke vor der Sammelstelle\n\nSehr geehrte Damen und Herren,\n\nvor der Sammelstelle in der Lindenstraße liegen seit Tagen mehrere Müllsäcke. Bitte veranlassen Sie die Abholung.\n\nMit freundlichen Grüßen\nFamilie Nowak",
                "info", 7200, Status.COMPLETED, CASE_MUELLSAECKE,
                "msg-muell-2026-08-29", null, null));
        list.add(email("Danke – Müllsäcke abgeholt",
                "Familie Nowak", "familie.nowak@example.de",
                "Betreff: Müllabholung\n\nSehr geehrte Damen und Herren,\n\nvielen Dank, die Müllsäcke wurden abgeholt. Das war eine schnelle Lösung.\n\nMit freundlichen Grüßen\nFamilie Nowak",
                "info", 4320, Status.COMPLETED, CASE_MUELLSAECKE,
                "msg-muell-2026-09-01", "msg-muell-2026-08-29", null));
        // Folge-E-Mail (Phase 2C.1, Szenario C): neue Meldung derselben Familie
        // über eine ZWEITE Absender-Adresse — die Thread-Zugehörigkeit wird
        // über den References-Header erkannt (nicht über die Adresse).
        list.add(email("Müllsäcke erneut an der Sammelstelle",
                "Familie Nowak", "nowak.familie@example.de",
                "Betreff: Müllsäcke erneut\n\nSehr geehrte Damen und Herren,\n\nan der Sammelstelle in der Lindenstraße liegen erneut Müllsäcke. Bitte veranlassen Sie die Abholung.\n\nMit freundlichen Grüßen\nFamilie Nowak",
                "info", 180, Status.NEW, null,
                "msg-muell-2026-09-01b", null, "msg-muell-2026-08-29"));
        list.add(email("Straßenlaterne repariert – Rückmeldung",
                "Horst Günther", "horst.guenther@example.de",
                "Betreff: Rückmeldung Straßenlaterne\n\nGuten Tag,\n\nvielen Dank für die schnelle Reparatur der Straßenlaterne. Alles funktioniert wieder.\n\nMit freundlichen Grüßen\nHorst Günther",
                "demo14", 5760, Status.COMPLETED, CASE_STRASSENLATERNE));
        list.add(email("Termin im Bürgeramt wahrgenommen",
                "Anke Dörr", "anke.doerr@example.de",
                "Betreff: Termin wahrgenommen\n\nSehr geehrte Damen und Herren,\n\nmeinen Termin im Bürgeramt habe ich wahrgenommen und alle Unterlagen sind vollständig. Vielen Dank.\n\nMit freundlichen Grüßen\nAnke Dörr",
                "kontakt", 7200, Status.COMPLETED, CASE_TERMIN));
        list.add(email("Verkehrsschild am Bahnhof Oranienburg umgefahren",
                "Paul Krüger", "paul.krueger@example.de",
                "Betreff: Verkehrsschild umgefahren\n\nGuten Tag,\n\nam Bahnhofsvorplatz ist ein Verkehrsschild umgefahren worden. Die Halteverbotszone ist nicht mehr erkennbar.\n\nMit freundlichen Grüßen\nPaul Krüger, Oranienburg",
                "demo15", 5400, Status.NEW));
        return list;
    }

    private static SeedEmail email(String subject, String senderName, String senderEmail, String body,
                                   String recipient, int receivedAgoMinutes, Status status) {
        return new SeedEmail(subject, senderName, senderEmail, body, recipient, receivedAgoMinutes, status,
                null, null, null, null);
    }

    private static SeedEmail email(String subject, String senderName, String senderEmail, String body,
                                   String recipient, int receivedAgoMinutes, Status status,
                                   String caseName) {
        return new SeedEmail(subject, senderName, senderEmail, body, recipient, receivedAgoMinutes, status,
                caseName, null, null, null);
    }

    private static SeedEmail email(String subject, String senderName, String senderEmail, String body,
                                   String recipient, int receivedAgoMinutes, Status status,
                                   String caseName, String messageId, String inReplyTo, String references) {
        return new SeedEmail(subject, senderName, senderEmail, body, recipient, receivedAgoMinutes, status,
                caseName, messageId, inReplyTo, references);
    }

    private int createEmailCatalog() {
        int created = 0;
        Instant now = Instant.now();
        for (SeedEmail s : EMAIL_CATALOG) {
            IncomingEmailEntity entity = new IncomingEmailEntity(
                    UUID.randomUUID(), s.subject(), s.senderName(), s.senderEmail(), s.body(),
                    now.minus(s.receivedAgoMinutes(), ChronoUnit.MINUTES),
                    recipientAddressedTo(s.recipient()), recipientEmail(s.recipient()));
            applyCatalogState(entity, s, now);
            incomingEmailRepository.save(entity);
            created++;
        }
        return created;
    }

    private static AddressedTo recipientAddressedTo(String recipient) {
        if ("kontakt".equals(recipient) || "info".equals(recipient)) {
            return AddressedTo.GENERAL;
        }
        return AddressedTo.EMPLOYEE;
    }

    private String recipientEmail(String recipient) {
        if ("kontakt".equals(recipient)) {
            return MAILBOX_KONTAKT;
        }
        if ("info".equals(recipient)) {
            return MAILBOX_INFO;
        }
        return demoEmail(Integer.parseInt(recipient.replace("demo", "")));
    }

    /**
     * Kohärente Abschluss-Szenarien (idempotent): legt die abgeschlossenen
     * Vorgänge an (falls fehlend), ordnet die Erledigt-Katalog-E-Mails ihrem
     * Vorgang zu ({@code workspaceId}) und hinterlegt je E-Mail ein
     * deterministisches Vor-Analyse-Ergebnis ({@code analysisId}), damit eine
     * erledigte E-Mail einen nachvollziehbaren Bearbeitungsstand zeigt, statt
     * wie eine unverarbeitete zu wirken. Das Vor-Analyse-Ergebnis ist ein
     * KI-freier, plausibler Katalog-Befund (gleiche Struktur wie ein echter
     * Analyse-Lauf — keine erfundenen KI-Felder).
     */
    @Transactional
    public int seedCompletedScenario() {
        int created = 0;
        try {
            Map<String, WorkspaceEntity> byName = new LinkedHashMap<>();
            for (WorkspaceEntity ws : workspaceService.findAll()) {
                byName.putIfAbsent(normalizeName(ws.getName()), ws);
            }
            for (CompletedCase c : COMPLETED_CASES) {
                WorkspaceEntity ws = byName.get(normalizeName(c.name()));
                if (ws == null) {
                    // Phase 2C.5: abgeschlossene Pool-Szenario-Vorgänge sind
                    // unzugewiesen (owner null) — das Leitungs-Konto ist keine
                    // operative Zuständigkeit und kein Vorgangs-Eigentümer.
                    ws = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                            c.name(), c.description(), "CASE", null));
                    byName.put(normalizeName(c.name()), ws);
                    created++;
                } else if (DEMO_OWNER.equalsIgnoreCase(ws.getOwnerId())) {
                    // Alt-Daten (Pool-Marker admin@verwaltungsassistent.local) auf den
                    // Pool-Zustand heben: unzugewiesen.
                    ws.setOwnerId(null);
                }
                ws.setStatus(WorkspaceStatus.CLOSED);
                ws.setPhase(WorkspacePhase.COMPLETE);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("demo", true);
                data.put("citizen", c.citizen());
                data.put("source", c.source());
                data.put("caseCategory", c.category());
                try {
                    ws.setPhaseData(MAPPER.writeValueAsString(data));
                } catch (Exception e) {
                    ws.setPhaseData("{}");
                }
                workspaceService.save(ws);
            }
            for (SeedEmail s : EMAIL_CATALOG) {
                if (s.caseName() == null) {
                    continue;
                }
                WorkspaceEntity caseWs = byName.get(normalizeName(s.caseName()));
                if (caseWs == null) {
                    continue;
                }
                for (IncomingEmailEntity email : incomingEmailRepository.findAll()) {
                    if (!s.subject().equals(email.getSubject())
                            || !s.senderEmail().equals(email.getSenderEmail())) {
                        continue;
                    }
                    email.setWorkspaceId(UUID.fromString(caseWs.getId()));
                    if (email.getAnalysisId() == null) {
                        email.setAnalysisId(preAnalysisFor(email, caseWs));
                        created++;
                    }
                    incomingEmailRepository.save(email);
                }
            }
            // Auslöser-Nachricht je Vorgang (Phase 2C.1): die älteste zugeordnete
            // E-Mail ist die ursprüngliche Meldung — der Kommunikationsverlauf
            // des Falls markiert sie als "Auslöser" (sourceEmailId = Analyse-Id).
            for (CompletedCase c : COMPLETED_CASES) {
                WorkspaceEntity caseWs = byName.get(normalizeName(c.name()));
                if (caseWs == null) {
                    continue;
                }
                IncomingEmailEntity oldest = null;
                for (IncomingEmailEntity e : incomingEmailRepository.findAll()) {
                    if (e.getWorkspaceId() == null
                            || !caseWs.getId().equals(e.getWorkspaceId().toString())) {
                        continue;
                    }
                    if (oldest == null || (e.getReceivedAt() != null
                            && (oldest.getReceivedAt() == null
                                    || e.getReceivedAt().isBefore(oldest.getReceivedAt())))) {
                        oldest = e;
                    }
                }
                if (oldest != null && oldest.getAnalysisId() != null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("demo", true);
                    data.put("citizen", c.citizen());
                    data.put("source", c.source());
                    data.put("caseCategory", c.category());
                    data.put("sourceEmailId", oldest.getAnalysisId().toString());
                    data.put("sourceEmailSubject", oldest.getSubject());
                    data.put("sourceEmailAt", oldest.getReceivedAt() != null
                            ? oldest.getReceivedAt().toString() : null);
                    try {
                        caseWs.setPhaseData(MAPPER.writeValueAsString(data));
                    } catch (Exception e) {
                        caseWs.setPhaseData("{}");
                    }
                    workspaceService.save(caseWs);
                }
            }
            // 2D.16 — Kohärenter Endzustand je Abschluss-Szenario: echte
            // zuständige Mitarbeiterin, dokumentierte Entscheidung (inkl.
            // Analyse-Lauf), Abschluss-Zeitpunkt/-Person und abgeschlossene
            // COMPLETE-Checkliste. Konvergiert auch bereits vorhandene
            // Alt-Vorgänge (idempotent).
            for (CompletedCase c : COMPLETED_CASES) {
                WorkspaceEntity caseWs = byName.get(normalizeName(c.name()));
                if (caseWs == null) {
                    continue;
                }
                try {
                    if (ensureCompletedScenarioEndState(caseWs, c)) {
                        created++;
                    }
                } catch (Exception e) {
                    log.warn("Abschluss-Endzustand für '{}' fehlgeschlagen: {}", c.name(), e.getMessage());
                }
            }
            log.info("Demo-Abschluss-Szenarien kohärent: {} Vorgänge, Erledigt-E-Mails zugeordnet", created);
        } catch (Exception e) {
            log.warn("Abschluss-Szenarien fehlgeschlagen: {}", e.getMessage());
        }
        return created;
    }

    /**
     * Bringt ein Abschluss-Szenario in den kohärenten Endzustand (2D.16):
     * <ul>
     *   <li>zuständige Mitarbeiterin als Eigentümerin (nie das Leitungs-Konto);</li>
     *   <li>abgeschlossener Analyse-Lauf (Fixture) mit strukturiertem Ergebnis
     *       — die Entscheidungsseite bleibt damit auch nach Abschluss
     *       ansehbar (gleiche Mechanik wie der abgeschlossene Demo-Benutzerfall);</li>
     *   <li>dokumentierte Entscheidung der Sachbearbeitung (phaseData.decision,
     *       Bezug auf die Analyse-Version) — Abschluss-Invariante
     *       „Geschlossen ⇒ Entscheidung dokumentiert" ist erfüllt;</li>
     *   <li>closedAt/closedBy (gleiche Form wie {@code CaseClosureService});</li>
     *   <li>persistierte COMPLETE-Checkliste (3/3 erledigt).</li>
     * </ul>
     * Es werden keine KI-Läufe ausgeführt und keine Belege erfunden: Der
     * Analyse-Lauf ist ein deterministischer Fixture-Befund des Szenarios;
     * Entscheidung und Abschluss tragen die echte Mitarbeiterin als Akteurin.
     */
    private boolean ensureCompletedScenarioEndState(WorkspaceEntity ws, CompletedCase c) {
        boolean changed = false;
        String ownerEmail = c.ownerEmail();
        if (ownerEmail != null && !ownerEmail.equalsIgnoreCase(ws.getOwnerId())) {
            ws.setOwnerId(ownerEmail);
            changed = true;
        }
        ws.setStatus(WorkspaceStatus.CLOSED);
        ws.setPhase(WorkspacePhase.COMPLETE);

        // Analyse-Lauf (Fixture) nur anlegen, wenn noch keiner existiert.
        if (workspaceService.latestCompletedAnalysisRun(ws.getId()).isEmpty()) {
            String actor = ownerEmail != null ? ownerEmail : "system";
            int version = workspaceService.startAnalysisRun(ws.getId(), actor);
            workspaceService.completeAnalysisRun(ws.getId(), version, 0, List.of(),
                    completedScenarioAnalysisResult(ws, c));
            workspaceService.recordAnalysisStatus(ws.getId(), "COMPLETED", Map.of("sourceCount", 0));
            changed = true;
            log.info("Abschluss-Szenario '{}': Fixture-Analyse (v{}) hinterlegt", c.name(), version);
        }
        // Lauf ohne abrufbares Ergebnis nachziehen (Alt-Daten).
        workspaceService.latestCompletedAnalysisRun(ws.getId()).ifPresent(run -> {
            if (workspaceService.deserializeAnalysisResult(run) == null) {
                try {
                    workspaceService.completeAnalysisRun(ws.getId(), run.getVersion(), 0,
                            List.of(), completedScenarioAnalysisResult(ws, c));
                } catch (Exception e) {
                    log.warn("Fixture-Ergebnis für '{}' nicht nachziehbar: {}", c.name(), e.getMessage());
                }
            }
        });

        Map<String, Object> data = new LinkedHashMap<>(ws.getPhaseDataMap());
        data.put("demo", true);
        data.put("citizen", c.citizen());
        data.put("source", c.source());
        data.put("caseCategory", c.category());
        if (ownerEmail != null) {
            data.put("closedAt", java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .format(java.time.LocalDateTime.now()));
            data.put("closedBy", ownerEmail);
        }
        Integer analysisVersion = workspaceService.latestCompletedAnalysisRun(ws.getId())
                .map(run -> run.getVersion()).orElse(null);
        if (analysisVersion != null && data.get("decision") == null) {
            String display = displayNameOf(ownerEmail);
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("confirmedBy", ownerEmail != null ? ownerEmail : "system");
            decision.put("confirmedByName", display != null ? display : ownerEmail);
            decision.put("confirmedAt", java.time.Instant.now().toString());
            decision.put("analysisVersion", analysisVersion);
            data.put("decision", decision);
        }
        List<Map<String, Object>> checklist = new ArrayList<>();
        String phaseLabel = "Abschluss";
        checklist.add(new LinkedHashMap<>(Map.of("id", "comp-1", "label", "Fall abgeschlossen",
                "phase", phaseLabel, "completed", true, "notRequired", false)));
        checklist.add(new LinkedHashMap<>(Map.of("id", "comp-2", "label", "Dokumente archiviert",
                "phase", phaseLabel, "completed", true, "notRequired", false)));
        checklist.add(new LinkedHashMap<>(Map.of("id", "comp-3", "label", "Abschlussbericht erstellt",
                "phase", phaseLabel, "completed", true, "notRequired", false)));
        data.put("checklist", checklist);
        try {
            ws.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            ws.setPhaseData("{}");
        }
        workspaceService.save(ws);

        // Verlaufseinträge (idempotent): Entscheidung dokumentiert + Fall
        // geschlossen — Beschreibungs-Konvention wie der echte Fluss.
        try {
            if (data.get("decision") != null
                    && workspaceService.getTimeline(ws.getId()).stream()
                            .noneMatch(ev -> "Entscheidung dokumentiert".equals(ev.getTitle()))) {
                workspaceService.addTimelineEvent(ws.getId(), java.time.LocalDate.now(),
                        "Entscheidung dokumentiert",
                        "Entscheidung dokumentiert durch " + displayNameOf(ownerEmail)
                                + " auf Grundlage der Analyse #" + analysisVersion + ".",
                        reasoning.workspace.model.TimelineEventType.DECISION,
                        null, 1.0, false);
                changed = true;
            }
            if (workspaceService.getTimeline(ws.getId()).stream()
                    .noneMatch(ev -> "Fall geschlossen".equals(ev.getTitle()))) {
                workspaceService.addTimelineEvent(ws.getId(), java.time.LocalDate.now(),
                        "Fall geschlossen",
                        "Fall geschlossen durch " + displayNameOf(ownerEmail) + ".",
                        reasoning.workspace.model.TimelineEventType.CHANGE,
                        null, 1.0, false);
                changed = true;
            }
        } catch (Exception e) {
            log.warn("Verlaufseinträge für '{}' nicht möglich", c.name(), e);
        }
        return changed;
    }

    /** Anzeigename eines Demo-Kontos (echte Stammdaten), sonst die E-Mail-Adresse. */
    private String displayNameOf(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            return userAccountRepository.findByEmail(email.toLowerCase())
                    .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : email)
                    .orElse(email);
        } catch (Exception e) {
            return email;
        }
    }

    /**
     * Deterministisches Analyse-Fixture für ein Abschluss-Szenario (kein LLM):
     * gleiche Ergebnisstruktur wie ein echter Analyse-Lauf, damit die
     * Entscheidungsseite nach Abschluss lesbar bleibt. Es werden keine Belege
     * oder Rechtsgrundlagen erfunden — das Ergebnis ist ein Bearbeitungsbefund
     * des Vorgangs (0 Vorgangsdokumente).
     */
    private Map<String, Object> completedScenarioAnalysisResult(WorkspaceEntity ws, CompletedCase c) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", ws.getId());
        result.put("caseName", c.name());
        result.put("analysisQuestion", "Analysiere den Vorgang \"" + c.name() + "\".");
        result.put("asOf", java.time.LocalDate.now().toString());
        result.put("decisionAnswer", c.decisionAnswer());
        result.put("grounded", Boolean.FALSE);
        result.put("model", "qwen2.5:14b");
        result.put("strategy", "Hybride Suche");
        String now = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .format(java.time.LocalDateTime.now());
        result.put("requestedAt", now);
        result.put("completedAt", now);
        result.put("confidenceScore", "72%");
        Map<String, Object> confidence = new LinkedHashMap<>();
        confidence.put("sourceConfidence", 0.0);
        confidence.put("semanticConfidence", 0.72);
        confidence.put("structuralConfidence", 0.0);
        confidence.put("completenessConfidence", 0.75);
        confidence.put("overallConfidence", 0.72);
        confidence.put("explanation", "Der Bearbeitungsbefund stützt sich auf den Vorgang selbst; "
                + "es liegen keine indexierten Vorgangsdokumente vor.");
        result.put("confidence", confidence);
        result.put("evidenceItems", List.of());
        result.put("authorities", List.of());
        result.put("primaryFindings", List.of(
                findingOf("Anliegen abschließend bearbeitet",
                        "Das Anliegen wurde vollständig bearbeitet und abgeschlossen.")));
        result.put("secondaryFindings", List.of());
        result.put("proceduralFindings", List.of(
                findingOf("Abschluss dokumentieren",
                        "Der abgeschlossene Vorgang ist im Aktenbestand dokumentiert.")));
        result.put("supportingFindings", List.of());
        result.put("findingRelationships", List.of());
        result.put("coverageScore", "72%");
        result.put("coverageScoreRaw", 0.72);
        result.put("coverageIssues", List.of());
        result.put("presentRoles", List.of());
        result.put("missingRoles", List.of());
        result.put("missingDocs", List.of());
        result.put("assessmentNote", "Der Vorgang ist vollständig bearbeitet und abgeschlossen.");
        result.put("analysisComplete", true);
        return result;
    }

    private static Map<String, Object> findingOf(String label, String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("description", description);
        f.put("priority", "80%");
        f.put("priorityRaw", 0.8);
        f.put("role", "Kernfeststellung");
        f.put("fillClass", "quality-indicator__fill--high");
        f.put("governingRefs", List.of());
        f.put("relatedRefs", List.of());
        f.put("evidenceLinks", List.of());
        return f;
    }

    /**
     * Deterministisches Vor-Analyse-Ergebnis einer Erledigt-Katalog-E-Mail:
     * bestätigt den zugehörigen abgeschlossenen Vorgang, ohne einen
     * KI-Lauf vorzutäuschen (kein LLM, keine erfundenen Belege).
     */
    private UUID preAnalysisFor(IncomingEmailEntity email, WorkspaceEntity caseWs) {
        try {
            EmailAnalysisEntity analysis = new EmailAnalysisEntity(
                    UUID.randomUUID(),
                    email.getAssignedTo() != null ? email.getAssignedTo() : DEMO_OWNER,
                    email.getText(), email.getSubject(), "Allgemeines Anliegen",
                    null, null, Instant.now());
            List<verwaltungsassistent.web.controller.EmailController.Step> steps = List.of(
                    new verwaltungsassistent.web.controller.EmailController.Step(1,
                            "Vorgang öffnen",
                            "Die E-Mail gehört zum abgeschlossenen Vorgang „" + caseWs.getName() + "“.",
                            "Vorgang öffnen", "/cases/" + caseWs.getId(), true),
                    new verwaltungsassistent.web.controller.EmailController.Step(2,
                            "Bearbeitung bestätigen",
                            "Die E-Mail ist als erledigt dokumentiert; kein weiterer Arbeitsschritt offen.",
                            null, null, true));
            verwaltungsassistent.web.controller.EmailController.EmailOutcome outcome =
                    new verwaltungsassistent.web.controller.EmailController.EmailOutcome(
                            email.getSubject(), "Allgemeines Anliegen", null, null,
                            List.of(), List.of(), List.of(), steps,
                            null, null, null, List.of());
            analysis.setResultJson(MAPPER.writeValueAsString(outcome));
            analysisRepository.save(analysis);
            return analysis.getId();
        } catch (Exception e) {
            log.warn("Vor-Analyse für Erledigt-E-Mail '{}' fehlgeschlagen: {}", email.getSubject(), e.getMessage());
            return null;
        }
    }

    /**
     * Voranalysierte Neueingänge (Phase 2C.1, Szenarien A + D): ausgewählte
     * Katalog-Neueingänge erhalten ein deterministisches Vor-Analyse-Ergebnis,
     * als kämen sie aus einer automatischen Mailbox-Zustellung. Szenario A:
     * neues Anliegen ohne Falltreffer (→ "Vorgang aus E-Mail anlegen").
     * Szenario D: unsicherer Falltreffer (SIMILAR) — die Mitarbeiterin
     * bestätigt oder ändert die Zuordnung ausdrücklich, nichts wird
     * stillschweigend zugeordnet. Kein LLM-Lauf für die Demo-Daten.
     */
    @Transactional
    public int seedPreAnalyzedEmails() {
        int created = 0;
        try {
            List<WorkspaceEntity> workspaces = new ArrayList<>(workspaceService.findAll());
            for (PreAnalyzedEmail spec : PRE_ANALYZED_EMAILS) {
                for (IncomingEmailEntity email : incomingEmailRepository.findAll()) {
                    if (!spec.subject().equals(email.getSubject())
                            || !spec.senderEmail().equals(email.getSenderEmail())) {
                        continue;
                    }
                    if (email.getAnalysisId() != null) {
                        continue; // idempotent
                    }
                    WorkspaceEntity candidate = null;
                    if (spec.uncertainMatch()) {
                        // Szenario-D-Kandidat: ein Wohngeld-Vorgang — bevorzugt
                        // der der adressierten Mitarbeiterin (die Zuordnung bleibt
                        // ein VORSCHLAG; die Zugriffsregeln gelten weiterhin).
                        String recipient = email.getAddressedToEmail();
                        candidate = workspaces.stream()
                                .filter(ws -> ws.getName() != null
                                        && normalizeName(ws.getName()).contains("wohngeld")
                                        && recipient != null && recipient.equalsIgnoreCase(ws.getOwnerId()))
                                .findFirst()
                                .orElseGet(() -> workspaces.stream()
                                        .filter(ws -> ws.getName() != null
                                                && normalizeName(ws.getName()).contains("wohngeld"))
                                        .findFirst().orElse(null));
                    }
                    email.setAnalysisId(preAnalysisForScenario(email, spec.topic(), candidate));
                    incomingEmailRepository.save(email);
                    created++;
                }
            }
            log.info("Voranalysierte Neueingänge: {} E-Mail(s) (Szenario A/D)", created);
        } catch (Exception e) {
            log.warn("Voranalysierte Neueingänge fehlgeschlagen: {}", e.getMessage());
        }
        return created;
    }

    private record PreAnalyzedEmail(String subject, String senderEmail, String topic,
                                    boolean uncertainMatch) {}

    private static final List<PreAnalyzedEmail> PRE_ANALYZED_EMAILS = List.of(
            // Szenario A — neues Anliegen, kein Falltreffer.
            new PreAnalyzedEmail("Hundesteuer – Anmeldung eines Hundes",
                    "petra.braun@example.de", "Allgemeines Anliegen", false),
            // Szenario D — unsicherer Falltreffer (SIMILAR), Bestätigung nötig.
            new PreAnalyzedEmail("Wohngeldantrag – welche Unterlagen fehlen noch?",
                    "erika.schulze@example.de", "Wohngeld", true));

    private UUID preAnalysisForScenario(IncomingEmailEntity email, String topic, WorkspaceEntity candidate) {
        try {
            List<verwaltungsassistent.web.controller.EmailController.CaseRef> matches = new ArrayList<>();
            if (candidate != null) {
                matches.add(new verwaltungsassistent.web.controller.EmailController.CaseRef(
                        candidate.getId(), candidate.getName(),
                        "Übereinstimmende Begriffe: wohngeld, unterlagen, fehlen — kein bestätigtes "
                                + "Personen-Signal. Zuordnung ausdrücklich bestätigen oder ablehnen.",
                        "SIMILAR"));
            }
            List<verwaltungsassistent.web.controller.EmailController.Step> steps = List.of(
                    new verwaltungsassistent.web.controller.EmailController.Step(1,
                            "Fallbezug prüfen",
                            candidate != null
                                    ? "Möglicher Bezug zu einem bestehenden Vorgang — Zuordnung ausdrücklich bestätigen oder ablehnen."
                                    : "Kein passender bestehender Vorgang — neuen Vorgang anlegen.",
                            candidate != null ? "Vorgang prüfen" : "Neuen Vorgang anlegen",
                            candidate != null ? "/cases/" + candidate.getId() : "/cases/new",
                            true));
            verwaltungsassistent.web.controller.EmailController.EmailOutcome outcome =
                    new verwaltungsassistent.web.controller.EmailController.EmailOutcome(
                            email.getSubject(), topic, null, null,
                            matches, List.of(),
                            verwaltungsassistent.web.controller.EmailController
                                    .typicalMissingDocuments(topic),
                            steps, null, null, null, List.of());
            EmailAnalysisEntity analysis = new EmailAnalysisEntity(UUID.randomUUID(),
                    email.getAssignedTo() != null ? email.getAssignedTo() : DEMO_OWNER,
                    email.getText(), email.getSubject(), topic, null, null, Instant.now());
            analysis.setResultJson(MAPPER.writeValueAsString(outcome));
            analysisRepository.save(analysis);
            return analysis.getId();
        } catch (Exception e) {
            log.warn("Vor-Analyse für Neueingang '{}' fehlgeschlagen: {}", email.getSubject(), e.getMessage());
            return null;
        }
    }

    /** Applies the catalog definition (status, assignment, completion) to an e-mail row. */
    private void applyCatalogState(IncomingEmailEntity entity, SeedEmail s, Instant now) {
        entity.setStatus(s.status());
        entity.setSourceType(IncomingEmailEntity.SourceType.MAILBOX);
        // Threading-Header (messageId / inReplyTo / references) sind Teil des
        // Katalog-Zustands — die Header-basierte Thread-/Fall-Erkennung
        // (Phase 2C.1) braucht sie dauerhaft, nicht nur während einer Sitzung.
        entity.setMessageId(s.messageId());
        entity.setInReplyTo(s.inReplyTo());
        entity.setReferences(s.references());
        // Der Analyse-Link und die Fall-Zuordnung entstehen ausschließlich
        // während einer Demo-Sitzung (E-Mail analysieren / Diesem Fall
        // zuordnen) — der Katalog-Zustand hat beides nicht.
        entity.setAnalysisId(null);
        entity.setWorkspaceId(null);
        if (s.status() == Status.NEW) {
            entity.setAssignedTo(null);
            entity.setAssignedAt(null);
            entity.setCompletedAt(null);
            entity.setCompletedBy(null);
            return;
        }
        entity.setAssignedTo(entity.getAddressedToEmail());
        entity.setAssignedAt(now.minus(s.receivedAgoMinutes() - 30, ChronoUnit.MINUTES));
        if (s.status() == Status.COMPLETED) {
            entity.setCompletedAt(now.minus(s.receivedAgoMinutes() - 120, ChronoUnit.MINUTES));
            entity.setCompletedBy(entity.getAddressedToEmail());
        } else {
            entity.setCompletedAt(null);
            entity.setCompletedBy(null);
        }
    }

    // ── Demo-Fotos (synthetisch, Brandenburg) → Geovorgänge ────────────────

    /**
     * Deterministischer Katalog der Brandenburg-Demo-Fotos.
     *
     * Die Bilddateien sind archivierte Übungsaufnahmen (echte Fotografien,
     * keine schematischen Zeichnungen), die als Demo-Material mitgeliefert
     * werden ({@code demo/geo-photos/}). Die GPS-Positionen sind fest
     * vorgegebene Demo-Koordinaten, liegen innerhalb der sieben gebündelten
     * Zuständigkeitsgebiete und lösen über den GeoService deterministisch auf
     * die beabsichtigte Behörde auf. Beim Import wird den Fotos per EXIF
     * (GPS + Aufnahmezeitpunkt) der Demo-Standort eingebettet; der
     * nachgelagerte GeoWorkflow verhält sich damit identisch zu echten
     * geotaggten Außendienstfotos.
     */
    private record SyntheticPhoto(String fileName, String caseTitle, String category, String priority,
                                  String remark, String description, String locationLabel,
                                  double lat, double lon, int capturedAtMinuteOffset) {}

    private static final List<SyntheticPhoto> BRANDENBURG_PHOTOS = List.of(
            new SyntheticPhoto("baustelle_potsdam_01.jpg",
                    "Baustelle – Einrichtung beschädigt", "Baustelle / Bauaufsicht", "HOCH",
                    "Baustelleneinrichtung teilweise umgestürzt, Absperrung defekt.",
                    "Gemeldete beschädigte Baustelleneinrichtung im öffentlichen Raum.",
                    "Werder (Havel)", 52.4012, 12.9145, 0),
            new SyntheticPhoto("strassenschaden_potsdam_02.jpg",
                    "Straßenschaden", "Straßenschaden", "MITTEL",
                    "Schlagloch in der Fahrbahn, für Radfahrer gefährlich.",
                    "Gemeldeter Straßenschaden im öffentlichen Straßenraum.",
                    "Werder (Havel)", 52.4022, 12.9155, 17),
            new SyntheticPhoto("muell_potsdam_03.jpg",
                    "Müllablagerung", "Müllablagerung", "MITTEL",
                    "Illegale Ablagerung am Straßenrand, Abholung veranlasst.",
                    "Gemeldete illegale Müllablagerung im öffentlichen Raum.",
                    "Werder (Havel)", 52.4032, 12.9165, 34),
            new SyntheticPhoto("strassenbeleuchtung_brb_01.jpg",
                    "Defekte Straßenbeleuchtung", "Straßenbeleuchtung", "HOCH",
                    "Straßenlaterne fällt in der Dunkelheit komplett aus.",
                    "Gemeldete defekte Straßenbeleuchtung im öffentlichen Raum.",
                    "Brandenburg an der Havel", 52.4062, 12.5750, 51),
            new SyntheticPhoto("verkehrsschild_brb_02.jpg",
                    "Beschädigtes Verkehrsschild", "Verkehrsschild", "NIEDRIG",
                    "Verkehrsschild umgeknickt, Beschilderung unvollständig.",
                    "Gemeldetes beschädigtes Verkehrsschild im öffentlichen Raum.",
                    "Brandenburg an der Havel", 52.4206, 12.4970, 68),
            new SyntheticPhoto("ablagerung_oranienburg_01.jpg",
                    "Illegale Ablagerung", "Müllablagerung", "MITTEL",
                    "Größere Ablagerung von Bauschutt am Wegesrand.",
                    "Gemeldete illegale Ablagerung im öffentlichen Raum.",
                    "Oranienburg", 52.7490, 13.2418, 85),
            new SyntheticPhoto("fahrzeug_oranienburg_02.jpg",
                    "Aufgegebenes Fahrzeug im öffentlichen Raum", "Parken / Ordnungswidrigkeit", "HOCH",
                    "Fahrzeug ohne Kennzeichen steht seit Wochen am Straßenrand.",
                    "Gemeldetes aufgegebenes Fahrzeug im öffentlichen Raum.",
                    "Oranienburg", 52.7472, 13.2563, 102),
            new SyntheticPhoto("ueberflutung_werder_01.jpg",
                    "Wasser auf der Fahrbahn", "Straßenschaden", "HOCH",
                    "Überflutete Fahrbahn nach Starkregen, Abfluss verstopft.",
                    "Gemeldete Überflutung auf einer öffentlichen Straße.",
                    "Werder (Havel)", 52.4025, 12.9172, 119),
            new SyntheticPhoto("gehweg_werder_02.jpg",
                    "Beschädigter Gehweg", "Straßenschaden", "MITTEL",
                    "Angehobene Gehwegplatten, Stolpergefahr.",
                    "Gemeldeter beschädigter Gehweg im öffentlichen Raum.",
                    "Werder (Havel)", 52.4040, 12.9180, 136),
            new SyntheticPhoto("muell_falkensee_01.jpg",
                    "Müllablagerung", "Müllablagerung", "MITTEL",
                    "Müllsäcke neben der Sammelstelle, Reinigung veranlasst.",
                    "Gemeldete Müllablagerung im öffentlichen Raum.",
                    "Falkensee", 52.5596, 13.0755, 153),
            new SyntheticPhoto("baustelleneinrichtung_falkensee_02.jpg",
                    "Beschädigte Baustelleneinrichtung", "Baustelle / Bauaufsicht", "MITTEL",
                    "Absperrung einer Baustelle beschädigt, Zutritt möglich.",
                    "Gemeldete beschädigte Baustelleneinrichtung im öffentlichen Raum.",
                    "Falkensee", 52.5332, 13.0600, 170),
            new SyntheticPhoto("parken_bernau_01.jpg",
                    "Parken auf dem Gehweg", "Parken / Ordnungswidrigkeit", "NIEDRIG",
                    "Fahrzeug blockiert den Fußgängerweg, Kontrolle veranlasst.",
                    "Gemeldete Parkordnungswidrigkeit im öffentlichen Raum.",
                    "Bernau bei Berlin", 52.6556, 13.5439, 187),
            new SyntheticPhoto("infrastruktur_bernau_02.jpg",
                    "Beschädigte öffentliche Infrastruktur", "Öffentliche Anlage", "MITTEL",
                    "Sitzbank im Stadtpark beschädigt, Reparatur erforderlich.",
                    "Gemeldete beschädigte öffentliche Infrastruktur.",
                    "Bernau bei Berlin", 52.6778, 13.5892, 204),
            new SyntheticPhoto("strassenschaden_kw_01.jpg",
                    "Straßenschaden", "Straßenschaden", "MITTEL",
                    "Abgesackter Fahrbahnbelag an der Kreuzung.",
                    "Gemeldeter Straßenschaden im öffentlichen Straßenraum.",
                    "Königs Wusterhausen", 52.2968, 13.6280, 221));

    private static SyntheticPhoto photoSpecFor(String fileName) {
        return BRANDENBURG_PHOTOS.stream()
                .filter(p -> p.fileName().equals(fileName))
                .findFirst()
                .orElse(new SyntheticPhoto(fileName,
                        "Außendienstaufnahme " + fileName, "Allgemeine Aufnahme", "MITTEL",
                        "Außendienstaufnahme mit GPS-Position (EXIF).",
                        "Außendienstaufnahme mit GPS-Position (EXIF).", "", 0, 0, 0));
    }

    /**
     * Zuständige Mitarbeiterin für einen Geovorgang (Phase 2D.16): GEO-Demo-
     * Vorgänge werden auf ECHTE Mitarbeiterkonten verteilt (demo02…demo05)
     * statt auf das Leitungs-Konto — admin@verwaltungsassistent.local bleibt rein aufsichtlich
     * und sieht alle Vorgänge, ohne Eigentümer zu sein. Die Zuordnung ist
     * deterministisch über den Foto-Katalog; einzelne Szenarien (die in der
     * Vorführung sichtbaren Aufnahmen) sind explizit gesetzt.
     */
    private static String geoOwnerEmail(String fileName, int catalogIndex) {
        int slot;
        switch (fileName) {
            case "strassenbeleuchtung_brb_01.jpg" -> slot = 0; // demo02 — Leit-Szenario
            case "muell_potsdam_03.jpg" -> slot = 1;           // demo03
            case "strassenschaden_potsdam_02.jpg" -> slot = 2; // demo04
            default -> slot = catalogIndex % 4;
        }
        return demoEmail(2 + slot);
    }

    /**
     * Importiert den deterministischen Brandenburg-Demo-Foto-Bestand: die
     * archivierten Übungsaufnahmen (echte Fotografien aus
     * {@code demo/geo-photos/}) werden mit Demo-EXIF (GPS + Aufnahmezeitpunkt)
     * unter uploads/photos/ abgelegt, dann EXIF → GeoService → Geovorgang.
     * Weicht eine vorhandene Datei vom ausgelieferten Asset ab (z. B. noch aus
     * der früheren synthetischen Erzeugung), wird sie aktualisiert und die
     * Anzeige-Kopie bestehender Datensätze in-place erneuert.
     */
    private int importDemoPhotos() {
        return importDemoPhotos(null);
    }

    /**
     * Phase 2D.15 — Dateisystem-Dataset: importiert die Geo-Demo-Bilder aus
     * {@code <dataset-root>/images/geo/} durch DENSELBEN Geo-/EXIF-/Geovorgang-
     * Pfad wie der Klassenpfad-Import. Erwartet werden die kanonischen
     * Dateinamen des Demo-Bestands (identische Semantik; GPS/EXIF werden
     * deterministisch ergänzt). Fehlende Dateien werden übersprungen und
     * protokolliert — das Dataset bestimmt den Umfang.
     */
    public int importDemoGeoPhotosFrom(java.nio.file.Path datasetGeoDir) {
        return importDemoPhotos(datasetGeoDir);
    }

    private int importDemoPhotos(java.nio.file.Path assetDir) {
        int created = 0;
        Path imagesDir = uploadDir.resolve("photos");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            log.warn("Demo-Foto-Ordner '{}' konnte nicht angelegt werden: {}", imagesDir, e.getMessage());
            return 0;
        }
        LocalDateTime baseCapture = LocalDateTime.of(2026, 8, 20, 9, 0);
        int catalogIndex = 0;
        for (SyntheticPhoto spec : BRANDENBURG_PHOTOS) {
            String fileName = spec.fileName();
            try {
                Path file = imagesDir.resolve(fileName);
                byte[] demoBytes = demoPhotoBytes(spec, baseCapture, assetDir);
                if (demoBytes == null) {
                    continue; // Datei fehlt im gewählten Asset-Quellverzeichnis
                }
                if (!Files.exists(file)
                        || !java.util.Arrays.equals(Files.readAllBytes(file), demoBytes)) {
                    Files.write(file, demoBytes);
                    log.info("Demo-Foto aktualisiert (Archivaufnahme): {}", fileName);
                    refreshExistingDisplayCopy(fileName, demoBytes);
                }
                String storagePath = "photos/" + fileName;
                GeoPhotoEntity existing = photoRepository.findAll().stream()
                        .filter(p -> storagePath.equals(p.getStoragePath()))
                        .findFirst().orElse(null);
                if (existing != null) {
                    continue;
                }
                String ownerEmail = geoOwnerEmail(fileName, catalogIndex);
                ExtractedGps gps = photoService.extract(storagePath);
                GeoPhotoEntity photo = new GeoPhotoEntity(UUID.randomUUID(), fileName, storagePath,
                        fileName.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg",
                        ownerEmail, Instant.now());
                photo.setGpsSource(gps.hasGps() ? "EXIF" : null);
                photoService.applyMetadata(photo, gps);
                // EXIF (GPS/Zeit/Ausrichtung) ist jetzt im Datensatz — die
                // Anzeige-/Download-Kopie wird von Geräte-Metadaten bereinigt,
                // das unveränderte Original bleibt unter photos/ erhalten.
                photo.setOriginalPath(storagePath);
                photo.setStoragePath(photoService.createSanitizedCopy(
                        photoService.resolve(storagePath), photo.getContentType()));
                photoRepository.save(photo);
                created++;
                if (gps.hasGps()) {
                    createGeoCaseFromPhoto(photo, ownerEmail);
                    created++;
                } else {
                    log.info("Demo-Foto '{}' ohne GPS-Metadaten importiert (nur Foto-Eintrag)", fileName);
                }
            } catch (Exception e) {
                log.warn("Demo-Foto '{}' konnte nicht importiert werden: {}", fileName, e.getMessage());
            } finally {
                catalogIndex++;
            }
        }
        ensureEmployeeGeoVorgaenge();
        return created;
    }

    // ── Team-Geovorgänge: jede Demo-Mitarbeiterin (demo01..demo20) hat
    //    mindestens zwei eigene, inhaltlich verschiedene Geovorgänge ─────────

    private static final String[] GEO_SUBJECTS = {
            "Defekte Straßenlaterne", "Vollgelaufener Papierkorb", "Schlagloch in der Fahrbahn",
            "Verstopfter Regenablauf", "Wildwuchs auf dem Gehweg", "Kaputte Parkbank",
            "Graffiti an der Fassade", "Umgestürzter Fahrradständer", "Defekte Ampel",
            "Unleserliches Straßenschild"};

    private static final String[] GEO_STREETS = {
            "Lindenstraße", "Karl-Marx-Straße", "Friedrich-Ebert-Straße", "Rudolf-Breitscheid-Straße",
            "Brandenburger Straße", "Potsdamer Straße", "Am Kanal", "Rathausgasse"};

    /**
     * Ergänzt deterministisch Geovorgänge, damit JEDE Demo-Mitarbeiterin
     * demo01..demo20 mindestens zwei eigene, inhaltlich verschiedene
     * Geovorgänge hat. Koordinaten werden aus den vorhandenen, gültigen
     * Demo-Foto-Positionen wiederverwendet und über den GeoService aufgelöst
     * (keine neue GIS-/Zuständigkeits-Logik). Die bestehenden Foto→Vorgang-
     * Zuordnungen bleiben 1:1 erhalten („Vorgang öffnen" bleibt gültig);
     * zusätzliche Team-Vorgänge erhalten bewusst KEINE Foto-Verknüpfung.
     * Idempotent: vorhandene eigene GEO-Vorgänge werden mitgezählt.
     */
    private void ensureEmployeeGeoVorgaenge() {
        List<GeoPhotoEntity> withGps = photoRepository.findAll().stream()
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .sorted(java.util.Comparator.comparing(
                        p -> p.getOriginalName() != null ? p.getOriginalName() : ""))
                .toList();
        if (withGps.isEmpty()) {
            return;
        }
        for (int emp = 1; emp <= DEMO_USER_COUNT; emp++) {
            String email = demoEmail(emp);
            long owned = workspaceService.findAll().stream()
                    .filter(w -> "GEO".equalsIgnoreCase(w.getWorkspaceType())
                            && email.equalsIgnoreCase(w.getOwnerId()))
                    .count();
            for (int k = (int) owned; k < 2; k++) {
                int idx = (emp - 1) * 2 + k;
                String subject = GEO_SUBJECTS[idx % GEO_SUBJECTS.length];
                String street = GEO_STREETS[(idx / GEO_SUBJECTS.length + k) % GEO_STREETS.length];
                GeoPhotoEntity p = withGps.get(idx % withGps.size());
                createGeoVorgang(subject + " – " + street,
                        "Außendienstmeldung (Demo): " + subject + " im Bereich " + street + ".",
                        subject, p.getLatitude(), p.getLongitude(), email);
            }
        }
    }

    /**
     * Erzeugt einen gewöhnlichen Geovorgang (workspace type GEO) aus
     * Koordinaten — dieselbe Auflösung wie {@link #createGeoCaseFromPhoto}
     * (GeoService: Zuständigkeitsgebiet + nächstgelegene Adresse), ohne
     * Foto-Verknüpfung.
     */
    private void createGeoVorgang(String title, String description, String category,
                                  double latitude, double longitude, String ownerEmail) {
        Jurisdiction j = geoService.jurisdictionFor(latitude, longitude);
        WorkspaceEntity ws = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                title, description, "GEO", ownerEmail));
        GeoAddress nearest = geoService.nearestAddress(latitude, longitude, 1500).orElse(null);
        ws.setGeoAddress(nearest != null ? nearest.fullAddress() : null);
        ws.setGeoStreet(nearest != null ? nearest.street() : null);
        ws.setGeoHouseNumber(nearest != null ? nearest.houseNumber() : null);
        ws.setGeoPostalCode(nearest != null ? nearest.postalCode() : null);
        ws.setGeoCity(nearest != null ? nearest.city() : null);
        ws.setGeoLatitude(latitude);
        ws.setGeoLongitude(longitude);
        ws.setGeoDistrict(j.resolved() ? j.district() : null);
        ws.setGeoAuthority(j.resolved() ? j.authority() : null);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("geoCategory", category);
        data.put("geoPriority", "Mittlere Priorität");
        data.put("geoRemark", "Team-Geovorgang (Demo)");
        data.put("source", "Außendienstmeldung (Demo)");
        try {
            ws.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            ws.setPhaseData("{}");
        }
        workspaceService.save(ws);
        log.info("Team-Geovorgang '{}' erzeugt (Gebiet: {}, zuständig: {})",
                title, j.resolved() ? j.district() : "unbekannt", ownerEmail);
    }

    /**
     * Ausgeliefertes Demo-Foto für ein Szenario: archivierte Übungsaufnahme
     * (JPEG) plus deterministische Demo-EXIF-Daten (GPS + Aufnahmezeitpunkt).
     * {@code assetDir == null} → Klassenpfad-Asset; sonst Datei aus dem
     * Dataset-Verzeichnis (fehlend → null, wird übersprungen).
     */
    private byte[] demoPhotoBytes(SyntheticPhoto spec, LocalDateTime baseCapture,
                                  java.nio.file.Path assetDir) throws IOException {
        byte[] jpeg;
        if (assetDir == null) {
            ClassPathResource asset = new ClassPathResource("demo/geo-photos/" + spec.fileName());
            if (!asset.exists()) {
                throw new IOException("Demo-Foto-Asset fehlt: " + spec.fileName());
            }
            try (var in = asset.getInputStream()) {
                jpeg = in.readAllBytes();
            }
        } else {
            Path file = assetDir.resolve(spec.fileName());
            if (!Files.isRegularFile(file)) {
                log.warn("Geo-Dataset: Datei '{}' fehlt unter {} — Foto wird übersprungen.",
                        spec.fileName(), assetDir);
                return null;
            }
            jpeg = Files.readAllBytes(file);
        }
        return SyntheticPhotoFactory.demoPhoto(jpeg, spec.lat(), spec.lon(),
                baseCapture.plusMinutes(spec.capturedAtMinuteOffset()));
    }

    /**
     * Erneuert die Anzeige-/Download-Kopie eines bereits existierenden
     * Demo-Foto-Datensatzes in-place, wenn sich die Quelldatei geändert hat
     * (z. B. Upgrade von synthetisch auf Archivaufnahme). Datensatz und
     * Koordinaten bleiben unverändert — nur die Pixel werden erneuert.
     * Abgeglichen wird über den Original-Dateinamen (der gespeicherte
     * storagePath zeigt auf die Anzeige-Kopie, nicht auf die Quelldatei).
     */
    private void refreshExistingDisplayCopy(String fileName, byte[] demoBytes) {
        try {
            GeoPhotoEntity existing = photoRepository.findAll().stream()
                    .filter(p -> fileName.equals(p.getOriginalName()))
                    .findFirst().orElse(null);
            if (existing == null || existing.getStoragePath() == null) {
                return;
            }
            byte[] display = GeoPhotoService.stripJpegMetadata(demoBytes);
            Path displayFile = photoService.resolve(existing.getStoragePath());
            if (Files.exists(displayFile)) {
                if (java.util.Arrays.equals(Files.readAllBytes(displayFile), display)) {
                    return;
                }
                Files.write(displayFile, display);
            } else {
                Files.write(displayFile, display);
            }
            log.info("Anzeige-Kopie für Demo-Foto '{}' aktualisiert", fileName);
        } catch (Exception e) {
            log.warn("Anzeige-Kopie für Demo-Foto '{}' konnte nicht aktualisiert werden: {}", fileName, e.getMessage());
        }
    }

    /**
     * Creates an ordinary Geovorgang (workspace type GEO) from an imported
     * photograph: coordinates → jurisdiction → phase data → photo as evidence
     * document. Mirrors the interactive GeoController flow exactly.
     *
     * <p>Zuständig ist eine ECHTE Mitarbeiterin (ownerEmail) — niemals das
     * Leitungs-Konto; die Leitung sieht alle GEO-Vorgänge aufsichtlich.</p>
     */
    private void createGeoCaseFromPhoto(GeoPhotoEntity photo, String ownerEmail) {
        Jurisdiction j = geoService.jurisdictionFor(photo.getLatitude(), photo.getLongitude());
        SyntheticPhoto spec = photoSpecFor(photo.getOriginalName());
        WorkspaceEntity ws = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                spec.caseTitle(), spec.description(), "GEO", ownerEmail));
        // Nächstgelegene erfasste Adresse auflösen (Invariante: ein Geovorgang
        // zeigt die Adresse, wenn der lokale Adressbestand sie auflösen kann).
        GeoAddress nearest = null;
        if (photo.getLatitude() != null && photo.getLongitude() != null) {
            nearest = geoService.nearestAddress(photo.getLatitude(), photo.getLongitude(), 1500).orElse(null);
        }
        ws.setGeoAddress(nearest != null ? nearest.fullAddress() : null);
        ws.setGeoStreet(nearest != null ? nearest.street() : null);
        ws.setGeoHouseNumber(nearest != null ? nearest.houseNumber() : null);
        ws.setGeoPostalCode(nearest != null ? nearest.postalCode() : null);
        ws.setGeoCity(nearest != null ? nearest.city() : null);
        ws.setGeoLatitude(photo.getLatitude());
        ws.setGeoLongitude(photo.getLongitude());
        ws.setGeoDistrict(j.resolved() ? j.district() : null);
        ws.setGeoAuthority(j.resolved() ? j.authority() : null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("geoCategory", spec.category());
        data.put("geoPriority", spec.priority());
        data.put("geoRemark", spec.remark());
        data.put("source", "Außendienstaufnahme (Demo, EXIF)");
        try {
            ws.setPhaseData(MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            ws.setPhaseData("{}");
        }
        workspaceService.save(ws);

        photo.setWorkspaceId(UUID.fromString(ws.getId()));
        photoRepository.save(photo);
        attachPhotoAsDocument(ws, photo, ownerEmail);
        log.info("Geovorgang '{}' aus Foto '{}' erzeugt (Gebiet: {}, zuständig: {})",
                spec.caseTitle(), photo.getOriginalName(),
                j.resolved() ? j.district() : "unbekannt", ownerEmail);
    }

    /** Photo becomes an ordinary evidence document; indexing runs afterwards. */
    private void attachPhotoAsDocument(WorkspaceEntity ws, GeoPhotoEntity photo, String ownerEmail) {
        try {
            // Deterministic: reuse the existing document for the same original
            // file instead of creating a new record per reset.
            Document existing = findPhotoDocument(photo.getOriginalName());
            if (existing != null) {
                attachIfMissing(ws, existing);
                return;
            }
            Path file = photoService.resolve(photo.getStoragePath());
            DocumentFileType fileType = photo.getOriginalName() != null
                    && photo.getOriginalName().toLowerCase().endsWith(".png") ? DocumentFileType.PNG : DocumentFileType.JPG;
            byte[] bytes = Files.readAllBytes(file);
            String checksum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).toString();
            var cmd = new CreateDocumentCommand(
                    "Foto " + photo.getOriginalName(), fileType, photo.getOriginalName(),
                    photo.getContentType(), bytes.length, "local", photo.getStoragePath(),
                    checksum, "OTHER", Set.of("geo"),
                    "INTERNAL", ownerEmail, "default");
            Document doc = documentFacade.createDocument(cmd);
            workspaceService.attachDocument(new AttachDocumentCommand(
                    ws.getId().toString(), doc.id().toString(),
                    DocumentCategory.OTHER, "OTHER", null));
            indexDocument(doc.id());
        } catch (Exception e) {
            log.warn("Demo-Foto konnte nicht als Dokument angehängt werden: {}", e.getMessage());
        }
    }

    /** The existing document record for a demo photo original, or null. */
    private Document findPhotoDocument(String originalName) {
        try {
            for (int page = 0; ; page++) {
                DocumentFilter filter = new DocumentFilter(null, null, null, null, null, null, null, page, 100);
                var pageDocs = documentFacade.findDocuments(filter);
                if (pageDocs.documents().isEmpty()) break;
                for (Document doc : pageDocs.documents()) {
                    if (doc.metadata() != null && doc.metadata().title() != null
                            && ("Foto " + originalName).equalsIgnoreCase(doc.metadata().title())) {
                        return doc;
                    }
                }
                if (pageDocs.documents().size() < 100) break;
            }
        } catch (Exception e) {
            log.warn("Foto-Dokument-Suche fehlgeschlagen: {}", e.getMessage());
        }
        return null;
    }

    private void attachIfMissing(WorkspaceEntity ws, Document doc) {
        try {
            boolean attached = workspaceService.getWorkspaceDocuments(ws.getId().toString()).stream()
                    .anyMatch(l -> l.getDocumentId().equals(doc.id().toString()));
            if (!attached) {
                workspaceService.attachDocument(new AttachDocumentCommand(
                        ws.getId().toString(), doc.id().toString(),
                        DocumentCategory.OTHER, "OTHER", null));
            }
        } catch (Exception e) {
            log.warn("Foto-Dokument konnte nicht angehängt werden: {}", e.getMessage());
        }
    }

    /** Existing ingestion pipeline: extract → chunk → embed → Qdrant. */
    private void indexDocument(UUID documentId) {
        if (hasActiveIngestionJob(documentId)) {
            log.info("Dokument {} wird bereits indexiert — kein zweiter Job (2D.16)", documentId);
            return;
        }
        try {
            var job = documentFacade.createIngestionJob(documentId, "system");
            documentFacade.startIngestion(job.id(), "system");
            ingestionProcessor.ingest(documentId);
            documentFacade.completeIngestion(job.id(), "system");
            log.info("Dokument {} über bestehende Pipeline indexiert", documentId);
        } catch (Exception e) {
            log.warn("Indexierung des Demo-Dokuments {} fehlgeschlagen: {}", documentId, e.getMessage());
        }
    }

    /**
     * 2D.16 — Schutz vor versehentlichen Doppel-Jobs: Läuft für das Dokument
     * bereits ein Indexierungs-Job (PENDING/RUNNING), wird kein zweiter
     * erzeugt. Verhindert die doppelten Verlaufszeilen, wenn zwei Import-
     * Pfade dasselbe frisch angelegte Dokument kurz hintereinander
     * indexieren. Explizite „Neu indexieren"-Aktionen sind NICHT betroffen
     * (sie laufen über DocumentController ohne diesen Guard).
     */
    private boolean hasActiveIngestionJob(UUID documentId) {
        try {
            var page = documentFacade.findIngestionJobs(
                    new reasoning.document.api.IngestionJobFilter(
                            documentId, null, null, 0, 10));
            if (page == null || page.jobs() == null) {
                return false;
            }
            for (var job : page.jobs()) {
                String status = String.valueOf(job.status()).toUpperCase();
                if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Job-Prüfung für {} nicht möglich: {}", documentId, e.getMessage());
        }
        return false;
    }

    // ── Cleanup (demo database state only — never files on disk) ─────────────

    /** Removes the demo database state: demo users, workspaces, e-mails, analyses, photos, mailboxes.
     *  Also removes the demo photo files so they are re-created deterministically from the assets. */
    private void cleanupDemoNamespace() {
        try {
            Path imagesDir = uploadDir.resolve("photos");
            for (SyntheticPhoto spec : BRANDENBURG_PHOTOS) {
                try {
                    Files.deleteIfExists(imagesDir.resolve(spec.fileName()));
                } catch (IOException e) {
                    log.debug("Demo-Foto '{}' konnte nicht gelöscht werden: {}", spec.fileName(), e.getMessage());
                }
            }
            for (UserAccountEntity u : userAccountRepository.findAll()) {
                if (u.getEmail() != null && u.getEmail().startsWith("demo")
                        && u.getEmail().endsWith(DEMO_EMAIL_SUFFIX)) {
                    List<WorkspaceEntity> workspaces = workspaceService.findByOwner(u.getEmail());
                    for (WorkspaceEntity ws : workspaces) {
                        deleteWorkspaceWithPhotoDocuments(ws);
                    }
                    refreshTokenSessionRepository.deleteByUser(u);
                    userAccountRepository.delete(u);
                }
            }
            // Geovorgänge (all GEO workspaces are demo data in this appliance)
            // and legacy admin test cases that are not part of the seeded
            // e-mail workflow cases — the demo state stays reproducible.
            java.util.Set<String> seededNames = verwaltungsassistent.web.config.DemoCaseSeeder.SEEDED_CASE_NAMES;
            for (WorkspaceEntity ws : workspaceService.findAll()) {
                if ("GEO".equalsIgnoreCase(ws.getWorkspaceType())) {
                    deleteWorkspaceWithPhotoDocuments(ws);
                } else if (DEMO_OWNER.equals(ws.getOwnerId())
                        && !seededNames.contains(normalizeName(ws.getName()))) {
                    log.info("Entferne Alt-Demo-Fall '{}' (nicht Teil des Demo-Ausgangszustands)", ws.getName());
                    deleteWorkspaceWithPhotoDocuments(ws);
                }
            }
            deleteOrphanPhotoDocuments();
            incomingEmailRepository.deleteAll();
            analysisRepository.deleteAll();
            photoRepository.deleteAll();
            mailboxRepository.deleteAll();
            userAccountRepository.flush();
            incomingEmailRepository.flush();
            analysisRepository.flush();
            photoRepository.flush();
            mailboxRepository.flush();
        } catch (Exception e) {
            log.warn("Demo-Daten-Bereinigung fehlgeschlagen: {}", e.getMessage());
        }
    }

    /** Normalized case-name matching (same rule as DemoCaseSeeder). */
    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase().replace("-", "").replace("–", "").replace(" ", "");
    }

    /**
     * Removes photo evidence documents ("Foto …") that are no longer linked to
     * any existing workspace (orphans from older demo runs) — their files stay
     * on disk. Corpus documents are never touched.
     */
    private void deleteOrphanPhotoDocuments() {
        try {
            java.util.Set<String> linked = new java.util.HashSet<>();
            for (WorkspaceEntity ws : workspaceService.findAll()) {
                try {
                    workspaceService.getWorkspaceDocuments(ws.getId().toString())
                            .forEach(l -> linked.add(l.getDocumentId()));
                } catch (Exception e) {
                    log.debug("Dokument-Links von {} nicht lesbar: {}", ws.getId(), e.getMessage());
                }
            }
            for (int page = 0; ; page++) {
                DocumentFilter filter = new DocumentFilter(null, null, null, null, null, null, null, page, 100);
                var pageDocs = documentFacade.findDocuments(filter);
                if (pageDocs.documents().isEmpty()) break;
                for (Document doc : pageDocs.documents()) {
                    String title = doc.metadata() != null ? doc.metadata().title() : null;
                    if (title != null && title.startsWith("Foto ")
                            && !linked.contains(doc.id().toString())) {
                        try {
                            documentFacade.deleteDocument(doc.id(), "system");
                            log.info("Verwaistes Foto-Dokument '{}' entfernt (Datei bleibt erhalten)", title);
                        } catch (Exception e) {
                            log.debug("Foto-Dokument {} konnte nicht entfernt werden: {}", doc.id(), e.getMessage());
                        }
                    }
                }
                if (pageDocs.documents().size() < 100) break;
            }
        } catch (Exception e) {
            log.warn("Bereinigung verwaister Foto-Dokumente fehlgeschlagen: {}", e.getMessage());
        }
    }

    /**
     * Removes a workspace and the photo evidence documents attached to it
     * (their files stay on disk). Only documents whose title marks them as
     * photo evidence ("Foto …") are deleted — corpus documents stay untouched.
     */
    private void deleteWorkspaceWithPhotoDocuments(WorkspaceEntity ws) {
        try {
            List<String> linkedDocIds = workspaceService.getWorkspaceDocuments(ws.getId().toString()).stream()
                    .map(l -> l.getDocumentId())
                    .filter(id -> id != null)
                    .toList();
            workspaceService.deleteAnalysisRuns(ws.getId().toString());
            workspaceService.deleteWorkspace(ws.getId().toString());
            for (String docId : linkedDocIds) {
                try {
                    Document doc = documentFacade.getDocument(UUID.fromString(docId), "system");
                    if (doc.metadata() != null && doc.metadata().title() != null
                            && doc.metadata().title().startsWith("Foto ")) {
                        documentFacade.deleteDocument(doc.id(), "system");
                    }
                } catch (Exception e) {
                    log.debug("Foto-Dokument {} konnte nicht entfernt werden: {}", docId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Workspace-Bereinigung fehlgeschlagen: {}", e.getMessage());
        }
    }
}
