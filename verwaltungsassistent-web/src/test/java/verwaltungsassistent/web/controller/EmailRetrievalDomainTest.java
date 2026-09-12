package verwaltungsassistent.web.controller;

import reasoning.ai.application.DomainClassifier;
import reasoning.ai.application.DomainGate;
import reasoning.ai.model.Domain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for e-mail retrieval relevance: an Imbiss/Gewerbe e-mail
 * must classify into the GEWERBE domain and the domain gate must reject
 * unrelated material (e.g. Binnenschifffahrt) so it is never presented as
 * relevant evidence.
 */
@SpringBootTest
@ActiveProfiles("dev")
class EmailRetrievalDomainTest {

    @Autowired
    private DomainClassifier domainClassifier;

    @Autowired
    private DomainGate domainGate;

    @Test
    void imbissEmail_classifiesAsGewerbe() {
        Domain domain = domainClassifier.classify(
                "Ich möchte einen Imbiss eröffnen. Was muss ich anmelden und wo?").primary();
        assertEquals("GEWERBE", domain.name(),
                "an Imbiss opening e-mail must be understood as Gewerbe/Gastronomie");
    }

    @Test
    void gewerbeanmeldung_classifiesAsGewerbe() {
        Domain domain = domainClassifier.classify("Wie melde ich ein Gewerbe an?").primary();
        assertEquals("GEWERBE", domain.name());
    }

    @Test
    void gewerbeDomain_rejectsUnrelatedShippingMaterial() {
        Domain gewerbe = Domain.of("GEWERBE");
        DomainGate.FilterResult result = domainGate.filterByDomain(gewerbe, List.of(
                "Binnenschifffahrt - Personalverordnung",
                "Gewerbeanmeldung in Berlin",
                "Gaststättenerlaubnis beantragen",
                "Bauordnung für Berlin"));

        assertTrue(result.rejected().contains("Binnenschifffahrt - Personalverordnung"),
                "shipping material must not pass as Gewerbe evidence");
        assertTrue(result.rejected().contains("Bauordnung für Berlin"));
        assertFalse(result.rejected().contains("Gewerbeanmeldung in Berlin"),
                "Gewerbe documents must be accepted");
        assertFalse(result.rejected().contains("Gaststättenerlaubnis beantragen"),
                "Gastronomie documents must be accepted");
    }

    @Test
    void generalDomain_acceptsEverything() {
        DomainGate.FilterResult result = domainGate.filterByDomain(Domain.GENERAL, List.of(
                "Binnenschifffahrt - Personalverordnung"));
        assertTrue(result.rejected().isEmpty(),
                "without a specific domain no document may be hard-rejected");
    }
}
