package verwaltungsassistent.web.config;

import reasoning.common.model.WorkspacePhase;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentPage;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import verwaltungsassistent.web.service.DemoDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test: demo case seeding is idempotent, creates the distinct
 * e-mail workflow cases, resets their phase to SETUP, and delegates the
 * Geovorgänge/photograph import to the deterministic DemoDataService.
 */
@ExtendWith(MockitoExtension.class)
class DemoCaseSeederTest {

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private DocumentFacade documentFacade;
    @Mock
    private JpaEmailAnalysisRepository emailAnalysisRepository;
    @Mock
    private JpaIncomingEmailRepository incomingEmailRepository;
    @Mock
    private JpaCasePlanningRepository casePlanningRepository;
    @Mock
    private DemoDataService demoDataService;
    @Mock
    private DemoStateResetter demoStateResetter;

    private DemoCaseSeeder seeder;

    @BeforeEach
    void setUp() {
        // Phase 2D.11: Overnight-Trigger-Standard AUS (false, limit 0, offset 0, kein Prozessor).
        seeder = new DemoCaseSeeder(workspaceService, documentFacade, emailAnalysisRepository,
                incomingEmailRepository, casePlanningRepository,
                demoDataService, demoStateResetter, false, 0, 0, null);
        when(workspaceService.findAll()).thenReturn(List.of());
        when(emailAnalysisRepository.findAll()).thenReturn(List.of());
        when(incomingEmailRepository.findByMessageId(any())).thenReturn(java.util.Optional.empty());
        when(workspaceService.getWorkspaceDocuments(any())).thenReturn(List.of());
        when(documentFacade.findDocuments(any())).thenReturn(new DocumentPage(List.of(), 0, 0, 0, 0));
        when(workspaceService.createWorkspace(any())).thenAnswer(inv -> {
            CreateWorkspaceCommand c = inv.getArgument(0);
            return new WorkspaceEntity("WS-DEMO", c.name(), c.description(), c.workspaceType(), c.createdBy());
        });
    }

    @Test
    void run_createsMissingDemoCasesAndDelegatesPhotoImport() {
        seeder.run();

        ArgumentCaptor<CreateWorkspaceCommand> cmd = ArgumentCaptor.forClass(CreateWorkspaceCommand.class);
        // the 5 e-mail workflow cases — Geovorgänge come from DemoDataService
        verify(workspaceService, times(5)).createWorkspace(cmd.capture());
        assertTrue(cmd.getAllValues().stream().anyMatch(c -> c.name().contains("Wohngeld")));
        assertTrue(cmd.getAllValues().stream().anyMatch(c -> c.name().contains("Carport")));
        assertTrue(cmd.getAllValues().stream().anyMatch(c -> c.name().contains("Ummeldung")));
        assertTrue(cmd.getAllValues().stream().anyMatch(c -> c.name().contains("Gewerbeanmeldung")));
        assertTrue(cmd.getAllValues().stream().anyMatch(c -> c.name().contains("Reisepass")));

        ArgumentCaptor<WorkspaceEntity> saved = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService, times(5)).save(saved.capture());
        for (WorkspaceEntity entity : saved.getAllValues()) {
            assertEquals(WorkspacePhase.SETUP, entity.getPhase());
        }
        long withSource = saved.getAllValues().stream()
                .filter(e -> e.getPhaseData().contains("\"source\"")).count();
        assertEquals(5, withSource);

        verify(demoDataService).importDemoPhotosIfMissing();
    }

    @Test
    void run_existingDemoCases_areReusedAndResetToSetup() {
        WorkspaceEntity existing = new WorkspaceEntity("WS-MUELLER", "Fall Müller – Wohngeld",
                "Wohngeldantrag von Erika Müller – Unterlagen unvollständig, Prüfung offen.",
                "CASE", "admin@verwaltungsassistent.local");
        existing.setPhase(WorkspacePhase.REVIEW);
        when(workspaceService.findAll()).thenReturn(List.of(existing));

        seeder.run();

        // 4 remaining e-mail cases are missing and get created
        verify(workspaceService, times(4)).createWorkspace(any());
        assertEquals(WorkspacePhase.SETUP, existing.getPhase());
        verify(workspaceService, times(1)).save(existing);
    }
}
