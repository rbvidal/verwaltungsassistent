package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.planning.NextBestWorkService.Recommendation;
import verwaltungsassistent.web.planning.NextBestWorkService.RecommendationResult;
import verwaltungsassistent.web.service.DashboardService;
import verwaltungsassistent.web.service.SystemHealthService;
import verwaltungsassistent.web.viewmodel.DashboardViewModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class HomeController {

    private final DashboardService dashboardService;
    private final verwaltungsassistent.web.planning.NextBestWorkService nextBestWorkService;
    private final SystemHealthService systemHealthService;

    public HomeController(DashboardService dashboardService,
                          verwaltungsassistent.web.planning.NextBestWorkService nextBestWorkService,
                          SystemHealthService systemHealthService) {
        this.dashboardService = dashboardService;
        this.nextBestWorkService = nextBestWorkService;
        this.systemHealthService = systemHealthService;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model,
                            @RequestParam(name = "empfehlung", defaultValue = "0") int recOffset) {
        AuthenticatedUser user = getCurrentUser();
        boolean supervisory = verwaltungsassistent.web.security.CaseAccessGuard
                .isSupervisory(user);
        model.addAttribute("supervisory", supervisory);
        DashboardViewModel dashboard = dashboardService.build(user);
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("pageTitle", supervisory ? "Leitungs-Übersicht" : "Dashboard");
        model.addAttribute("activeSection", "dashboard");
        model.addAttribute("currentUserEmail", user.email());
        // Systemstatus (Phase 2D.12): echte Erreichbarkeits-Prüfung der
        // Laufzeit-Abhängigkeiten (Datenbank/Vektorspeicher/KI-Modell) — die
        // Mitarbeiter-Oberfläche zeigt ein ehrliches OK/Fehler statt eines
        // statischen "Audit-Status".
        model.addAttribute("systemStatus", systemHealthService.status());
        // Leitungs-Konto: aufsichtliche Gesamtübersicht statt persönlicher
        // Systemempfehlung — die Leitung bekommt keine Mitarbeiter-Empfehlung
        // (recommendFor liefert für sie ohnehin ein leeres Ergebnis).
        if (supervisory) {
            model.addAttribute("supervision", dashboardService.supervisionView(user));
        } else {
            RecommendationResult result = nextBestWorkService.recommendFor(user);
            // "Nächsten Vorgang" (Phase 2D.12): reine NAVIGATION durch die
            // BESTEHENDE Empfehlungs-Reihung (recommendFor ist deterministisch
            // und verändert keinen Zustand) — kein zweites Ranking, keine
            // Mutation von Zuordnung/Status. recOffset = Position in der
            // unveränderten rankedCandidates-Liste.
            int size = result.rankedCandidates() == null ? 0 : result.rankedCandidates().size();
            int offset = Math.max(0, recOffset);
            if (size == 0) {
                offset = 0;
                // Kein Kandidat: unverändertes Ergebnis an die Vorlage —
                // dort erscheint der erklärte Leerzustand (recommended() == null).
                model.addAttribute("recommendation", result);
            } else {
                offset = Math.min(offset, size - 1);
                if (recOffset > 0 && recOffset >= size) {
                    model.addAttribute("recExhausted", Boolean.TRUE);
                }
                if (recOffset > 0) {
                    Recommendation shown = result.rankedCandidates().get(offset);
                    // Angezeigter Kandidat = Position recOffset; die übrige
                    // Liste (rankedCandidates) bleibt unverändert, damit die
                    // Erklärungskontexte konsistent bleiben.
                    RecommendationResult atOffset = new RecommendationResult(
                            result.employeeEmail(), shown, result.rankedCandidates(),
                            result.excludedCandidates(), result.trace());
                    model.addAttribute("recommendation", atOffset);
                } else {
                    model.addAttribute("recommendation", result);
                }
            }
            model.addAttribute("recOffset", offset);
            model.addAttribute("recSize", size);
        }
        model.addAttribute("breadcrumbs",
                List.of(new Breadcrumb("Home", "/dashboard")));
        return "dashboard/index";
    }

    private AuthenticatedUser getCurrentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("No authenticated user");
    }

    public record Breadcrumb(String label, String url) {}
}
