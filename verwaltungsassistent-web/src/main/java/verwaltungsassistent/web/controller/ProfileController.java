package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Profile page for the authenticated user — real account data only
 * (name, email, roles). No write operations.
 */
@Controller
public class ProfileController {

    @GetMapping("/profile")
    public String profile(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        model.addAttribute("displayName", user.displayName());
        model.addAttribute("email", user.email());
        model.addAttribute("roles", user.roles().stream().sorted().toList());
        model.addAttribute("pageTitle", "Profil");
        model.addAttribute("activeSection", "profile");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Profil", "/profile")));
        return "profile/index";
    }
}
