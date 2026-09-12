package reasoning.ai.application;

import reasoning.search.model.ChunkPosition;
import reasoning.search.model.ChunkReference;
import reasoning.search.model.CitationReference;
import reasoning.search.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Temporal validity filter for retrieval results.
 *
 * <p>Only EXPLICIT dates are enforced: a result whose citation dates prove it is
 * not valid for the analysis as-of date (future start, expired end, or
 * superseded) is dropped. Unknown validity is kept — it is flagged as unknown
 * downstream, never silently treated as proven current validity.
 */
class TemporalValidityFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private SearchResult result(LocalDate validFrom, LocalDate validUntil, LocalDate supersededAt) {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        ChunkReference chunk = new ChunkReference(
                chunkId, docId, 3, "Testdokument",
                new ChunkPosition(12, null, 7, 0, 100));
        CitationReference citation = new CitationReference(
                docId, chunkId, 3, "Testdokument", 12, 0, 100,
                "Auszug", null, validFrom, validUntil, supersededAt);
        return new SearchResult(chunk, "Auszug", 0.9, 0.9, "keyword", citation);
    }

    @Test
    void keepsDocumentCurrentlyValid() {
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(result(LocalDate.of(2024, 1, 1), null, null)), TODAY);
        assertEquals(1, kept.size());
    }

    @Test
    void dropsDocumentExplicitlyExpiredForCurrentAsOf() {
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(result(LocalDate.of(2018, 1, 1), LocalDate.of(2019, 12, 31), null)), TODAY);
        assertTrue(kept.isEmpty());
    }

    @Test
    void dropsFutureDocumentForCurrentAsOf() {
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(result(LocalDate.of(2030, 1, 1), null, null)), TODAY);
        assertTrue(kept.isEmpty());
    }

    @Test
    void keepsHistoricallyValidDocumentForHistoricalAsOf() {
        LocalDate asOf = LocalDate.of(2019, 6, 1);
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(result(LocalDate.of(2018, 1, 1), LocalDate.of(2019, 12, 31), null)), asOf);
        assertEquals(1, kept.size());
    }

    @Test
    void dropsSupersededDocumentForCurrentAsOfButKeepsForEarlierDate() {
        var superseded = result(LocalDate.of(2015, 1, 1), null, LocalDate.of(2020, 1, 1));
        assertTrue(DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(superseded), TODAY).isEmpty());
        assertEquals(1, DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(superseded), LocalDate.of(2019, 6, 1)).size());
    }

    @Test
    void keepsBoundaryValidUntilEqualToAsOf() {
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(result(LocalDate.of(2018, 1, 1), TODAY, null)), TODAY);
        assertEquals(1, kept.size());
    }

    @Test
    void keepsDocumentWithUnknownValidity() {
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(result(null, null, null)), TODAY);
        assertEquals(1, kept.size());
    }

    // ── Displacement failure case: expired docs occupy top ranks, valid docs
    //    displaced beyond the original top-15; the overscanned window (simulated
    //    here as a 20-candidate input) lets the filter recover them. ──

    @Test
    void expiredDocsDisplaceValidOnesButOverscannedWindowRecoversThem() {
        List<SearchResult> candidates = new java.util.ArrayList<>();
        LocalDate expiredFrom = LocalDate.of(2018, 1, 1);
        LocalDate expiredUntil = LocalDate.of(2019, 12, 31);
        LocalDate validFrom = LocalDate.of(2024, 1, 1);
        // Ranks 1-5: expired (would occupy the original top-15 window)
        for (int i = 0; i < 5; i++) {
            candidates.add(result(expiredFrom, expiredUntil, null));
        }
        // Ranks 6-15: valid (the original top-15 window)
        for (int i = 0; i < 10; i++) {
            candidates.add(result(validFrom, null, null));
        }
        // Ranks 16-20: valid but displaced beyond the original top-15
        List<SearchResult> beyondWindow = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            beyondWindow.add(result(validFrom, null, null));
        }
        candidates.addAll(beyondWindow);

        // Without overscan the 5 beyond-window docs would never reach the filter.
        List<SearchResult> filtered = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                candidates, TODAY);
        List<SearchResult> finalSet = DefaultRetrievalAugmentationService.trimToWindow(filtered, 15);

        assertEquals(15, finalSet.size(), "final candidate set is trimmed back to the window");
        assertTrue(finalSet.stream().noneMatch(r -> r.citation().validUntil() != null),
                "no expired document survives into the final set");
        assertTrue(finalSet.stream().anyMatch(r -> beyondWindow.contains(r)),
                "valid documents displaced beyond the original top-15 survive via the overscan");
    }

    @Test
    void nullAsOfTrimsOverscannedSetWithoutFiltering() {
        List<SearchResult> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(result(LocalDate.of(2024, 1, 1), null, null));
        }
        List<SearchResult> filtered = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                candidates, null);
        List<SearchResult> finalSet = DefaultRetrievalAugmentationService.trimToWindow(filtered, 15);
        assertEquals(15, finalSet.size(), "no asOf → filter is a no-op, only the window trim applies");
        assertEquals(candidates.subList(0, 15), finalSet, "ranking order is preserved");
    }

    @Test
    void nullAsOfIsANoOp() {
        var expired = result(LocalDate.of(2018, 1, 1), LocalDate.of(2019, 12, 31), null);
        var valid = result(LocalDate.of(2024, 1, 1), null, null);
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(expired, valid), null);
        assertEquals(2, kept.size(), "no as-of date must not silently filter anything");
    }

    @Test
    void unknownValidityStaysAvailableForRagWhileExplicitlyExpiredIsDropped() {
        var unknown = result(null, null, null);
        var expired = result(LocalDate.of(2018, 1, 1), LocalDate.of(2019, 12, 31), null);
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(expired, unknown), TODAY);
        assertEquals(1, kept.size());
        assertEquals(unknown, kept.getFirst(),
                "unknown validity remains ordinary RAG evidence; only explicit dates are enforced");
    }

    @Test
    void keepsValidDocumentAlongsideDroppingInvalidOne() {
        var valid = result(LocalDate.of(2024, 1, 1), null, null);
        var expired = result(LocalDate.of(2018, 1, 1), LocalDate.of(2019, 12, 31), null);
        List<SearchResult> kept = DefaultRetrievalAugmentationService.filterByTemporalValidity(
                List.of(expired, valid), TODAY);
        assertEquals(1, kept.size());
        assertEquals(valid, kept.getFirst());
    }
}
