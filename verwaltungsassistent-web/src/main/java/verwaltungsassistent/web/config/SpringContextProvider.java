package verwaltungsassistent.web.config;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Minimaler, statischer Zugriff auf den ApplicationContext — ausschließlich
 * für die statische Sichtbarkeits-Helfer-Klasse {@code WorkspaceVisibility},
 * damit diese ohne Änderung aller Aufrufer-Konstruktoren den
 * CaseViewClaimService abfragen kann. In reinen Unit-Kontexten (kein
 * Spring-Context) liefert die Abfrage null und das Verhalten bleibt
 * unverändert.
 */
@Component
public class SpringContextProvider implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static ApplicationContext context() {
        return context;
    }
}
