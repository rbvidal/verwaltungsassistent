package verwaltungsassistent.web.controller;

import reasoning.common.model.DocumentFileType;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import reasoning.search.model.ChunkType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The HYBRID presentation rule must require a SPECIFIC term match: generic
 * administrative vocabulary alone ("unterlagen", "beantragen") appears in
 * nearly every municipal document and must not qualify an unrelated document
 * as evidence-backed (e.g. a Gewerbe info sheet for a Reisepass request).
 */
class KnowledgeEvidenceTest {

    private static final String REISEPASS_QUERY =
            "Reisepass für meine Tochter beantragen meine Tochter ist 14 Jahre alt und "
            + "braucht einen neuen Reisepass. Welche Unterlagen benötigen wir für die "
            + "Beantragung eines Reisepasses für Minderjährige und muss mein Mann mitkommen?";

    private DocumentChunkEntity chunk(String text) {
        return new DocumentChunkEntity(UUID.randomUUID(), UUID.randomUUID(), 1,
                ChunkType.TEXT, text, null, null, 0, null, null,
                "titel", DocumentFileType.PDF, "OTHER", Set.of(), "upload",
                "tenant", Instant.now(), Set.of(), null);
    }

    private JpaDocumentChunkRepository repoWith(String text) {
        JpaDocumentChunkRepository repo = mock(JpaDocumentChunkRepository.class);
        when(repo.findByDocumentIdOrderByChunkIndex(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(chunk(text)));
        return repo;
    }

    @Test
    void gewerbeDoc_withOnlyGenericOverlap_isNotEvidence() {
        // The Gewerbe info sheet shares only generic words with the Reisepass
        // request — it must not count as textual evidence.
        JpaDocumentChunkRepository repo = repoWith(
                "Gewerbe anmelden. Für die Anmeldung benötigen Sie folgende Unterlagen. "
                + "Reichen Sie den Antrag ein. Beantragung der Gewerbeanmeldung.");
        assertFalse(KnowledgeController.hasTextualEvidence(repo, UUID.randomUUID(), REISEPASS_QUERY));
    }

    @Test
    void reisepassDoc_withSpecificTerm_isEvidence() {
        JpaDocumentChunkRepository repo = repoWith(
                "Reisepass beantragen. Für Minderjährige benötigen Sie Unterlagen. "
                + "Der gesetzliche Vertreter muss mitkommen.");
        assertTrue(KnowledgeController.hasTextualEvidence(repo, UUID.randomUUID(), REISEPASS_QUERY));
    }

    @Test
    void queryWithOnlyGenericTerms_isNeverEvidence() {
        JpaDocumentChunkRepository repo = repoWith(
                "Antrag und Unterlagen prüfen, benötigte Unterlagen einreichen.");
        assertFalse(KnowledgeController.hasTextualEvidence(
                repo, UUID.randomUUID(), "Bitte prüfen Sie die Unterlagen und den Antrag"));
    }
}
