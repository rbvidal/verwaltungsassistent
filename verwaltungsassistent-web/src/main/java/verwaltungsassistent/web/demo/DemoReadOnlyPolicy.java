package verwaltungsassistent.web.demo;

import reasoning.auth.api.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo-Betrieb ({@code demo}-Profil): Das Leitungs-Konto (admin@verwaltungsassistent.local,
 * Rolle ADMIN) ist vollständig schreibgeschützt — es darf auch die Bereiche
 * nicht verändern, die außerhalb der Demo zur Administration gehören
 * (Dokumente verwalten, Geovorgänge anlegen/ändern, Benutzerkonten anlegen).
 * Die technische Rolle SUPERADMIN bleibt ausgenommen (Demo-Reset u. Ä.).
 *
 * <p>Nur im Demo-Profil aktiv (Bean fehlt in anderen Profilen, Aufrufer
 * nutzen {@link org.springframework.beans.factory.ObjectProvider}). Die
 * Sachbearbeitungs-Sperren (Fälle/E-Mails/Assistent) gelten unabhängig
 * davon bereits global über CaseAccessGuard/requireOperationalUser.</p>
 */
@Component
@Profile("demo")
public class DemoReadOnlyPolicy {

    public static final String LEITUNG_DENIED_MESSAGE =
            "Das Leitungs-Konto ist im Demo-Betrieb schreibgeschützt — dieser Bereich ist nur lesend verfügbar.";

    /**
     * @throws ResponseStatusException 403 wenn der Nutzer das Leitungs-Konto
     *                                 (ADMIN, ohne SUPERADMIN) ist
     */
    public void denyAdminMutation(AuthenticatedUser user) {
        if (user != null && user.roles() != null
                && user.roles().contains("ADMIN") && !user.roles().contains("SUPERADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, LEITUNG_DENIED_MESSAGE);
        }
    }
}
