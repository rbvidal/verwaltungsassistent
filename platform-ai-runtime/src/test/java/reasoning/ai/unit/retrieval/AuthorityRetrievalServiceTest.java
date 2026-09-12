package reasoning.ai.unit.retrieval;

import reasoning.ai.application.DefaultAuthorityRetrievalService;
import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityRetrievalServiceTest {

    private final DefaultAuthorityRetrievalService service = new DefaultAuthorityRetrievalService();

    @Test
    void noKnowledgeBaseBacking_neverFabricatesPlaceholderAuthorities() {
        List<ExtractedConcept> concepts = List.of(
                new ExtractedConcept("PAYMENT", "Payment / Obligation", 0.7, List.of("535", "556b"), List.of()));

        List<AuthorityReference> references = service.retrieveAuthorities("payment due", concepts);

        assertTrue(references.isEmpty(),
                "Without an actual knowledge-base authority behind a reference, "
                        + "no fabricated 'Entry N / Reference text for entry N' may be produced");
    }

    @Test
    void emptyConcepts_returnsNoAuthorities() {
        assertTrue(service.retrieveAuthorities("anything", List.of()).isEmpty());
    }
}
