package verwaltungsassistent.web.config;

import reasoning.ai.model.Domain;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers municipal domain identifiers at startup.
 * The Verwaltungsassistent core has no knowledge of these domain names —
 * they are supplied by this municipal application.
 */
@Component
public class MunicipalDomains {

    public static final Domain PROCUREMENT = Domain.of("PROCUREMENT");
    public static final Domain BUILDING = Domain.of("BUILDING");
    public static final Domain HR = Domain.of("HR");
    public static final Domain TRAVEL = Domain.of("TRAVEL");
    public static final Domain GEWERBE = Domain.of("GEWERBE");

    @PostConstruct
    void init() {
        // Registration happens via Domain.of() static initializers above.
        // This method exists to ensure the bean is created and constants are
        // registered before any DomainClassifier or DomainGate usage.
    }
}
