package reasoning.ai.unit.application;

import reasoning.ai.api.ClaimVerificationService;
import reasoning.ai.application.DefaultGroundingService;
import reasoning.ai.model.ClaimVerification;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.ai.model.RetrievalContext;
import reasoning.ai.model.SourceCitation;
import reasoning.ai.model.SourceCitation.SourceTier;
import reasoning.ai.model.SourceCitation.SourceType;
import reasoning.ai.model.SourceDossier;
import reasoning.ai.model.SourceRole;
import reasoning.search.api.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The presentation gate of the grounding service: a retrieved document is not
 * automatically a Beleg. Unrelated nearest-neighbour results must be withheld
 * when the evidence does not substantively engage the question — even when the
 * verifier ENTAILED the answer's meta-claims ("the evidence contains no
 * information about X") against those unrelated excerpts.
 *
 * <p>Case A (relevant evidence exists) must still present the document as a
 * Beleg; Case B (no relevant evidence) must fail closed without citations.</p>
 */
class EvidencePresentationGateTest {

    private static final String PARKING_QUESTION =
            "Wo kann man einen Parkausweis für Personen mit Behinderungen beantragen?";

    private static final String SUFFICIENCY_CLAIM =
            "The available evidence is sufficient to answer the user's question.";

    /** German generic vocabulary — mirrors the municipal stop-words config. */
    private static final Set<String> STOP_WORDS = Set.of(
            "einen", "person", "personen", "beantragen", "beantragung",
            "unterlagen", "antrag", "prüfen", "benötigen", "kann", "können",
            "wo", "man", "für", "mit", "einer", "einem", "eines", "und", "die",
            "der", "das", "den", "dem", "des", "ist", "im", "in", "an", "am");

    private static final String HONEST_ANSWER = """
            KURZANTWORT
            Die angegebenen Beweisstücke enthalten keine Informationen zum Beantragen eines Parkausweises für Personen mit Behinderungen.

            ENTSCHEIDUNG
            Es ist empfehlenswert, die zuständige Behörde zu kontaktieren.

            RECHTSGRUNDLAGE
            Keine entsprechenden Dokumente vorhanden
            """;

