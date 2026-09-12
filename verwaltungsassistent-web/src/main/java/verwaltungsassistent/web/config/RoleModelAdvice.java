package verwaltungsassistent.web.config;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.security.CaseAccessGuard;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Stellt allen MVC-Seiten die Rollen-Ansicht bereit: {@code supervisory}
 * (Leitungs-Konto = ADM/Leitung mit Leserechten) und die Anzeige-Rolle der
 * Seitenleiste ({@code roleDisplayLabel}). Templates blenden damit operative
 * Bedienelemente aus — die Ablehnung erfolgt serverseitig zusätzlich über
 * {@link CaseAccessGuard#requireWriteAccess(String, AuthenticatedUser)}.
 */
@ControllerAdvice
public class RoleModelAdvice {

    @ModelAttribute
    public void addRoleInfo(Model model) {
        boolean supervisory = false;
        boolean superadmin = false;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            supervisory = CaseAccessGuard.isSupervisory(user);
            superadmin = user.roles() != null && user.roles().contains("SUPERADMIN");
        }
        model.addAttribute("supervisory", supervisory);
        // Phase 2D.13: Superadmin-Konto sichtbar von der reinen Leitung
        // unterscheidbar (gleiche aufsichtliche Farbwelt, technische Rolle).
        model.addAttribute("superadmin", superadmin);
        model.addAttribute("roleDisplayLabel", superadmin
                ? "Superadmin (Technische Verwaltung)"
                : supervisory ? "Leitung (nur Lesen)" : "Sachbearbeitung");
    }
}
