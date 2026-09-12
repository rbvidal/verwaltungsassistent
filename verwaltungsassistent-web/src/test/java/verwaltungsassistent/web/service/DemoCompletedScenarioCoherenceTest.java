package verwaltungsassistent.web.service;

import reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.common.model.WorkspacePhase;
import reasoning.common.model.WorkspaceStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentIngestionProcessor;
import reasoning.workspace.api.TimelineEventEntity;
import reasoning.workspace.api.WorkspaceAnalysisRunEntity;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.analysis.persistence.MailboxRepository;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2D.16 — Kohärenz der abgeschlossenen Demo-Abschluss-Szenarien: Ein vom
 * Seeder erzeugter CLOSED-Vorgang muss eine echte zuständige Mitarbeiterin,
 * eine dokumentierte Entscheidung (inkl. Analyse-Lauf, auf den sie sich
 * bezieht), Abschluss-Zeitpunkt/-Person, eine abgeschlossene COMPLETE-
 * Checkliste und Verlaufseinträge besitzen. Keine „Geschlossen ohne
 * Entscheidung"-Zustände.
 */
class DemoCompletedScenarioCoherenceTest {

    @TempDir
    Path tempDir;

    private UserAccountRepository userRepo;
    private WorkspaceService workspaceService;
    private JpaIncomingEmailRepository emailRepo;
    private JpaEmailAnalysisRepository analysisRepo;
    private MailboxRepository mailboxRepo;
    private GeoPhotoRepository photoRepo;
    private GeoPhotoService photoService;
    private DemoDataService service;

    private final List<WorkspaceEntity> knownWorkspaces = new ArrayList<>();
    private final java.util.Map<String, List<TimelineEventEntity>> eventsByWorkspace =
            new java.util.HashMap<>();
    private final WorkspaceAnalysisRunEntity run = new WorkspaceAnalysisRunEntity(
            UUID.randomUUID(), UUID.randomUUID(), 1, "COMPLETED",
            "demo02@verwaltungsassistent.local", Instant.now().minusSeconds(600));

