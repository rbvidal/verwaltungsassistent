package verwaltungsassistent.web.service;

import java.util.List;
import java.util.Locale;

import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.geo.GeoPhotoEntity;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministische GEO-Verteilung: Jede Demo-Mitarbeiterin (demo01..demo20)
 * hat nach der Demo-Generierung mindestens zwei eigene Geovorgänge, und jeder
 * Geovorgang existiert als gültiger Workspace; jeder Foto→Vorgang-Link löst
 * auf einen existierenden Vorgang auf ("Vorgang öffnen" -> nie "Fall nicht
 * gefunden").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DemoGeoDistributionTest {

    @Autowired
    private DemoDataService demoDataService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private GeoPhotoRepository photoRepository;

    private static String demoEmail(int n) {
        return String.format(Locale.ROOT, "demo%02d@verwaltungsassistent.local", n);
    }

    @Test
    void everyDemoEmployeeHasAtLeastTwoGeoVorgaenge() {
        demoDataService.generate();

        List<WorkspaceEntity> geo = workspaceService.findAll().stream()
                .filter(w -> "GEO".equalsIgnoreCase(w.getWorkspaceType()))
                .toList();
        for (int emp = 1; emp <= 20; emp++) {
            String email = demoEmail(emp);
            long owned = geo.stream()
                    .filter(w -> email.equalsIgnoreCase(w.getOwnerId()))
                    .count();
            assertTrue(owned >= 2,
                    demoEmail(emp) + " muss >=2 eigene Geovorgänge haben, hat " + owned);
        }
    }

    @Test
    void everyGeoVorgangIsAValidWorkspace() {
        demoDataService.generate();
        List<WorkspaceEntity> geo = workspaceService.findAll().stream()
                .filter(w -> "GEO".equalsIgnoreCase(w.getWorkspaceType()))
                .toList();
        for (WorkspaceEntity ws : geo) {
            assertTrue(ws.getId() != null && ws.getName() != null && !ws.getName().isBlank(),
                    "Geovorgang ohne gültigen Workspace/Name: " + ws.getId());
        }
    }

    @Test
    void photoToGeoCaseLinksResolveToExistingWorkspaces() {
        demoDataService.generate();
        for (GeoPhotoEntity p : photoRepository.findAll()) {
            if (p.getWorkspaceId() == null) {
                continue;
            }
            assertTrue(workspaceService.findById(p.getWorkspaceId().toString()).isPresent(),
                    "Foto '" + p.getOriginalName() + "' verweist auf fehlenden Vorgang "
                            + p.getWorkspaceId());
        }
    }
}