    private static final EmbeddingProvider embeddingStub = new EmbeddingProvider() {
        public float[] embed(String text) { return new float[768]; }
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(t -> new float[768]).toList();
        }
        public int dimension() { return 768; }
        public String modelName() { return "test-stub"; }
    };

    /** Verifier stub: per-claim ENTAILED verdicts; everything else UNKNOWN. */
    private static final class ConfigurableVerifier implements ClaimVerificationService {
        private final Set<String> entailedClaims;
        private final boolean sufficiencyEntailed;

        ConfigurableVerifier(Set<String> entailedClaims, boolean sufficiencyEntailed) {
            this.entailedClaims = entailedClaims;
            this.sufficiencyEntailed = sufficiencyEntailed;
        }

        @Override
        public ClaimVerification verify(String claim, String evidenceExcerpt) {
            boolean entailed = claim.trim().equals(SUFFICIENCY_CLAIM)
                    ? sufficiencyEntailed : entailedClaims.contains(claim.trim());
            return new ClaimVerification(claim, evidenceExcerpt,
                    entailed ? ClaimVerification.Verdict.ENTAILED : ClaimVerification.Verdict.UNKNOWN,
                    entailed ? 0.85 : 0.2, "test stub");
        }

        @Override
        public List<ClaimVerification> verifyBatch(String claim, List<String> evidenceExcerpts) {
            return evidenceExcerpts.stream().map(e -> verify(claim, e)).toList();
        }
    }

    private DefaultGroundingService groundingService;

    @BeforeEach
    void setUp() {
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.of(), false));
        groundingService.setStopWords(STOP_WORDS);
    }

    private static SourceCitation source(String title, String excerpt, double score) {
        return new SourceCitation(UUID.randomUUID(), UUID.randomUUID(), 1, title,
                null, null, null, excerpt, score, SourceTier.PRIMARY, SourceType.FACTUAL);
    }

    private RetrievalContext context(List<SourceCitation> sources) {
        return context(PARKING_QUESTION, sources);
    }

    private RetrievalContext context(String query, List<SourceCitation> sources) {
        Map<SourceRole, List<String>> byRole = new EnumMap<>(SourceRole.class);
        for (SourceRole role : SourceRole.values()) byRole.put(role, List.of());
        SourceDossier dossier = new SourceDossier(byRole, List.of(), List.of(), 1.0,
                "Quellen vorhanden");
        return new RetrievalContext(query, "HYBRID_RETRIEVAL", sources,
                List.of(), null, dossier, null, null);
    }

    private static List<SourceCitation> unrelatedSources() {
        // Mirrors the observed runtime evidence: nearest neighbours whose only
        // lexical overlap is generic vocabulary ("Personenverkehr", "einen").
        return List.of(
                source("Bauordnung für Berlin (BauO Bln)",
                        "dem öffentlichen Personenverkehr oder der Schülerbeförderung "
                                + "dienen, f) Schutzhütten für Wanderinnen oder Wanderer", 0.62),
                source("Reisepass beantragen",
                        "falls Sie noch nie ein Dokument wie beispielsweise einen "
                                + "Personalausweis oder einen Reisepass hatten", 0.58),
                source("Melderegister - Auskunftssperre im Melderegister eintragen lassen",
                        "Eine Aufhebung der Auskunftssperre ist jederzeit schriftlich "
                                + "durch den Antragsteller möglich", 0.55));
    }

    private static List<String> claimSections(String rawAnswer) {
        return List.of(
                "Die angegebenen Beweisstücke enthalten keine Informationen zum Beantragen eines Parkausweises für Personen mit Behinderungen.",
                "Es ist empfehlenswert, die zuständige Behörde zu kontaktieren.",
                "Keine entsprechenden Dokumente vorhanden");
    }

    /** Case B: the verifier ENTAILED the honest meta-claims, but the evidence is unrelated. */
    @Test
    void unrelatedEvidence_metaClaimsEntailedButNoAnchor_failsClosedWithoutBelege() {
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.copyOf(claimSections(HONEST_ANSWER)), false));
        groundingService.setStopWords(STOP_WORDS);

        ReasonedAnswer answer = groundingService.ground(HONEST_ANSWER,
                context(unrelatedSources()));

        assertEquals("Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor.",
                answer.answer(), "the honest insufficient-evidence answer must be presented");
        assertTrue(answer.sourceCitations().isEmpty(),
                "unrelated documents must NOT be displayed as Belege");
        assertTrue(answer.authorityReferences().isEmpty());
        assertEquals(0.0, answer.confidence().overallConfidence(), 1e-9,
                "no misleading confidence when the evidence is unrelated");
        assertFalse(answer.grounded());
    }

    /** Case A: a genuinely relevant document exists — it IS presented as a Beleg. */
    @Test
    void relevantEvidence_entailsAndAnchors_presentedWithBelege() {
        SourceCitation relevant = source("Parkausweis für Menschen mit Behinderungen beantragen",
                "Für einen Parkausweis für Menschen mit Behinderungen wenden Sie sich an "
                        + "die zuständige Straßenverkehrsbehörde. Der Antrag ist formlos möglich.", 0.8);
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.copyOf(claimSections(HONEST_ANSWER)), true));
        groundingService.setStopWords(STOP_WORDS);

        ReasonedAnswer answer = groundingService.ground(HONEST_ANSWER,
                context(List.of(relevant)));

        assertEquals(HONEST_ANSWER.trim(), answer.answer().trim(),
                "the answer itself is preserved when evidence exists");
        assertEquals(1, answer.sourceCitations().size(),
                "the relevant document must be presented as a Beleg");
        assertEquals("Parkausweis für Menschen mit Behinderungen beantragen",
                answer.sourceCitations().getFirst().title());
        assertTrue(answer.confidence().overallConfidence() > 0.0);
    }

    /** Case C (partial evidence): only one of two factual components is supported. */
    @Test
    void partialEvidence_supportedPartKept_unsupportedPartExplicit_noUnrelatedBelege() {
        // The question combines a covered component (Reisepass) with an
        // uncovered one (Parkausweis). The relevant Reisepass source anchors;
        // the unrelated nearest neighbours must NOT become Belege.
        SourceCitation relevant = source("Reisepass beantragen",
                "Der Reisepass kann bei der Landesbehörde in Berlin beantragt werden. "
                        + "Voraussetzungen und Gebühren für Minderjährige.", 0.8);
        String partialAnswer = """
                KURZANTWORT
                Einen Reisepass kann man bei der Landesbehörde in Berlin beantragen.

                ENTSCHEIDUNG
                Zu einem Parkausweis für Menschen mit Behinderungen liegen in der Wissensbasis keine ausreichenden Informationen vor.

                RECHTSGRUNDLAGE
                Reisepass: [Reisepass beantragen]
                """;
        // only the Reisepass claim is supported; the parking claim stays unsupported.
        // The retrieval layer (anchorEvidence) already dropped the unrelated
        // nearest neighbours before grounding — see EvidenceAnchorTest.
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.of(
                        "Einen Reisepass kann man bei der Landesbehörde in Berlin beantragen."), false));
        groundingService.setStopWords(STOP_WORDS);

        ReasonedAnswer answer = groundingService.ground(partialAnswer,
                context("Wo kann man einen Reisepass beantragen und wo einen Parkausweis "
                        + "für Menschen mit Behinderungen?", List.of(relevant)));

        // the answer itself is preserved (not replaced by the blanket
        // insufficient-evidence outcome) — partial evidence is NOT zero evidence
        assertEquals(partialAnswer.trim(), answer.answer().trim());
        assertEquals(1, answer.sourceCitations().size(),
                "only the genuinely relevant Reisepass document is a Beleg");
        assertEquals("Reisepass beantragen", answer.sourceCitations().getFirst().title());
        // the unsupported component is visible in the finding trail
        assertFalse(answer.findingHierarchy().primaryFindings().isEmpty());
        assertFalse(answer.findingHierarchy().secondaryFindings().isEmpty(),
                "the unsupported parking component must remain explicitly unsupported");
        // not fully grounded: an unsupported component exists
        assertFalse(answer.grounded());
    }

    /** Partial evidence must NOT carry the same confidence as full evidence. */
    @Test
    void partialEvidence_confidenceIsLowerThanFullEvidence() {
        String fullAnswer = """
                KURZANTWORT
                Einen Reisepass kann man bei der Landesbehörde in Berlin beantragen.
                """;
        String partialAnswer = """
                KURZANTWORT
                Einen Reisepass kann man bei der Landesbehörde in Berlin beantragen.

                ENTSCHEIDUNG
                Zu einem Parkausweis für Menschen mit Behinderungen liegen keine Informationen vor.
                """;
        SourceCitation relevant = source("Reisepass beantragen",
                "Der Reisepass kann bei der Landesbehörde in Berlin beantragt werden.", 0.8);

        // full evidence: both claims supported (both are about the Reisepass)
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.of(
                        "Einen Reisepass kann man bei der Landesbehörde in Berlin beantragen."), true));
        groundingService.setStopWords(STOP_WORDS);
        ReasonedAnswer full = groundingService.ground(fullAnswer,
                context("Wo kann man einen Reisepass beantragen?", List.of(relevant)));

        // partial evidence: only the Reisepass claim supported
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.of(
                        "Einen Reisepass kann man bei der Landesbehörde in Berlin beantragen."), false));
        groundingService.setStopWords(STOP_WORDS);
        ReasonedAnswer partial = groundingService.ground(partialAnswer,
                context("Wo kann man einen Reisepass beantragen und wo einen Parkausweis "
                        + "für Menschen mit Behinderungen?", List.of(relevant)));

        assertTrue(full.confidence().overallConfidence() > partial.confidence().overallConfidence(),
                "some-evidence-exists must not equal entire-answer-supported: "
                        + "full=" + full.confidence().overallConfidence()
                        + " partial=" + partial.confidence().overallConfidence());
        assertTrue(partial.confidence().overallConfidence() > 0.0,
                "partial evidence is not zero evidence");
    }

    /** A title-only match never anchors: the document body must engage the question. */
    @Test
    void titleOnlyMatch_doesNotAnchor_unrelatedBodyFailsClosed() {
        SourceCitation titleOnly = source("Parkausweis für Menschen mit Behinderungen beantragen",
                "Gebührenordnung für Verwaltungsleistungen. Die Gebühr ist bei Antragstellung "
                        + "innerhalb von zwei Wochen zu entrichten.", 0.6);
        groundingService = new DefaultGroundingService(embeddingStub,
                new ConfigurableVerifier(Set.copyOf(claimSections(HONEST_ANSWER)), false));
        groundingService.setStopWords(STOP_WORDS);

        ReasonedAnswer answer = groundingService.ground(HONEST_ANSWER, context(List.of(titleOnly)));

        assertEquals("Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor.",
                answer.answer());
        assertTrue(answer.sourceCitations().isEmpty(),
                "a heading that resembles the question is not a Beleg");
    }

}
