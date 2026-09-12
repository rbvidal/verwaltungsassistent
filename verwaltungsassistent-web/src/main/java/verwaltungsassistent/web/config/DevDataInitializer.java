package verwaltungsassistent.web.config;

import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.RegisterUserCommand;
import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile({"dev", "demo"})
public class DevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final AuthFacade authFacade;
    private final UserAccountRepository userAccountRepository;

    public DevDataInitializer(AuthFacade authFacade, UserAccountRepository userAccountRepository) {
        this.authFacade = authFacade;
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public void run(String... args) {
        try {
            authFacade.register(new RegisterUserCommand(
                    "admin@verwaltungsassistent.local",
                    "admin123",
                    "Administrator",
                    Set.of("ADMIN")
            ));
            log.info("Dev user created: admin@verwaltungsassistent.local / admin123 (ADMIN)");
        } catch (Exception e) {
            log.debug("Dev user already exists: {}", e.getMessage());
        }

        try {
            authFacade.register(new RegisterUserCommand(
                    "user@verwaltungsassistent.local",
                    "user1234",
                    "Sachbearbeiter",
                    Set.of("USER")
            ));
            log.info("Dev user created: user@verwaltungsassistent.local / user1234");
        } catch (Exception e) {
            log.debug("Dev user already exists or could not be created: {}", e.getMessage());
        }

        // Phase 2D.12 — Verstecktes SUPERADMIN-Konto (nur Entwickler-/Demo-
        // Konfiguration, bewusst NICHT auf der Anmeldeseite gelistet):
        // technische Wartung (Datenwiederherstellung, "Alle Daten löschen",
        // Demo-Reset, Datensatz-Neuaufbau) ist ausschließlich diesem Konto
        // vorbehalten. Das normale admin@verwaltungsassistent.local (ADM/Leitung) bleibt rein
        // aufsichtlich. Zugang: superadmin@verwaltungsassistent.local / NcDn++2026$$.
        try {
            authFacade.register(new RegisterUserCommand(
                    "superadmin@verwaltungsassistent.local",
                    "NcDn++2026$$",
                    "Superadmin (Technische Verwaltung)",
                    Set.of("ADMIN", "SUPERADMIN")
            ));
            log.info("Hidden superadmin created: superadmin@verwaltungsassistent.local (ADMIN + SUPERADMIN, Wartung)");
        } catch (Exception e) {
            log.debug("Superadmin already exists: {}", e.getMessage());
        }

        // Profil ergänzen (Telefon etc.): die Signatur- und PDF-Blöcke
        // (BEARBEITET VON) lesen ausschließlich die Benutzerdaten — ein
        // fehlendes Telefon würde im exportierten Dokument als Leerstelle
        // erscheinen.
        enrichProfile("admin@verwaltungsassistent.local",
                "Leiter Bürgerdienste", "Abt. Bürgerdienste", "Bürgeramt Potsdam",
                "Raum 1.01", "0331 289-0");
        enrichProfile("user@verwaltungsassistent.local",
                "Sachbearbeitung Bürgerangelegenheiten", "Abt. Bürgerdienste", "Bürgeramt Potsdam",
                "Raum 1.02", "0331 289-1001");
    }

    private void enrichProfile(String email, String position, String department,
                               String office, String room, String phone) {
        try {
            userAccountRepository.findByEmail(email).ifPresent(u -> {
                boolean changed = false;
                if (u.getPosition() == null || u.getPosition().isBlank()) {
                    u.setPosition(position);
                    changed = true;
                }
                if (u.getDepartment() == null || u.getDepartment().isBlank()) {
                    u.setDepartment(department);
                    changed = true;
                }
                if (u.getOffice() == null || u.getOffice().isBlank()) {
                    u.setOffice(office);
                    changed = true;
                }
                if (u.getRoom() == null || u.getRoom().isBlank()) {
                    u.setRoom(room);
                    changed = true;
                }
                if (u.getPhone() == null || u.getPhone().isBlank()) {
                    u.setPhone(phone);
                    changed = true;
                }
                if (changed) {
                    userAccountRepository.save(u);
                    log.info("Profil ergänzt: {}", email);
                }
            });
        } catch (Exception e) {
            log.debug("Profil-Ergänzung fehlgeschlagen für {}: {}", email, e.getMessage());
        }
    }
}
