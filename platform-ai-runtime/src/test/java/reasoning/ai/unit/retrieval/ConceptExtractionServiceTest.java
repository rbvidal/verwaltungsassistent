package reasoning.ai.unit.retrieval;

import reasoning.ai.application.DefaultConceptExtractionService;
import reasoning.ai.model.ExtractedConcept;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConceptExtractionServiceTest {

    private final DefaultConceptExtractionService service = new DefaultConceptExtractionService();

    @Test
    void genericNonMatchingQuestion_fallsBackWithoutGoverningReferences() {
        List<ExtractedConcept> concepts = service.classify(
                "Analysiere den Fall Müller - Wohngeld. Dokumente: 0. Erstelle eine Empfehlung.");

        assertEquals(1, concepts.size());
        assertEquals("GENERAL", concepts.get(0).concept());
        assertTrue(concepts.get(0).governingReferences().isEmpty(),
                "The generic fallback must not fabricate legal references (e.g. §535 BGB)");
    }

    @Test
    void matchingConcept_stillCarriesItsGoverningReferences() {
        // "arrears" matches the DEFAULT rule, which references 543/569/573.
        List<ExtractedConcept> concepts = service.classify("tenant is in arrears and did not pay");

        assertTrue(concepts.stream().anyMatch(c -> !c.governingReferences().isEmpty()),
                "Matched domain concepts keep their governing references");
        assertTrue(concepts.stream().noneMatch(c -> c.concept().equals("GENERAL")));
        assertFalse(concepts.get(0).governingReferences().isEmpty());
    }
}
