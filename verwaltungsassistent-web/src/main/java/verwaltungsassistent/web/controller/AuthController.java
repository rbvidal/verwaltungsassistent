package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import reasoning.auth.model.Role;
import verwaltungsassistent.web.form.LoginForm;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class AuthController {

    private final UserAccountRepository userAccountRepository;
    private final SessionRegistry sessionRegistry;

    public AuthController(UserAccountRepository userAccountRepository, SessionRegistry sessionRegistry) {
        this.userAccountRepository = userAccountRepository;
        this.sessionRegistry = sessionRegistry;
    }

    @GetMapping("/login")
    public String loginForm(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "expired", required = false) String expired,
            @RequestParam(value = "registered", required = false) String registered,
            Model model
    ) {
        model.addAttribute("loginForm", new LoginForm("", ""));
        addDemoPanelState(model);

        if (error != null) {
            if ("blocked".equals(error)) {
                model.addAttribute("loginError",
                        reasoning.auth.application.AccountLockedException.MESSAGE);
            } else if ("occupied".equals(error)) {
                model.addAttribute("loginError",
                        "Dieser Demo-Zugang wird bereits in einem anderen Browser verwendet. "
                                + "Bitte melden Sie sich dort ab oder wählen Sie einen anderen Zugang.");
            } else {
                model.addAttribute("loginError", "Ungültige E-Mail oder Passwort");
            }
        }
        if (logout != null) {
            model.addAttribute("logoutMessage", "Sie wurden erfolgreich abgemeldet");
        }
        if (expired != null) {
            if ("inactivity".equals(expired)) {
                model.addAttribute("expiredMessage",
                        "Ihre Sitzung ist wegen Inaktivität abgelaufen. Bitte melden Sie sich erneut an.");
            } else {
                model.addAttribute("expiredMessage",
                        "Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an");
            }
        }
        if (registered != null) {
            model.addAttribute("registeredMessage",
                    "Registrierung erfolgreich. Bitte melden Sie sich an");
        }

        return "auth/login";
    }

    /**
     * Live-Status der Demo-Konto-Auswahl für die Login-Seite. Die Karte
     * ersetzt sich per HTMX-Polling selbst (auth/login :: demoUsersCard),
     * damit Belegung UND Freigabe der fünf Demo-Konten ohne Neuladen
     * sichtbar werden — auch nach Inaktivitäts-Logout, dessen Sitzung der
     * SessionRegistry über das sessionDestroyed-Ereignis entzogen wird.
     */
    @GetMapping("/login/panel")
    public String demoPanelState(Model model) {
        addDemoPanelState(model);
        return "auth/login :: demoUsersCard";
    }

    private void addDemoPanelState(Model model) {
        List<DemoUserRow> rows = demoUsers();
        model.addAttribute("demoUsers", rows);
        model.addAttribute("demoPassword", verwaltungsassistent.web.service.DemoDataService.DEMO_PASSWORD);
        long occupied = rows.stream().filter(DemoUserRow::inUse).count();
        model.addAttribute("demoOccupiedCount", occupied);
        model.addAttribute("demoAvailableCount", rows.size() - occupied);
        model.addAttribute("allDemoOccupied", !rows.isEmpty() && occupied == rows.size());
    }

    /**
     * Demo users for the login page with an availability status: "frei" unless
     * an active HTTP session exists for the account right now (the clearly
     * defined availability rule — no session-reservation system). The match is
     * by account e-mail, not by principal name (AuthenticatedUser is a record
     * without a name() accessor).
     */
    private List<DemoUserRow> demoUsers() {
        try {
            Set<String> inUse = sessionRegistry.getAllPrincipals().stream()
                    // The registry stores the principal object itself (the
                    // AuthenticatedUser); older entries may carry the
                    // Authentication — unwrap both forms.
                    .map(p -> p instanceof Authentication auth ? auth.getPrincipal() : p)
                    .filter(pr -> pr instanceof AuthenticatedUser)
                    .map(pr -> ((AuthenticatedUser) pr).email())
                    .collect(Collectors.toSet());
            List<DemoUserRow> rows = new ArrayList<>();
            Set<String> publicDemoAccounts = Set.of(
                    "demo01@verwaltungsassistent.local", "demo02@verwaltungsassistent.local", "demo03@verwaltungsassistent.local",
                    "demo04@verwaltungsassistent.local", "demo05@verwaltungsassistent.local");
            for (UserAccountEntity u : userAccountRepository.findAll()) {
                if (u.getEmail() != null
                        && publicDemoAccounts.contains(u.getEmail())
                        && u.getRoles().contains(Role.USER)) {
                    rows.add(new DemoUserRow(u.getEmail(), u.getDisplayName(), inUse.contains(u.getEmail())));
                }
            }
            rows.sort(Comparator.comparing(DemoUserRow::email));
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    public record DemoUserRow(String email, String name, boolean inUse) {}
}
