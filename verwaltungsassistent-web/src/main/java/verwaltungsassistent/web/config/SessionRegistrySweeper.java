package verwaltungsassistent.web.config;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Räumt verwaiste SessionRegistry-Einträge ab („in Benutzung" auf der
 * Login-Seite), deren zugrunde liegende HTTP-Sitzung serverseitig bereits
 * abgelaufen ist — z. B. nach Browser-Crash, vergessenem Logout oder
 * Inaktivitäts-Logout. Ohne diese Räumung bliebe das Demo-Konto gesperrt,
 * bis die Anwendung neu startet (die container-seitige sessionDestroyed-
 * Benachrichtigung ist in dieser Umgebung nicht zuverlässig).
 *
 * <p>Schwelle = serverseitige Sitzungs-Timeout-Dauer
 * ({@code server.servlet.session.timeout}, Standard 30m): Ein Registry-Eintrag
 * ohne Aktivität länger als diese Dauer kann keine lebende Sitzung mehr sein
 * und wird entfernt. Der Login-Versuch danach funktioniert wieder normal.</p>
 */
@Component
public class SessionRegistrySweeper {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistrySweeper.class);

    private final SessionRegistry sessionRegistry;
    private final Duration maxIdle;

    public SessionRegistrySweeper(SessionRegistry sessionRegistry,
                                  @Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout) {
        this.sessionRegistry = sessionRegistry;
        // Etwas Großzügigkeit über das Timeout hinaus, damit kein aktiver
        // Randfall entfernt wird; die serverseitige Inaktivitäts-Logout-Logik
        // (app.security.inactivity-timeout-minutes, Standard 15m) läuft davor.
        this.maxIdle = sessionTimeout.isZero() || sessionTimeout.isNegative()
                ? Duration.ofMinutes(30)
                : sessionTimeout.plus(Duration.ofMinutes(2));
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        try {
            Instant cutoff = Instant.now().minus(maxIdle);
            int removed = 0;
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                    Instant last = info.getLastRequest() != null
                            ? info.getLastRequest().toInstant() : Instant.EPOCH;
                    if (last.isBefore(cutoff)) {
                        sessionRegistry.removeSessionInformation(info.getSessionId());
                        removed++;
                        log.info("SessionRegistry: verwaisten Eintrag entfernt ({}), Konto wieder frei",
                                principal);
                    }
                }
            }
            if (removed > 0) {
                log.info("SessionRegistry-Räumung: {} Eintrag/Einträge entfernt", removed);
            }
        } catch (Exception e) {
            log.warn("SessionRegistry-Räumung fehlgeschlagen: {}", e.getMessage());
        }
    }
}
