package reasoning.ai.application;

import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceDossier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dossier no longer classifies German documents via English landlord/tenant
 * keywords; coverage reflects only whether sources are available. German
 * documents must never systematically report coverageScore 0.
 */
class DefaultSourceOrchestrationServiceTest {

    private static final String GEWERBE_QUERY =
            "Ich möchte zum 1. September ein kleines Gewerbe anmelden. "
            + "Welche Unterlagen benötige ich für die Gewerbeanmeldung?";

    private final DefaultSourceOrchestrationService service = new DefaultSourceOrchestrationService();

    private SourceCitation source(String title, String excerpt) {
        return new SourceCitation(UUID.randomUUID(), UUID.randomUUID(), 1, title, 1, 0, 10,
                excerpt != null ? excerpt : "", 0.9, SourceCitation.SourceTier.PRIMARY,
                SourceCitation.SourceType.FACTUAL, List.of("KEYWORD"));
    }

    @Test
    void germanSources_neverReportZeroCoverage() {
        SourceDossier dossier = service.buildDossier(
                List.of(source("Gewerbeanmeldung", "Voraussetzungen für die Gewerbeanmeldung")),
                GEWERBE_QUERY);

        assertEquals(1.0, dossier.coverageScore());
        assertTrue(dossier.completenessAssessment().contains("Quellen vorhanden"));
        assertTrue(dossier.missingRoles().isEmpty(), "no role-based missing-document claims");
        assertTrue(dossier.presentRoles().isEmpty());
    }

    @Test
    void noSources_reportZeroCoverage() {
        SourceDossier dossier = service.buildDossier(List.of(), GEWERBE_QUERY);

        assertEquals(0.0, dossier.coverageScore());
        assertTrue(dossier.completenessAssessment().contains("Keine Quellen verfügbar"));
    }

    @Test
    void coverage_doesNotDependOnDocumentLanguageOrWording() {
        SourceDossier dossier = service.buildDossier(
                List.of(source("Bürgeramt Gewerbe", "Gewerbe anmelden, Voraussetzungen, Gebühren")),
                GEWERBE_QUERY);

        assertEquals(1.0, dossier.coverageScore(),
                "German documents must not be penalized for not matching English role keywords");
    }

    @Test
    void nullSources_areTreatedAsEmpty() {
        assertEquals(0.0, service.buildDossier(null, GEWERBE_QUERY).coverageScore());
    }
}
