package reasoning.ai.application;

import reasoning.ai.api.AuthorityRetrievalService;
import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Retrieves authority references for extracted concepts.
 *
 * <p>Authority references are only emitted when an actual knowledge-base
 * authority backs them. The former behavior fabricated placeholder entries
 * ("Entry N" / "Reference text for entry N") from concept governing
 * references — including a hardcoded §535 BGB fallback — which presented
 * synthetic text as a genuine legal foundation. Until a real knowledge-base
 * source exists behind a reference, this service returns no authorities;
 * genuine authorities (e.g. from the rule-engine path) are unaffected.</p>
 */
@Service
public class DefaultAuthorityRetrievalService implements AuthorityRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthorityRetrievalService.class);

    @Override
    public List<AuthorityReference> retrieveAuthorities(String query, List<ExtractedConcept> concepts) {
        // No knowledge-base authority source is currently available, so no
        // authorities are fabricated. When a real authority lookup is added
        // (e.g. against the knowledge base), references must be built from
        // actual entries — never from placeholder templates.
        log.debug("Authority retrieval: no knowledge-base authority source available — returning no references");
        return List.of();
    }
}
