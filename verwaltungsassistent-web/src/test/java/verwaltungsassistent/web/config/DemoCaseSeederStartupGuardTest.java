package verwaltungsassistent.web.config;

import reasoning.document.api.DocumentFacade;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.analysis.persistence.JpaEmailAnalysisRepository;
import verwaltungsassistent.web.analysis.persistence.JpaIncomingEmailRepository;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import verwaltungsassistent.web.service.DemoDataService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 2D.15 — Startup-Guards des DemoSeeders: Im Vorbereitungslauf
 * ({@code demo.prepare.enabled=true}) und beim Start in den vorbereiteten
 * Zustand ({@code demo.startup.reset=false}) darf der Seeder weder den
 * Demo-Reset ausführen noch seeden.
 */
class DemoCaseSeederStartupGuardTest {

    private DemoCaseSeeder newSeeder() {
        return new DemoCaseSeeder(
                mock(WorkspaceService.class),
                mock(DocumentFacade.class),
                mock(JpaEmailAnalysisRepository.class),
                mock(JpaIncomingEmailRepository.class),
                mock(JpaCasePlanningRepository.class),
                mock(DemoDataService.class),
                mock(DemoStateResetter.class),
                false, 0, 0, null);
    }

    @Test
    void prepareEnabled_skipsResetAndSeeding() {
        DemoCaseSeeder seeder = newSeeder();
        ReflectionTestUtils.setField(seeder, "prepareEnabled", true);
        DemoStateResetter resetter = (DemoStateResetter) ReflectionTestUtils.getField(seeder, "demoStateResetter");

        seeder.run();

        verify(resetter, never()).reset();
    }

    @Test
    void startupResetDisabled_keepsPreparedState() {
        DemoCaseSeeder seeder = newSeeder();
        ReflectionTestUtils.setField(seeder, "startupReset", false);
        DemoStateResetter resetter = (DemoStateResetter) ReflectionTestUtils.getField(seeder, "demoStateResetter");

        seeder.run();

        verify(resetter, never()).reset();
    }
}
