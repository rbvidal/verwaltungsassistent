package verwaltungsassistent.web.service;

import reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.common.model.DocumentStatus;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.DocumentPage;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.document.model.DocumentMetadata;
import reasoning.common.model.DocumentFileType;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.analysis.persistence.MailboxEntity;
import verwaltungsassistent.web.analysis.persistence.MailboxRepository;
import verwaltungsassistent.web.geo.GeoPhotoEntity;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import verwaltungsassistent.web.geo.GeoPhotoService.ExtractedGps;
import reasoning.document.api.DocumentIngestionProcessor;
import verwaltungsassistent.web.service.GeoService.Jurisdiction;
import verwaltungsassistent.web.util.ExifJpegHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the deterministic demo-data importer ("Demo-Daten
 * zurücksetzen"): cleanup, recreation of users/mailboxes/e-mail catalog,
 * photo import with EXIF → GeoService → Geovorgang, persistence of the
 * original files, indexing trigger and repeatability.
 */
class DemoDataResetTest {

    @TempDir
    Path tempDir;

    private UserAccountRepository userRepo;
    private WorkspaceService workspaceService;
    private JpaIncomingEmailRepository emailRepo;
    private JpaEmailAnalysisRepository analysisRepo;
    private MailboxRepository mailboxRepo;
    private GeoPhotoRepository photoRepo;
    private GeoPhotoService photoService;
    private GeoService geoService;
    private DocumentFacade documentFacade;
    private DocumentIngestionProcessor ingestion;
    private DemoDataService service;

    // stateful stubs (behavior of a real database)
    private final List<UserAccountEntity> knownUsers = new ArrayList<>();
    private final List<WorkspaceEntity> knownWorkspaces = new ArrayList<>();
    private final List<GeoPhotoEntity> knownPhotos = new ArrayList<>();
    private final List<MailboxEntity> knownMailboxes = new ArrayList<>();
    private final List<IncomingEmailEntity> savedEmails = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        userRepo = mock(UserAccountRepository.class);
        workspaceService = mock(WorkspaceService.class);
        emailRepo = mock(JpaIncomingEmailRepository.class);
        analysisRepo = mock(JpaEmailAnalysisRepository.class);
        mailboxRepo = mock(MailboxRepository.class);
        photoRepo = mock(GeoPhotoRepository.class);
        photoService = mock(GeoPhotoService.class);
        geoService = mock(GeoService.class);
        documentFacade = mock(DocumentFacade.class);
        ingestion = mock(DocumentIngestionProcessor.class);
        RefreshTokenSessionRepository refreshRepo = mock(RefreshTokenSessionRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        when(userRepo.findAll()).thenAnswer(i -> new ArrayList<>(knownUsers));
        when(userRepo.save(any(UserAccountEntity.class))).thenAnswer(i -> {
            UserAccountEntity u = i.getArgument(0);
            knownUsers.removeIf(x -> x.getId().equals(u.getId()));
            knownUsers.add(u);
            return u;
        });
        org.mockito.Mockito.doAnswer(i -> {
            knownUsers.remove(i.getArgument(0));
            return null;
        }).when(userRepo).delete(any(UserAccountEntity.class));

        when(workspaceService.findAll()).thenAnswer(i -> new ArrayList<>(knownWorkspaces));
        when(workspaceService.findByOwner(anyString())).thenAnswer(i -> knownWorkspaces.stream()
                .filter(w -> i.getArgument(0).equals(w.getOwnerId())).collect(Collectors.toList()));
        when(workspaceService.createWorkspace(any(CreateWorkspaceCommand.class))).thenAnswer(i -> {
            CreateWorkspaceCommand cmd = i.getArgument(0);
            WorkspaceEntity ws = new WorkspaceEntity("GV-" + (knownWorkspaces.size() + 1),
                    cmd.name(), cmd.description(), cmd.workspaceType(), cmd.createdBy());
            knownWorkspaces.add(ws);
            return ws;
        });
        org.mockito.Mockito.doAnswer(i -> {
            knownWorkspaces.removeIf(w -> w.getId().equals(i.getArgument(0)));
            return null;
        }).when(workspaceService).deleteWorkspace(anyString());
        when(workspaceService.getWorkspaceDocuments(anyString())).thenReturn(List.of());

        // Wie JPA: save() ist ein Upsert — das erneute Speichern derselben
        // Entität (z. B. Zuordnung + Vor-Analyse) dupliziert nicht.
        when(emailRepo.save(any(IncomingEmailEntity.class))).thenAnswer(i -> {
            IncomingEmailEntity e = i.getArgument(0);
            savedEmails.removeIf(x -> x.getId().equals(e.getId()));
            savedEmails.add(e);
            return e;
        });
        when(emailRepo.findAll()).thenAnswer(i -> new ArrayList<>(savedEmails));

        when(mailboxRepo.findAllByOrderByAddress()).thenAnswer(i -> new ArrayList<>(knownMailboxes));
        when(mailboxRepo.findByAddress(anyString())).thenAnswer(i -> knownMailboxes.stream()
                .filter(m -> m.getAddress().equals(i.getArgument(0))).findFirst());
        when(mailboxRepo.save(any(MailboxEntity.class))).thenAnswer(i -> {
            MailboxEntity m = i.getArgument(0);
            knownMailboxes.removeIf(x -> x.getId().equals(m.getId()));
            knownMailboxes.add(m);
            return m;
        });
        org.mockito.Mockito.doAnswer(i -> {
            knownMailboxes.clear();
            return null;
        }).when(mailboxRepo).deleteAll();

        when(photoService.resolve(anyString())).thenAnswer(i -> tempDir.resolve((String) i.getArgument(0)));
        when(photoService.createSanitizedCopy(any(), anyString())).thenAnswer(i -> {
            java.nio.file.Path src = i.getArgument(0);
            java.nio.file.Path target = tempDir.resolve("geo/display-test.jpg");
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(src, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "geo/display-test.jpg";
        });

        when(photoRepo.findAll()).thenAnswer(i -> new ArrayList<>(knownPhotos));
        when(photoRepo.save(any(GeoPhotoEntity.class))).thenAnswer(i -> {
            GeoPhotoEntity p = i.getArgument(0);
            knownPhotos.removeIf(x -> x.getId().equals(p.getId()));
            knownPhotos.add(p);
            return p;
        });
        org.mockito.Mockito.doAnswer(i -> {
            knownPhotos.clear();
            return null;
        }).when(photoRepo).deleteAll();

        DocumentPage emptyPage = mock(DocumentPage.class);
        when(emptyPage.documents()).thenReturn(List.of());
        when(emptyPage.totalElements()).thenReturn(0L);
        when(documentFacade.findDocuments(any())).thenReturn(emptyPage);
        when(documentFacade.createDocument(any(CreateDocumentCommand.class))).thenAnswer(i -> {
            Document doc = mock(Document.class);
            when(doc.id()).thenReturn(UUID.randomUUID());
            return doc;
        });
        when(documentFacade.getDocument(any(), anyString())).thenAnswer(i -> {
            Document doc = mock(Document.class);
            when(doc.metadata()).thenReturn(null);
            return doc;
        });
        when(documentFacade.createIngestionJob(any(), anyString())).thenAnswer(i -> {
            DocumentIngestionJob job = mock(DocumentIngestionJob.class);
            when(job.id()).thenReturn(UUID.randomUUID());
            return job;
        });

        when(geoService.jurisdictionFor(anyDouble(), anyDouble()))
                .thenReturn(new Jurisdiction("Potsdam", "Stadtverwaltung Potsdam", true));

        // Two persistent originals: one with GPS, one without.
        Path images = tempDir.resolve("photos");
        Files.createDirectories(images);
        ExifJpegHelper.writeGpsJpeg(images.resolve("gps-demo.jpg"), 52.5, 13.4, "N", "E");
        Files.write(images.resolve("ohne-gps.png"),
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});

        service = new DemoDataService(userRepo, workspaceService, emailRepo, analysisRepo,
                mailboxRepo, photoRepo, photoService, geoService, documentFacade, ingestion,
                refreshRepo, passwordEncoder, tempDir.toString());
    }

    private void stubPhotoExtraction() {
        // Alle synthetischen Brandenburg-Demo-Fotos tragen deterministisches
        // EXIF-GPS → die Extraktion liefert für jeden Katalog-Eintrag GPS.
        when(photoService.extract(anyString()))
                .thenReturn(new ExtractedGps(52.5, 13.4, Instant.parse("2026-08-10T07:15:00Z"),
                        "Top, left", true));
        // the real GeoPhotoService.applyMetadata copies the fields and persists
        when(photoService.applyMetadata(any(GeoPhotoEntity.class), any(ExtractedGps.class))).thenAnswer(i -> {
            GeoPhotoEntity p = i.getArgument(0);
            ExtractedGps g = i.getArgument(1);
            p.setLatitude(g.latitude());
            p.setLongitude(g.longitude());
            p.setCapturedAt(g.capturedAt());
            p.setOrientation(g.orientation());
            p.setHasGeo(g.hasGps());
            knownPhotos.removeIf(x -> x.getId().equals(p.getId()));
            knownPhotos.add(p);
            return p;
        });
    }

    /**
     * Startup reset of the e-mail queue (DemoStateResetter path): a catalog
     * e-mail with demo-run processing state (analysis link, case association,
     * IN_PROGRESS) returns to its catalog definition — analysis link AND
     * Fall-Zuordnung are cleared, so a second demo run starts cleanly without
     * dangling references to deleted cases.
     */
    @Test
    void resetEmailQueue_clearsAnalysisLinkAndCaseAssociation() {
        IncomingEmailEntity email = new IncomingEmailEntity(
                UUID.randomUUID(), "Wohngeldantrag – welche Unterlagen fehlen noch?",
                "Erika Schulze", "erika.schulze@example.de",
                "Betreff: Wohngeldantrag\n\nSehr geehrte Damen und Herren, ich habe meinen Antrag eingereicht.",
                Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, DemoDataService.MAILBOX_KONTAKT);
        email.setStatus(IncomingEmailEntity.Status.IN_PROGRESS);
        email.setAnalysisId(UUID.randomUUID());
        email.setWorkspaceId(UUID.randomUUID());
        when(emailRepo.findAll()).thenReturn(List.of(email));
        savedEmails.clear();

        service.resetEmailQueue();

        assertNull(email.getAnalysisId(), "analysis link is reset to the catalog state");
        assertNull(email.getWorkspaceId(), "case association is reset to the catalog state");
        assertEquals(IncomingEmailEntity.Status.NEW, email.getStatus(),
                "processing status returns to the catalog definition");
    }

    /**
     * Issue 6: Der Demo-Korpus erhält das Wissensdokument "Straßenbeleuchtung"
     * (idempotent). Existiert noch kein Dokument mit diesem Titel, wird die
     * Quelldatei abgelegt, das Dokument über die bestehende Pipeline angelegt
     * und indexiert; bei erneutem Aufruf wird nichts dupliziert. Das Dokument
     * enthält die Suchbegriffe der Straßenbeleuchtungs-E-Mail ("Straßenlaterne",
     * "Beleuchtung"), damit die Suche einen thematisch passenden Treffer statt
     * schwacher Nächst-Nachbarn (GewO/BMG) liefern kann.
     */
    @Test
    void seedStreetLightingKnowledge_createsDocumentWhenMissingAndIsIdempotent() throws Exception {
        when(documentFacade.findDocuments(any(DocumentFilter.class)))
                .thenReturn(new DocumentPage(List.of(), 0, 0, 0, 0));
        when(documentFacade.createDocument(any(CreateDocumentCommand.class))).thenAnswer(inv -> {
            CreateDocumentCommand cmd = inv.getArgument(0);
            return new Document(UUID.randomUUID(), "tenant",
                    new DocumentMetadata(cmd.title(), DocumentFileType.TXT, "OTHER", Set.of(), "INTERNAL"),
                    DocumentStatus.READY, 1, "admin@verwaltungsassistent.local", "admin@verwaltungsassistent.local",
                    Instant.now(), Instant.now(), List.of());
        });
        DocumentIngestionJob job = new DocumentIngestionJob(UUID.randomUUID(), UUID.randomUUID(),
                reasoning.common.model.IngestionStatus.PENDING,
                "system", "admin@verwaltungsassistent.local", "default", null, Instant.now(), null, null, 1);
        when(documentFacade.createIngestionJob(any(UUID.class), anyString())).thenReturn(job);
        when(documentFacade.startIngestion(any(UUID.class), anyString())).thenReturn(job);

        service.seedStreetLightingKnowledge();

        org.mockito.ArgumentCaptor<CreateDocumentCommand> captor =
                org.mockito.ArgumentCaptor.forClass(CreateDocumentCommand.class);
        verify(documentFacade).createDocument(captor.capture());
        assertEquals(DemoDataService.STREET_LIGHTING_DOC_TITLE, captor.getValue().title());
        // Die Quelldatei liegt im Upload-Verzeichnis und enthält die Suchbegriffe.
        Path source = tempDir.resolve("knowledge/Straßenbeleuchtung-Zustaendigkeit.txt");
        assertTrue(Files.exists(source), "source file must be written to the upload dir");
        String content = new String(Files.readAllBytes(source), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.toLowerCase().contains("straßenlaterne"), "document must contain the query term");
        assertTrue(content.toLowerCase().contains("beleuchtung"), "document must contain the query term");
        verify(ingestion).ingest(any(UUID.class));

        // Idempotent: ein zweiter Lauf findet das Dokument und legt nichts an.
        Document existing = new Document(UUID.randomUUID(), "tenant",
                new DocumentMetadata(DemoDataService.STREET_LIGHTING_DOC_TITLE, DocumentFileType.TXT,
                        "OTHER", Set.of(), "INTERNAL"),
                DocumentStatus.READY, 1, "admin@verwaltungsassistent.local", "admin@verwaltungsassistent.local",
                Instant.now(), Instant.now(), List.of());
        when(documentFacade.findDocuments(any(DocumentFilter.class)))
                .thenReturn(new DocumentPage(List.of(existing), 1, 0, 1, 1));
        service.seedStreetLightingKnowledge();
        verify(documentFacade, org.mockito.Mockito.times(1)).createDocument(any(CreateDocumentCommand.class));
    }

    @Test
    void resetRecreatesUsersMailboxesEmailsAndPhotos() {
        stubPhotoExtraction();

        int created = service.reset();

        assertTrue(created > 0);

        // 20 deterministic demo users demo01..demo20@verwaltungsassistent.local
        ArgumentCaptor<UserAccountEntity> userCaptor = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(userRepo, times(20)).save(userCaptor.capture());
        Set<String> emails = userCaptor.getAllValues().stream()
                .map(UserAccountEntity::getEmail).collect(Collectors.toSet());
        assertEquals(20, emails.size());
        assertTrue(emails.contains("demo01@verwaltungsassistent.local"));
        assertTrue(emails.contains("demo20@verwaltungsassistent.local"));

        // 2 general mailboxes as real recipient records
        ArgumentCaptor<MailboxEntity> mailboxCaptor = ArgumentCaptor.forClass(MailboxEntity.class);
        verify(mailboxRepo, times(2)).save(mailboxCaptor.capture());
        Set<String> addresses = mailboxCaptor.getAllValues().stream()
                .map(MailboxEntity::getAddress).collect(Collectors.toSet());
        assertTrue(addresses.contains(DemoDataService.MAILBOX_KONTAKT));
        assertTrue(addresses.contains(DemoDataService.MAILBOX_INFO));

        // controlled e-mail catalog: 31 distinct mails (inkl. Abschluss-Szenarien), > 1 page, distributed
        assertEquals(32, savedEmails.size());
        assertEquals(32, savedEmails.stream().map(IncomingEmailEntity::getSubject).collect(Collectors.toSet()).size());
        assertTrue(savedEmails.stream().anyMatch(e -> "demo01@verwaltungsassistent.local".equals(e.getAddressedToEmail())));
        assertTrue(savedEmails.stream().anyMatch(e -> DemoDataService.MAILBOX_KONTAKT.equals(e.getAddressedToEmail())));
        assertTrue(savedEmails.stream().anyMatch(e -> DemoDataService.MAILBOX_INFO.equals(e.getAddressedToEmail())));
        assertTrue(savedEmails.stream().anyMatch(e -> e.getStatus() == IncomingEmailEntity.Status.NEW));
        assertTrue(savedEmails.stream().anyMatch(e -> e.getStatus() == IncomingEmailEntity.Status.COMPLETED));

        // 14 synthetic Brandenburg photos, alle mit GPS → je ein Geovorgang
        assertEquals(14, knownPhotos.size());
        assertTrue(knownPhotos.stream().allMatch(GeoPhotoEntity::isHasGeo),
                "every synthetic demo photo carries GPS");
        GeoPhotoEntity gpsPhoto = knownPhotos.stream()
                .filter(GeoPhotoEntity::isHasGeo).findFirst().orElseThrow();
        assertEquals("EXIF", gpsPhoto.getGpsSource());
        assertNotNull(gpsPhoto.getWorkspaceId());
        assertEquals(52.5, gpsPhoto.getLatitude(), 1e-9);
        assertEquals(13.4, gpsPhoto.getLongitude(), 1e-9);

        // Geovorgang carries the photo coordinates and the deterministic jurisdiction
        List<WorkspaceEntity> geoCases = knownWorkspaces.stream()
                .filter(w -> "GEO".equals(w.getWorkspaceType())).toList();
        assertEquals(14, geoCases.size());
        WorkspaceEntity geoCase = geoCases.get(0);
        assertEquals(52.5, geoCase.getGeoLatitude(), 1e-9);
        assertEquals(13.4, geoCase.getGeoLongitude(), 1e-9);
        assertEquals("Potsdam", geoCase.getGeoDistrict());
        assertEquals("Stadtverwaltung Potsdam", geoCase.getGeoAuthority());
        assertTrue(geoCase.getPhaseDataMap().containsKey("geoCategory"));

        // photos become ordinary evidence documents + indexing runs
        verify(documentFacade, times(14)).createDocument(any(CreateDocumentCommand.class));
        verify(ingestion, times(14)).ingest(any());
    }

    @Test
    void resetDoesNotDeletePersistentOriginalFiles() throws Exception {
        stubPhotoExtraction();
        Path original = tempDir.resolve("photos/gps-demo.jpg");
        byte[] before = Files.readAllBytes(original);

        service.reset();

        assertTrue(Files.exists(original));
        byte[] after = Files.readAllBytes(original);
        assertTrue(java.util.Arrays.equals(before, after));
    }

    @Test
    void resetIsRepeatableWithoutDuplicates() {
        stubPhotoExtraction();

        service.reset();
        Set<String> firstSubjects = savedEmails.stream()
                .map(IncomingEmailEntity::getSubject).collect(Collectors.toSet());
        savedEmails.clear();
        service.reset();

        // same logical dataset on the second run — no appended duplicates
        assertEquals(32, savedEmails.size()); // 32 Katalog-E-Mails (inkl. Abschluss-Szenarien + Folge-E-Mail)
        assertEquals(firstSubjects, savedEmails.stream()
                .map(IncomingEmailEntity::getSubject).collect(Collectors.toSet()));
        assertEquals(20, knownUsers.size());
        assertEquals(2, knownMailboxes.size());
        assertEquals(14, knownPhotos.size());
        assertEquals(57, knownWorkspaces.size()); // 40 Fall-Workspaces + 3 Abschluss-Vorgänge + 14 Geovorgänge
        assertEquals(14, knownWorkspaces.stream().filter(w -> "GEO".equals(w.getWorkspaceType())).count());
    }

    /**
     * Kohärente Abschluss-Szenarien: Erledigt-Katalog-E-Mails sind ihrem
     * abgeschlossenen Vorgang zugeordnet (workspaceId) und tragen ein
     * deterministisches Vor-Analyse-Ergebnis (analysisId) — eine erledigte
     * E-Mail wirkt nie wie eine unverarbeitete.
     */
    @Test
    void seedCompletedScenario_linksErledigtEmailsToClosedCases() {
        stubPhotoExtraction();
        service.reset();

        IncomingEmailEntity danke = savedEmails.stream()
                .filter(e -> "Danke – Müllsäcke abgeholt".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertNotNull(danke.getWorkspaceId(), "Erledigt-E-Mail muss dem Vorgang zugeordnet sein");
        assertNotNull(danke.getAnalysisId(), "Erledigt-E-Mail muss ein Vor-Analyse-Ergebnis tragen");

        WorkspaceEntity caseWs = knownWorkspaces.stream()
                .filter(w -> "Müllsäcke abgeholt".equals(w.getName()))
                .findFirst().orElseThrow();
        assertEquals(caseWs.getId(), danke.getWorkspaceId().toString());
        assertEquals(WorkspaceStatus.CLOSED, caseWs.getStatus());
        assertEquals(WorkspacePhase.COMPLETE, caseWs.getPhase());

        // Der zweite Eintrag desselben Szenarios (ursprüngliche Meldung) ist
        // demselben Vorgang zugeordnet → Kommunikationsverlauf möglich.
        IncomingEmailEntity meldung = savedEmails.stream()
                .filter(e -> "Müllsäcke vor der Sammelstelle".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertEquals(caseWs.getId(), meldung.getWorkspaceId().toString());
    }

    /**
     * Phase 2C.1: Threading-Header + voranalysierte Neueingänge.
     * - Der Müllsäcke-Thread trägt messageId/inReplyTo/references-Header
     *   (Header-basierte Thread-/Fall-Erkennung statt nur semantischem Match).
     * - Szenario A: ein Neuanliegen ist voranalysiert (kein Falltreffer).
     * - Szenario D: ein Neuanliegen ist voranalysiert mit unsicherem Treffer.
     */
    @Test
    void seedPreAnalyzedEmails_threadingHeadersAndPreAnalysis() {
        stubPhotoExtraction();
        service.reset();

        IncomingEmailEntity erste = savedEmails.stream()
                .filter(e -> "Müllsäcke vor der Sammelstelle".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertEquals("msg-muell-2026-08-29", erste.getMessageId());
        IncomingEmailEntity danke = savedEmails.stream()
                .filter(e -> "Danke – Müllsäcke abgeholt".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertEquals("msg-muell-2026-08-29", danke.getInReplyTo());
        IncomingEmailEntity folge = savedEmails.stream()
                .filter(e -> "Müllsäcke erneut an der Sammelstelle".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertEquals("msg-muell-2026-08-29", folge.getReferences());
        assertEquals(IncomingEmailEntity.Status.NEW, folge.getStatus());
        assertEquals(IncomingEmailEntity.SourceType.MAILBOX, folge.getSourceType());

        // Szenario A: Neuanliegen voranalysiert.
        IncomingEmailEntity hundesteuer = savedEmails.stream()
                .filter(e -> "Hundesteuer – Anmeldung eines Hundes".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertNotNull(hundesteuer.getAnalysisId(), "Neuanliegen (Szenario A) muss voranalysiert sein");

        // Szenario D: unsicherer Treffer voranalysiert.
        IncomingEmailEntity wohngeld = savedEmails.stream()
                .filter(e -> "Wohngeldantrag – welche Unterlagen fehlen noch?".equals(e.getSubject()))
                .findFirst().orElseThrow();
        assertNotNull(wohngeld.getAnalysisId(), "Szenario-D-E-Mail muss voranalysiert sein");
        assertTrue(wohngeld.getAnalysisId() != null && wohngeld.getAnalysisId() != hundesteuer.getAnalysisId(),
                "jede E-Mail hat ihre eigene Vor-Analyse");
    }

    @Test
    void seedEmailQueueIfEmptySeedsOnlyWhenEmpty() {
        when(emailRepo.count()).thenReturn(0L);
        int created = service.seedEmailQueueIfEmpty();
        // 32 Katalog-E-Mails + 2 Postfächer + 3 Abschluss-Vorgänge + 6 Vor-Analysen
        assertEquals(43, created);
        assertEquals(2, knownMailboxes.size());
        assertEquals(32, savedEmails.size());

        // queue already populated → nothing appended
        savedEmails.clear();
        when(emailRepo.count()).thenReturn(5L);
        assertEquals(0, service.seedEmailQueueIfEmpty());
        assertTrue(savedEmails.isEmpty());
    }

    @Test
    void resetCleansOldDemoState() {
        // old demo user with a workspace, old email/photo/mailbox records
        UserAccountEntity oldUser = new UserAccountEntity("demo03@verwaltungsassistent.local", "x", "Alt",
                Set.of(reasoning.auth.model.Role.USER));
        knownUsers.add(oldUser);
        WorkspaceEntity oldWs = new WorkspaceEntity("GV-99", "Alter Geovorgang", "alt", "GEO", "demo03@verwaltungsassistent.local");
        knownWorkspaces.add(oldWs);
        // legacy admin test case that is not part of the seeded demo state
        WorkspaceEntity legacyAdminCase = new WorkspaceEntity("WS-ALT2", "Umzug nach Berlin (Test)",
                "alt", "CASE", DemoDataService.DEMO_OWNER);
        knownWorkspaces.add(legacyAdminCase);
        // the seeded e-mail workflow cases stay untouched
        WorkspaceEntity seededCase = new WorkspaceEntity("WS-MUELLER", "Fall Müller – Wohngeld",
                "Wohngeld", "CASE", DemoDataService.DEMO_OWNER);
        knownWorkspaces.add(seededCase);
        knownMailboxes.add(new MailboxEntity(UUID.randomUUID(), "alt@verwaltungs-demo.de", "Alt", "alt"));
        knownPhotos.add(new GeoPhotoEntity(UUID.randomUUID(), "alt.jpg", "photos/alt.jpg", "image/jpeg",
                "demo03@verwaltungsassistent.local", Instant.now()));
        savedEmails.add(new IncomingEmailEntity(UUID.randomUUID(), "Alte E-Mail", "Alt",
                "alt@example.de", "Text", Instant.now(), IncomingEmailEntity.AddressedTo.GENERAL, null));

        stubPhotoExtraction();
        service.reset();

        verify(workspaceService).deleteWorkspace(oldWs.getId().toString());
        verify(workspaceService).deleteWorkspace(legacyAdminCase.getId().toString());
        verify(userRepo).delete(oldUser);
        assertFalse(knownMailboxes.stream().anyMatch(m -> m.getAddress().startsWith("alt@")));
        assertFalse(knownPhotos.stream().anyMatch(p -> p.getOriginalName().startsWith("alt")));
        verify(emailRepo).deleteAll();
        verify(analysisRepo).deleteAll();
        assertTrue(knownWorkspaces.stream().noneMatch(w -> "Alter Geovorgang".equals(w.getName())));
        assertTrue(knownWorkspaces.stream().noneMatch(w -> "Umzug nach Berlin (Test)".equals(w.getName())));
        // the seeded e-mail workflow case survives the reset
        assertTrue(knownWorkspaces.stream().anyMatch(w -> "Fall Müller – Wohngeld".equals(w.getName())));
    }

    @Test
    void geocaseFromPhotoPreservesOriginalCoordinates() {
        stubPhotoExtraction();
        service.reset();
        // coordinates flow photo → GeoService → Geovorgang without drift
        GeoPhotoEntity photo = knownPhotos.stream().filter(GeoPhotoEntity::isHasGeo).findFirst().orElseThrow();
        WorkspaceEntity geoCase = knownWorkspaces.stream()
                .filter(w -> "GEO".equals(w.getWorkspaceType())).findFirst().orElseThrow();
        assertEquals(photo.getLatitude(), geoCase.getGeoLatitude());
        assertEquals(photo.getLongitude(), geoCase.getGeoLongitude());
    }
}