    @BeforeEach
    void setUp() {
        userRepo = mock(UserAccountRepository.class);
        workspaceService = mock(WorkspaceService.class);
        emailRepo = mock(JpaIncomingEmailRepository.class);
        analysisRepo = mock(JpaEmailAnalysisRepository.class);
        mailboxRepo = mock(MailboxRepository.class);
        photoRepo = mock(GeoPhotoRepository.class);
        photoService = mock(GeoPhotoService.class);
        verwaltungsassistent.web.service.GeoService geoService =
                mock(verwaltungsassistent.web.service.GeoService.class);
        DocumentFacade documentFacade = mock(DocumentFacade.class);
        DocumentIngestionProcessor ingestion = mock(DocumentIngestionProcessor.class);
        RefreshTokenSessionRepository refreshRepo = mock(RefreshTokenSessionRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(emailRepo.findAll()).thenReturn(new ArrayList<>());
        when(mailboxRepo.findAllByOrderByAddress()).thenReturn(new ArrayList<>());
        when(mailboxRepo.findByAddress(anyString())).thenReturn(Optional.empty());
        when(photoRepo.findAll()).thenReturn(new ArrayList<>());
        when(userRepo.findByEmail(anyString())).thenAnswer(i -> {
            String email = i.getArgument(0);
            UserAccountEntity u = new UserAccountEntity(email, "hash", "Demo Mitarbeiterin",
                    java.util.Set.of());
            return Optional.of(u);
        });

        when(workspaceService.findAll()).thenAnswer(i -> new ArrayList<>(knownWorkspaces));
        when(workspaceService.getWorkspaceDocuments(anyString())).thenReturn(List.of());
        when(workspaceService.getTimeline(anyString())).thenAnswer(i ->
                new ArrayList<>(eventsByWorkspace.getOrDefault((String) i.getArgument(0),
                        List.of())));
        doAnswer(i -> {
            knownWorkspaces.removeIf(w -> w.getId().equals(i.getArgument(0)));
            return null;
        }).when(workspaceService).deleteWorkspace(anyString());
        doAnswer(i -> {
            WorkspaceEntity ws = i.getArgument(0);
            knownWorkspaces.removeIf(w -> w.getId().equals(ws.getId()));
            knownWorkspaces.add(ws);
            return null;
        }).when(workspaceService).save(any(WorkspaceEntity.class));
        when(workspaceService.createWorkspace(any())).thenAnswer(i -> {
            reasoning.workspace.api.CreateWorkspaceCommand cmd = i.getArgument(0);
            WorkspaceEntity ws = new WorkspaceEntity("WS-X", cmd.name(), cmd.description(),
                    cmd.workspaceType(), cmd.createdBy());
            ws.setId(UUID.randomUUID().toString());
            ws.setStatus(WorkspaceStatus.DRAFT);
            ws.setPhase(WorkspacePhase.SETUP);
            knownWorkspaces.add(ws);
            return ws;
        });
        // Erster Aufruf: noch kein Lauf → Fixture wird angelegt; weitere
        // Aufrufe (Version für phaseData.decision) sehen den Lauf.
        when(workspaceService.latestCompletedAnalysisRun(anyString()))
                .thenReturn(Optional.empty(), Optional.of(run), Optional.of(run),
                        Optional.empty(), Optional.of(run), Optional.of(run),
                        Optional.empty(), Optional.of(run), Optional.of(run));
        when(workspaceService.startAnalysisRun(anyString(), anyString())).thenReturn(1);
        when(workspaceService.deserializeAnalysisResult(run)).thenReturn(null);
        doAnswer(i -> {
            TimelineEventEntity ev = new TimelineEventEntity();
            ev.setTitle(i.getArgument(2));
            eventsByWorkspace.computeIfAbsent((String) i.getArgument(0),
                    k -> new ArrayList<>()).add(ev);
            return ev;
        }).when(workspaceService).addTimelineEvent(anyString(), any(), anyString(), anyString(),
                any(), any(), anyDouble(), anyBoolean());

        service = new DemoDataService(userRepo, workspaceService, emailRepo, analysisRepo,
                mailboxRepo, photoRepo, photoService, geoService, documentFacade, ingestion,
                refreshRepo, passwordEncoder, tempDir.toString());
    }

    private WorkspaceEntity scenario(String name) {
        WorkspaceEntity ws = new WorkspaceEntity("WS-" + name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(),
                name, "Demo-Abschluss-Szenario", "CASE", null);
        ws.setId(UUID.randomUUID().toString());
        ws.setStatus(WorkspaceStatus.CLOSED);
        ws.setPhase(WorkspacePhase.COMPLETE);
        ws.setPhaseData("{\"demo\": true}");
        ws.setCreatedAt(Instant.now().minusSeconds(3600));
        knownWorkspaces.add(ws);
        return ws;
    }

    @Test
    void completedScenarios_getCoherentEndState() {
        scenario(DemoDataService.CASE_MUELLSAECKE);
        scenario(DemoDataService.CASE_STRASSENLATERNE);
        scenario(DemoDataService.CASE_TERMIN);

        int created = service.seedCompletedScenario();

        assertEquals(3, knownWorkspaces.size(), "scenario workspaces exist");
        assertTrue(created > 0, "end-state convergence reports changes");
        for (WorkspaceEntity ws : knownWorkspaces) {
            assertNotNull(ws.getOwnerId(), "closed scenario has a responsible employee owner");
            assertTrue(ws.getOwnerId().startsWith("demo"),
                    "owner is a real demo employee, not the supervisory account: " + ws.getOwnerId());
            assertEquals(WorkspaceStatus.CLOSED, ws.getStatus());
            assertEquals(WorkspacePhase.COMPLETE, ws.getPhase());

            Map<String, Object> data = ws.getPhaseDataMap();
            assertTrue(data.get("decision") instanceof Map<?, ?>,
                    "a closed demo case must carry a documented decision");
            Map<?, ?> decision = (Map<?, ?>) data.get("decision");
            assertEquals(ws.getOwnerId(), String.valueOf(decision.get("confirmedBy")),
                    "decision actor = responsible employee");
            assertNotNull(decision.get("analysisVersion"), "decision references the analysis version");
            assertNotNull(data.get("closedAt"), "closedAt is recorded (real closure shape)");
            assertEquals(ws.getOwnerId(), String.valueOf(data.get("closedBy")),
                    "closedBy = responsible employee");
            assertTrue(data.get("checklist") instanceof List<?> checklist && checklist.size() == 3,
                    "COMPLETE checklist is persisted with 3 items");
        }
        // Je Szenario genau ein Fixture-Lauf; Verlaufseinträge enthalten
        // Entscheidung + Abschluss.
        verify(workspaceService, times(3)).startAnalysisRun(anyString(), anyString());
        verify(workspaceService, times(3)).recordAnalysisStatus(anyString(),
                org.mockito.ArgumentMatchers.eq("COMPLETED"), any());
        List<String> titles = eventsByWorkspace.values().stream()
                .flatMap(List::stream).map(TimelineEventEntity::getTitle).toList();
        assertTrue(titles.contains("Entscheidung dokumentiert"), "history records the documented decision");
        assertTrue(titles.contains("Fall geschlossen"), "history records the closure");

        // Idempotenz: ein zweiter Seed-Lauf erzeugt keine zweiten Fixture-
        // Läufe und keine doppelten Verlaufseinträge.
        int eventsBefore = eventsByWorkspace.values().stream().mapToInt(List::size).sum();
        service.seedCompletedScenario();
        verify(workspaceService, times(3)).startAnalysisRun(anyString(), anyString());
        int eventsAfter = eventsByWorkspace.values().stream().mapToInt(List::size).sum();
        assertEquals(eventsBefore, eventsAfter,
                "no duplicate timeline entries on re-seed");
        long decisionEvents = eventsByWorkspace.values().stream().flatMap(List::stream)
                .filter(ev -> "Entscheidung dokumentiert".equals(ev.getTitle())).count();
        assertEquals(3, decisionEvents, "exactly one documented-decision event per scenario");
    }
}
