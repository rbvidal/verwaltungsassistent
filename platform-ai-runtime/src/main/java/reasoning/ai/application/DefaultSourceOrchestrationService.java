package reasoning.ai.application;

import reasoning.ai.api.SourceOrchestrationService;
import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceDossier;
import reasoning.ai.model.SourceRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a source dossier describing the available evidence.
 *
 * <p>The former role classification (English landlord/tenant keywords such as
 * "contract", "termination", "unpaid", "attorney") classified German municipal
 * documents as UNCLASSIFIED, systematically reporting coverageScore 0 — even
 * when relevant German evidence was present. Role-based coverage is not
 * required by the current evidence/prompt pipeline (relevance and support are
 * judged by retrieval and verification), so it is removed. Coverage now
 * reflects only whether sources are available: 1.0 with sources, 0.0 without.
 * Relevance quality is not claimed by this score.</p>
 */
@Service
public class DefaultSourceOrchestrationService implements SourceOrchestrationService {

    @Override
    public SourceDossier buildDossier(List<SourceCitation> sources, String query) {
        Map<SourceRole, List<String>> byRole = new EnumMap<>(SourceRole.class);
        for (SourceRole role : SourceRole.values()) {
            byRole.put(role, new ArrayList<>());
        }

        boolean hasSources = sources != null && !sources.isEmpty();
        double coverageScore = hasSources ? 1.0 : 0.0;
        String assessment = hasSources
                ? "Quellen vorhanden – die Analyse kann auf Evidenz zurückgreifen."
                : "Keine Quellen verfügbar – die Analyse kann keine Evidenz heranziehen.";

        return new SourceDossier(byRole, List.of(), List.of(), coverageScore, assessment);
    }
}
