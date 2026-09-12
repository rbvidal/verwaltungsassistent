package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decision-review surface. Decisions are produced by the existing decision
 * workspace (question -> rule engine/retrieval -> generation -> independent
 * verification with fail-closed handling); this page reviews them per case.
 * No second decision engine and no approval mechanism is invented — the
 * verification status of each analysis is shown inside the decision
 * workspace itself.
 */
@Controller
public class DecisionsController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WorkspaceService workspaceService;

    public DecisionsController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/decisions")
    public String decisions(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        List<WorkspaceEntity> workspaces;
        if (user.roles().contains("ADMIN")) {
            workspaces = new ArrayList<>(workspaceService.findAll());
        } else {
            // Dieselbe Sichtbarkeit wie die Fälle-Liste (eigene Vorgänge +
            // allgemeiner Arbeitspool): die Entscheidungsübersicht darf nicht
            // weniger zeigen als die Vorgangsliste, auf der sie aufbaut.
            workspaces = new ArrayList<>(
                    verwaltungsassistent.web.security.WorkspaceVisibility
                            .ownAndPool(workspaceService, user.email()));
        }
        workspaces.sort(Comparator.comparing(WorkspaceEntity::getUpdatedAt).reversed());

        List<DecisionRow> rows = workspaces.stream()
                .map(workspaceService::toDto)
                .map(d -> new DecisionRow(
                        d.id(),
                        d.name() != null ? d.name() : d.workspaceCode(),
                        d.workspaceCode(),
                        d.description() != null && !d.description().isBlank()
                                ? d.description() : "Keine Beschreibung",
                        CaseDetailController.phaseLabel(d.phase()),
                        d.documents() != null ? d.documents().size() : 0,
                        d.updatedAt() != null
                                ? DATE_FMT.format(d.updatedAt().atZone(ZoneId.systemDefault()))
                                : "—"))
                .toList();

        model.addAttribute("rows", rows);
        model.addAttribute("pageTitle", "Entscheidungen");
        model.addAttribute("activeSection", "decisions");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Entscheidungen", "/decisions")));
        return "decisions/list";
    }

    public record DecisionRow(String id, String name, String code, String description,
                              String phaseLabel, int documentCount, String updatedAt) {}
}
