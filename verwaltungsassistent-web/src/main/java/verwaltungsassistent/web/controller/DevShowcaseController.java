package verwaltungsassistent.web.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

/**
 * Development-only showcase page for the shared UI component library.
 * Not part of application navigation. Exists solely to verify fragments during development.
 */
@Controller
@Profile({"dev", "demo"})
public class DevShowcaseController {

    @GetMapping("/dev/showcase")
    public String showcase(Model model) {
        model.addAttribute("pageTitle", "UI Component Showcase");
        model.addAttribute("activeSection", "none");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Showcase", "/dev/showcase")));

        // --- dataTable sample data ---
        model.addAttribute("tableHeaders", List.of(
                new Header("name", "Name", true),
                new Header("status", "Status", false),
                new Header("phase", "Phase", true),
                new Header("documents", "Dokumente", false),
                new Header("updated", "Aktualisiert", true)
        ));

        model.addAttribute("tableRows", List.of(
                Map.of("name", "Fall Müller", "status", "Aktiv", "phase", "Analyse",
                        "documents", "5", "updated", "01.08.2026"),
                Map.of("name", "Fall Schmidt", "status", "Aktiv", "phase", "Ingestion",
                        "documents", "2", "updated", "31.07.2026"),
                Map.of("name", "Fall Weber", "status", "Geschlossen", "phase", "Abgeschlossen",
                        "documents", "12", "updated", "15.07.2026"),
                Map.of("name", "Fall Fischer", "status", "Entwurf", "phase", "Einrichtung",
                        "documents", "0", "updated", "29.07.2026"),
                Map.of("name", "Fall Becker", "status", "Aktiv", "phase", "Überprüfung",
                        "documents", "8", "updated", "28.07.2026")
        ));

        // --- pagination sample data ---
        model.addAttribute("paginationPage", 0);
        model.addAttribute("paginationTotalPages", 5);

        // --- filterBar sample data ---
        model.addAttribute("sampleFilters", List.of(
                new FilterDef("status", "Status", "select", List.of(
                        new FilterOption("ACTIVE", "Aktiv"),
                        new FilterOption("DRAFT", "Entwurf"),
                        new FilterOption("CLOSED", "Geschlossen"),
                        new FilterOption("ARCHIVED", "Archiviert")
                ), ""),
                new FilterDef("type", "Typ", "select", List.of(
                        new FilterOption("GENERAL", "Allgemein"),
                        new FilterOption("BUILDING", "Bauen"),
                        new FilterOption("SOCIAL", "Soziales")
                ), ""),
                new FilterDef("search", "Suche", "text", List.of(), "")
        ));

        // --- tabBar sample data ---
        model.addAttribute("sampleTabs", List.of(
                new TabDef("overview", "Übersicht", null),
                new TabDef("documents", "Dokumente", null),
                new TabDef("timeline", "Timeline", null),
                new TabDef("checklist", "Checkliste", null),
                new TabDef("notes", "Notizen", null)
        ));

        // --- phaseProgress sample data ---
        model.addAttribute("phaseStages", List.of(
                new StageDef("Einrichtung"),
                new StageDef("Ingestion"),
                new StageDef("Analyse"),
                new StageDef("Überprüfung"),
                new StageDef("Abschluss")
        ));
        model.addAttribute("phaseCurrentIndex", 2); // 0-based → "Analyse"

        return "dev/showcase";
    }

    // Data holder records

    public record Header(String key, String label, boolean sortable) {}

    public record FilterDef(String name, String label, String type, List<FilterOption> options, String value) {
        public String getPlaceholder() { return null; }
    }

    public record FilterOption(String value, String label) {}

    public record TabDef(String id, String label, String url) {
        public String getContent() { return null; }
    }

    public record StageDef(String label) {}
}
