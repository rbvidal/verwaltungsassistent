package reasoning.ai.application;

import reasoning.search.model.ChunkReference;
import reasoning.search.model.CitationReference;
import reasoning.search.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence-anchor rule: generic administrative vocabulary alone must not
 * qualify an unrelated document as supporting evidence. When at least one
 * retrieved source carries a specific question term, sources without any
 * specific term are dropped; with no anchor anywhere (cross-lingual queries,
 * purely generic questions) the raw ranking stands.
 */
class EvidenceAnchorTest {

    private static final Set<String> GENERIC = Set.of(
            "unterlagen", "antrag", "prüfen", "benötigen", "benötigte", "termin",
            "online", "september", "dokument", "dokumente", "vorschriften", "phase",
            "einrichtung", "analysiere", "erstelle", "faktenzusammenfassung",
            "anwendbare", "handlungsschritte", "risiken", "empfehlung",
            "fall", "fälle", "wurde",
            // high-frequency German function/general words must never anchor:
            // "einen" appears in nearly every administrative sentence and
            // "personen" in nearly every public-service document (e.g. in
            // "Personenverkehr" or "Personalausweis" contexts).
            "einen", "person", "personen", "beantragen", "beantragung",
            "kann", "können", "wo", "man", "für", "mit", "einer", "einem",
            "eines", "und", "die", "der", "das", "den", "dem", "des", "ist",
            "im", "in", "an", "am", "bitte", "haben", "habe", "meine", "nicht",
            "noch", "auch", "nach", "sich", "wir", "uns", "muss", "müssen",
            "welche", "welcher", "welches", "wann", "warum", "bereits", "schon");

    private static final String GEWERBE_QUESTION =
            "Analysiere den Fall \"Gewerbeanmeldung\" (Gewerbeanmeldung zum 1. September – "
            + "benötigte Unterlagen und Online-Termin). Phase: Einrichtung. Dokumente: 0. Erstelle: "
            + "1) Faktenzusammenfassung, 2) anwendbare Vorschriften, 3) Handlungsschritte, "
            + "4) Risiken, 5) Empfehlung.";

    /**
     * Result mit einem realistischeren keywordScore: 0.0 für Dokumente ohne
     * lexikalische Verbindung zur Frage, > 0 für Dokumente, die Frage-Wörter
     * enthalten (der Anker arbeitet seit dem semantisch-primären Retrieval
     * über den keywordScore, nicht mehr über einen reinen Text-Check).
     */
    private SearchResult result(String text, double keywordScore) {
        ChunkReference ref = new ChunkReference(UUID.randomUUID(), UUID.randomUUID(), 1,
                "titel", null, null);
        return new SearchResult(ref, text, 0.7, 0.7, "hybrid",
                new CitationReference(UUID.randomUUID(), UUID.randomUUID(), 1,
                        "titel", null, null, null, null), keywordScore, 0.5, 0.0,
                "GENERAL", "HYBRID_RETRIEVAL");
    }

    private SearchResult result(String text) {
        return result(text, 0.5);
    }

    @Test
    void gewerbeanmeldung_unrelatedDocsWithoutSpecificTerm_areDropped() {
        List<SearchResult> results = List.of(
                result("Gewerbe anmelden. Für die Gewerbeanmeldung benötigen Sie Unterlagen "
                        + "und reichen den Antrag ein.", 0.5),
                result("Die Gewerbeanmeldung muss vor Beginn der Tätigkeit erfolgen.", 0.5),
                result("Reisepass beantragen. Voraussetzungen und Gebühren für Minderjährige.", 0.0),
                result("Online-Ausweisfunktion (eID) nachträglich aktivieren.", 0.0));

        List<SearchResult> anchored = DefaultRetrievalAugmentationService.anchorEvidence(
                GEWERBE_QUESTION, results, GENERIC);

        // Der Anker verwirft Dokumente ohne lexikalische Verbindung, sobald
        // mindestens zwei verankerte Kandidaten existieren (Sicherheitsnetz:
        // nie auf einen einzigen Treffer reduzieren).
        assertEquals(2, anchored.size(), "only the docs carrying a specific term survive");
        assertTrue(anchored.stream().allMatch(r -> r.text().contains("Gewerbeanmeldung")));
    }

