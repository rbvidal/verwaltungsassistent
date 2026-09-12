package reasoning.ai.application;

import reasoning.ai.api.AuthorityGroundingService;
import reasoning.ai.api.AuthorityRetrievalService;
import reasoning.ai.api.ConceptExtractionService;
import reasoning.ai.api.ReferenceValidationService;
import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates authority grounding by extracting concepts, retrieving references, and validating results.
 */
@Service
public class DefaultAuthorityGroundingService implements AuthorityGroundingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthorityGroundingService.class);

    private final ConceptExtractionService conceptExtractionService;
    private final AuthorityRetrievalService retrievalService;
    private final ReferenceValidationService validationService;

    public DefaultAuthorityGroundingService(
            ConceptExtractionService conceptExtractionService,
            AuthorityRetrievalService retrievalService,
            ReferenceValidationService validationService) {
        this.conceptExtractionService = conceptExtractionService;
        this.retrievalService = retrievalService;
        this.validationService = validationService;
    }

    @Override
    public AuthorityGroundingResult ground(String query) {
        List<ExtractedConcept> concepts = conceptExtractionService.classify(query);
        log.debug("Extracted {} concepts for query", concepts.size());

        List<AuthorityReference> candidates = retrievalService.retrieveAuthorities(query, concepts);
        log.debug("Retrieved {} reference candidates", candidates.size());

        List<AuthorityReference> validated = validationService.validate(candidates, concepts, query);
        log.debug("Validated to {} references after filtering", validated.size());

        return new AuthorityGroundingResult(concepts, validated);
    }
}
