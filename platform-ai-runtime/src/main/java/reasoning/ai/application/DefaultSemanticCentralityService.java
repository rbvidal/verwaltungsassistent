package reasoning.ai.application;

import reasoning.ai.api.SemanticCentralityService;
import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;
import reasoning.ai.model.SemanticCentralityScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Scores and filters authority references by semantic centrality using static weights and concept awareness.
 */
@Service
public class DefaultSemanticCentralityService implements SemanticCentralityService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSemanticCentralityService.class);

    private static final Map<String, Double> CENTRALITY_WEIGHTS = Map.ofEntries(
            Map.entry("535", 1.0),
            Map.entry("551", 0.95),
            Map.entry("543", 0.9),
            Map.entry("387", 0.9),
            Map.entry("555d", 0.89),
            Map.entry("555b", 0.88),
            Map.entry("559", 0.87),
            Map.entry("280", 0.85),
            Map.entry("569", 0.85),
            Map.entry("573c", 0.85),
            Map.entry("556b", 0.8),
            Map.entry("389", 0.8),
            Map.entry("540", 0.8),
            Map.entry("573", 0.8),
            Map.entry("555c", 0.78),
            Map.entry("546a", 0.75),
            Map.entry("553", 0.75),
            Map.entry("568", 0.75),
            Map.entry("555e", 0.75),
            Map.entry("536a", 0.75),
            Map.entry("537", 0.7),
            Map.entry("536", 0.7),
            Map.entry("542", 0.7),
            Map.entry("281", 0.7),
            Map.entry("536b", 0.68),
            Map.entry("536c", 0.68),
            Map.entry("556", 0.65),
            Map.entry("556a", 0.65),
            Map.entry("546", 0.65),
            Map.entry("555a", 0.65),
            Map.entry("554", 0.65),
            Map.entry("557", 0.55),
            Map.entry("558", 0.55),
            Map.entry("558a", 0.5),
            Map.entry("560", 0.5),
            Map.entry("574", 0.4)
    );

    private static final Set<String> PERIPHERAL_REFS = Set.of(
            "531", "532", "533", "534", "548", "549", "550",
            "575", "576", "577", "578", "580", "580a"
    );

    @Override
    public List<SemanticCentralityScore> scoreAuthorities(List<AuthorityReference> references, String query) {
        List<SemanticCentralityScore> scores = new ArrayList<>();
        for (AuthorityReference a : references) {
            String entry = a.entryNumber();
            double centrality = CENTRALITY_WEIGHTS.getOrDefault(entry, 0.5);

            SemanticCentralityScore.SemanticTier tier;
            String rationale;
            if (PERIPHERAL_REFS.contains(entry)) {
                centrality = Math.min(centrality, 0.3);
                tier = SemanticCentralityScore.SemanticTier.PERIPHERAL;
                rationale = "Peripheral to core domain concepts";
            } else if (centrality >= 0.85) {
                tier = SemanticCentralityScore.SemanticTier.CORE;
                rationale = "Core governing reference for domain obligations and remedies";
            } else if (centrality >= 0.7) {
                tier = SemanticCentralityScore.SemanticTier.SUPPORTING;
                rationale = "Supporting reference";
            } else if (centrality >= 0.4) {
                tier = SemanticCentralityScore.SemanticTier.PERIPHERAL;
                rationale = "Tangentially related — not central to primary findings";
            } else {
                tier = SemanticCentralityScore.SemanticTier.IRRELEVANT;
                rationale = "Not relevant to identified concepts";
            }

            scores.add(new SemanticCentralityScore(entry, centrality, tier, rationale));
        }
        scores.sort(Comparator.comparingDouble(SemanticCentralityScore::centralityScore).reversed());
        return scores;
    }

    @Override
    public List<AuthorityReference> filterCentral(List<AuthorityReference> references, String query) {
        return filterCentralStatic(references);
    }

    @Override
    public List<AuthorityReference> filterCentral(List<AuthorityReference> references, String query, List<ExtractedConcept> concepts) {
        if (concepts == null || concepts.isEmpty()) {
            return filterCentralStatic(references);
        }

        ExtractedConcept topConcept = concepts.getFirst();
        Set<String> guaranteedReferences = new LinkedHashSet<>(topConcept.governingReferences());
        log.info("Semantic centrality: top concept={}, guaranteed references={}",
                topConcept.concept(), guaranteedReferences);

        double threshold = topConcept.confidence() * 0.5;
        for (int i = 1; i < concepts.size(); i++) {
            if (concepts.get(i).confidence() >= threshold) {
                guaranteedReferences.addAll(concepts.get(i).governingReferences());
            }
        }

        List<SemanticCentralityScore> scores = scoreAuthorities(references, query);
        Set<String> keepRefs = new HashSet<>(guaranteedReferences);
        for (SemanticCentralityScore s : scores) {
            if (s.tier() == SemanticCentralityScore.SemanticTier.CORE
                    || s.tier() == SemanticCentralityScore.SemanticTier.SUPPORTING) {
                keepRefs.add(s.paragraph());
            }
        }

        List<AuthorityReference> filtered = new ArrayList<>();
        for (AuthorityReference a : references) {
            if (keepRefs.contains(a.entryNumber())) {
                double centrality;
                if (guaranteedReferences.contains(a.entryNumber())) {
                    centrality = Math.max(CENTRALITY_WEIGHTS.getOrDefault(a.entryNumber(), 0.5),
                            topConcept.confidence());
                } else {
                    centrality = CENTRALITY_WEIGHTS.getOrDefault(a.entryNumber(), 0.5);
                }
                filtered.add(new AuthorityReference(
                        a.documentId(), a.chunkId(), a.referenceId(),
                        a.domainCode(), a.entryNumber(), a.entryTitle(),
                        a.excerpt(), a.domain(), a.basis(),
                        centrality, a.retrievalScore(), a.tier()));
            }
        }
        filtered.sort(Comparator.comparingDouble(AuthorityReference::relevanceScore).reversed());
        log.info("Semantic centrality: {} references after filtering (from {}), top={}",
                filtered.size(), references.size(),
                filtered.isEmpty() ? "none" : filtered.getFirst().referenceId());
        return filtered;
    }

    private List<AuthorityReference> filterCentralStatic(List<AuthorityReference> references) {
        List<SemanticCentralityScore> scores = scoreAuthorities(references, "");
        Set<String> coreRefs = new HashSet<>();
        for (SemanticCentralityScore s : scores) {
            if (s.tier() == SemanticCentralityScore.SemanticTier.CORE
                    || s.tier() == SemanticCentralityScore.SemanticTier.SUPPORTING) {
                coreRefs.add(s.paragraph());
            }
        }

        List<AuthorityReference> filtered = new ArrayList<>();
        for (AuthorityReference a : references) {
            if (coreRefs.contains(a.entryNumber())) {
                double centrality = CENTRALITY_WEIGHTS.getOrDefault(a.entryNumber(), 0.5);
                filtered.add(new AuthorityReference(
                        a.documentId(), a.chunkId(), a.referenceId(),
                        a.domainCode(), a.entryNumber(), a.entryTitle(),
                        a.excerpt(), a.domain(), a.basis(),
                        centrality, a.retrievalScore(), a.tier()));
            }
        }
        filtered.sort(Comparator.comparingDouble(AuthorityReference::relevanceScore).reversed());
        return filtered;
    }
}