    @Test
    void genericOnlyQuestion_keepsAllResults() {
        List<SearchResult> results = List.of(
                result("Antrag prüfen. Unterlagen vollständig einreichen.", 0.0),
                result("Benötigte Unterlagen anfordern.", 0.0));

        List<SearchResult> anchored = DefaultRetrievalAugmentationService.anchorEvidence(
                "Bitte prüfen Sie die Unterlagen und den Antrag", results, GENERIC);

        assertEquals(2, anchored.size(), "no specific anchor — raw ranking stands");
    }

    @Test
    void crossLingualQuery_withoutAnyAnchor_keepsAllResults() {
        List<SearchResult> results = List.of(
                result("Gewerbe anmelden. Für die Anmeldung benötigen Sie Unterlagen.", 0.0),
                result("Reisepass beantragen. Voraussetzungen.", 0.0));

        List<SearchResult> anchored = DefaultRetrievalAugmentationService.anchorEvidence(
                "How much is the fee for a business registration?", results, GENERIC);

        assertEquals(2, anchored.size(), "no German specific term matches — no hard drop");
    }

    @Test
    void specificTerms_excludeConfiguredGenericVocabulary() {
        List<String> terms = DefaultRetrievalAugmentationService.specificTerms(
                "Gewerbeanmeldung zum September – benötigte Unterlagen und Online-Termin",
                GENERIC);

        assertEquals(List.of("gewerbeanmeldung"), terms);
    }

    @Test
    void parkausweisQuestion_junkAnchorsAreExcluded() {
        List<String> terms = DefaultRetrievalAugmentationService.specificTerms(
                "Wo kann man einen Parkausweis für Personen mit Behinderungen beantragen?",
                GENERIC);

        // "einen" and "personen" are generic — only the substantive terms remain
        assertEquals(List.of("parkausweis", "behinderungen"), terms);
    }

    @Test
    void parkausweisQuestion_unrelatedDocsDropped_whenRelevantDocPresent() {
        List<SearchResult> results = List.of(
                result("dem öffentlichen Personenverkehr oder der Schülerbeförderung "
                        + "dienen, f) Schutzhütten für Wanderinnen oder Wanderer", 0.0),
                result("falls Sie noch nie ein Dokument wie beispielsweise einen "
                        + "Personalausweis oder einen Reisepass hatten", 0.0),
                result("Eine Aufhebung der Auskunftssperre ist jederzeit schriftlich "
                        + "durch den Antragsteller möglich", 0.0),
                result("Für einen Parkausweis für Menschen mit Behinderungen wenden Sie "
                        + "sich an die zuständige Straßenverkehrsbehörde. Der Antrag ist formlos möglich.", 0.5),
                result("Für Menschen mit Behinderungen gilt ein vereinfachtes "
                        + "Antragsverfahren für den Parkausweis.", 0.5));

        List<SearchResult> anchored = DefaultRetrievalAugmentationService.anchorEvidence(
                "Wo kann man einen Parkausweis für Personen mit Behinderungen beantragen?",
                results, GENERIC);

        assertEquals(2, anchored.size(), "only the genuinely relevant parking permit docs survive");
        assertTrue(anchored.stream().allMatch(r -> r.text().contains("Parkausweis")
                || r.text().contains("Behinderungen")));
    }

    @Test
    void parkausweisQuestion_noRelevantDoc_rawRankingStands() {
        // Case B: NO parking permit document exists in the corpus. The anchor
        // finds no specific term anywhere and must NOT drop or promote anything —
        // the presentation gate downstream is what must refuse to show Belege.
        List<SearchResult> results = List.of(
                result("dem öffentlichen Personenverkehr oder der Schülerbeförderung dienen", 0.0),
                result("falls Sie noch nie ein Dokument wie beispielsweise einen Reisepass hatten", 0.0),
                result("Eine Aufhebung der Auskunftssperre ist jederzeit schriftlich möglich", 0.0));

        List<SearchResult> anchored = DefaultRetrievalAugmentationService.anchorEvidence(
                "Wo kann man einen Parkausweis für Personen mit Behinderungen beantragen?",
                results, GENERIC);

        assertEquals(3, anchored.size(), "no anchor — raw ranking stands (honest nearest results)");
    }
}
