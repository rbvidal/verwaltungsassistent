package reasoning.ai.api;

import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceDossier;

import java.util.List;

/**
 * Orchestrates the classification of source citations into a source dossier.
 */
public interface SourceOrchestrationService {
    /**
     * Builds a {@link SourceDossier} classifying sources by role and computing coverage.
     */
    SourceDossier buildDossier(List<SourceCitation> sources, String query);
}
