package verwaltungsassistent.web.e2e;

import reasoning.common.model.WorkspacePhase;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.planning.CaseWorkStateService;
import verwaltungsassistent.web.planning.ProcessingTimeConfig;
import verwaltungsassistent.web.planning.persistence.EffortEstimateEntity;
import verwaltungsassistent.web.planning.persistence.EffortObservationEntity;
import verwaltungsassistent.web.planning.persistence.JpaEffortEstimateRepository;
import verwaltungsassistent.web.planning.persistence.JpaEffortObservationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-Only-Endpunkte für die Playwright-E2E (playwright-Profil):
 * deterministische Steuerung von Uhr, Ausgangszustand und die Beobachtung
 * von Zustand, Lernstatistik und Fall-Eigenschaften über das Backend.
 * Produktionsprofile registrieren diese Controller-Klasse nie.
 */
@RestController
@RequestMapping("/test")
@Profile("playwright")
public class PlaywrightTestController {

    private final PlaywrightDataSeeder seeder;
    private final TestClock testClock;
    private final CaseWorkStateService caseWorkStateService;
    private final WorkspaceService workspaceService;
    private final JpaEffortObservationRepository observationRepository;
    private final JpaEffortEstimateRepository estimateRepository;
    private final ProcessingTimeConfig processingTimeConfig;

    public PlaywrightTestController(PlaywrightDataSeeder seeder,
                                    TestClock testClock,
                                    CaseWorkStateService caseWorkStateService,
                                    WorkspaceService workspaceService,
                                    JpaEffortObservationRepository observationRepository,
                                    JpaEffortEstimateRepository estimateRepository,
                                    ProcessingTimeConfig processingTimeConfig) {
        this.seeder = seeder;
        this.testClock = testClock;
        this.caseWorkStateService = caseWorkStateService;
        this.workspaceService = workspaceService;
        this.observationRepository = observationRepository;
        this.estimateRepository = estimateRepository;
        this.processingTimeConfig = processingTimeConfig;
    }

    @GetMapping("/reset")
    public Map<String, Object> reset() {
        seeder.reseed();
        return Map.of("status", "ok", "now", testClock.instant().toString());
    }

    @GetMapping("/clock")
    public Map<String, Object> clock() {
        return Map.of("now", testClock.instant().toString(),
                "startedAt", testClock.startedAt().toString(),
                "elapsedMinutes", Duration.between(testClock.startedAt(), testClock.instant()).toMinutes());
    }

    @GetMapping("/clock/advance")
    public Map<String, Object> advance(@RequestParam("minutes") int minutes) {
        testClock.advance(Duration.ofMinutes(minutes));
        return Map.of("now", testClock.instant().toString(), "advancedMinutes", minutes);
    }

    @GetMapping("/effort/{category}")
    public Map<String, Object> effort(@PathVariable String category) {
        String key = category.trim().toLowerCase(java.util.Locale.GERMANY);
        EffortEstimateEntity estimate = estimateRepository.findAll().stream()
                .filter(e -> key.equalsIgnoreCase(e.getCategory()))
                .findFirst().orElse(null);
        List<EffortObservationEntity> observations = observationRepository.findAll().stream()
                .filter(o -> key.equalsIgnoreCase(o.getCategory())).toList();
        int baseline = processingTimeConfig.baselineMinutes(key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baselineMinutes", baseline);
        result.put("effectiveEstimateMinutes", estimate != null ? estimate.getMedianMinutes() : baseline);
        result.put("observationCount", observations.size());
        result.put("sampleCount", estimate != null ? estimate.getSampleCount() : null);
        result.put("medianMinutes", estimate != null ? estimate.getMedianMinutes() : null);
        result.put("source", estimate != null ? estimate.getSource() : "YAML baseline");
        result.put("complexityFeedback", observations.stream()
                .map(EffortObservationEntity::getComplexityFeedback)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null));
        result.put("complexityFeedbacks", observations.stream()
                .map(EffortObservationEntity::getComplexityFeedback)
                .filter(java.util.Objects::nonNull).toList());
        result.put("observedActiveMinutes", observations.stream()
                .mapToInt(EffortObservationEntity::getObservedActiveMinutes).sum());
        return result;
    }

    @GetMapping("/workstate/{caseId}")
    public Map<String, Object> workstate(@PathVariable String caseId) {
        return caseWorkStateService.currentState(caseId);
    }

    @GetMapping("/case/{caseId}")
    public Map<String, Object> caseInfo(@PathVariable String caseId) {
        WorkspaceEntity ws = workspaceService.findById(caseId).orElse(null);
        if (ws == null) {
            return Map.of("found", false);
        }
        Map<String, Object> data = ws.getPhaseDataMap();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("name", ws.getName());
        result.put("ownerId", ws.getOwnerId());
        result.put("phase", ws.getPhase() != null ? ws.getPhase().name() : null);
        result.put("status", ws.getStatus() != null ? ws.getStatus().name() : null);
        result.put("caseCategory", data.get("caseCategory"));
        result.put("workState", data.get("workState"));
        result.put("waitingOn", data.get("waitingOn"));
        result.put("analysisStatus", data.get("analysis") instanceof Map<?, ?> m ? m.get("status") : null);
        result.put("documentCount", workspaceService.getWorkspaceDocuments(caseId).size());
        return result;
    }

    @GetMapping("/cases")
    public List<Map<String, Object>> cases() {
        return workspaceService.findAll().stream()
                .filter(ws -> ws.getStatus() != reasoning.common.model.WorkspaceStatus.ARCHIVED)
                .map(ws -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", ws.getId());
                    row.put("name", ws.getName());
                    row.put("ownerId", ws.getOwnerId());
                    row.put("phase", ws.getPhase() != null ? ws.getPhase().name() : null);
                    return row;
                }).toList();
    }
}
