package reasoning.ai.unit.evidence;

import reasoning.ai.model.EvidenceItem;
import reasoning.ai.model.EvidencePackage;
import reasoning.ai.model.EvidencePackage.Contradiction;
import reasoning.ai.model.EvidencePackage.CoverageStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for EvidencePackage DTO.
 * Covers null safety, negative counts, flag consistency, and edge cases.
 */
class EvidencePackageValidationTest {

    // ── Null safety ──

    @Test
    void shouldConvertNullItemsToEmptyList() {
        EvidencePackage pkg = new EvidencePackage(null, false, null,
                CoverageStatus.INSUFFICIENT, 0, 0, 0);
        assertNotNull(pkg.items());
        assertTrue(pkg.items().isEmpty());
    }

    @Test
    void shouldConvertNullContradictionsToEmptyList() {
        EvidencePackage pkg = new EvidencePackage(List.of(), false, null,
                CoverageStatus.SUFFICIENT, 2, 2, 2);
        assertNotNull(pkg.contradictions());
        assertTrue(pkg.contradictions().isEmpty());
    }

    @Test
    void shouldPreserveNonNullCollections() {
        EvidenceItem item = new EvidenceItem(1, java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), 1, 1, "Test Doc", "Test Authority",
                "§ 1", "Excerpt", "supports", 0.9, null,
                null, null, null, null);
        Contradiction c = new Contradiction("Test", List.of("A"), List.of("B"), "rec");
        EvidencePackage pkg = new EvidencePackage(List.of(item), false,
                List.of(c), CoverageStatus.SUFFICIENT, 3, 2, 1);
        assertEquals(1, pkg.items().size());
        assertEquals(1, pkg.contradictions().size());
    }

    // ── Negative counts ──

    @Test
    void shouldRejectNegativeTotalDocumentsSearched() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvidencePackage(List.of(), false, List.of(),
                        CoverageStatus.INSUFFICIENT, -1, 0, 0));
    }

    @Test
    void shouldRejectNegativeRelevantDocumentsFound() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvidencePackage(List.of(), false, List.of(),
                        CoverageStatus.INSUFFICIENT, 0, -1, 0));
    }

    @Test
    void shouldRejectNegativeDocumentsUsed() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvidencePackage(List.of(), false, List.of(),
                        CoverageStatus.INSUFFICIENT, 0, 0, -1));
    }

    // ── Count ordering ──

    @Test
    void shouldRejectRelevantExceedingTotal() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvidencePackage(List.of(), false, List.of(),
                        CoverageStatus.INSUFFICIENT, 5, 10, 5));
    }

    @Test
    void shouldRejectUsedExceedingRelevant() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvidencePackage(List.of(), false, List.of(),
                        CoverageStatus.PARTIAL, 10, 5, 8));
    }

    @Test
    void shouldAcceptEqualCounts() {
        EvidencePackage pkg = new EvidencePackage(List.of(), false, List.of(),
                CoverageStatus.INSUFFICIENT, 5, 5, 5);
        assertEquals(5, pkg.totalDocumentsSearched());
        assertEquals(5, pkg.relevantDocumentsFound());
        assertEquals(5, pkg.documentsUsed());
    }

    @Test
    void shouldAcceptZeroCounts() {
        EvidencePackage pkg = new EvidencePackage(List.of(), true, List.of(),
                CoverageStatus.INSUFFICIENT, 0, 0, 0);
        assertTrue(pkg.isEmpty());
        assertEquals(0, pkg.totalDocumentsSearched());
    }

    // ── Flag consistency ──

    @Test
    void shouldRejectInsufficientWithSufficientCoverage() {
        assertThrows(IllegalArgumentException.class, () ->
                new EvidencePackage(List.of(), true, List.of(),
                        CoverageStatus.SUFFICIENT, 2, 2, 2));
    }

    @Test
    void shouldAcceptInsufficientWithPartialCoverage() {
        EvidencePackage pkg = new EvidencePackage(List.of(), true, List.of(),
                CoverageStatus.PARTIAL, 1, 1, 1);
        assertTrue(pkg.hasInsufficientEvidence());
        assertEquals(CoverageStatus.PARTIAL, pkg.coverageStatus());
    }

    @Test
    void shouldAcceptInsufficientWithInsufficientCoverage() {
        EvidencePackage pkg = new EvidencePackage(List.of(), true, List.of(),
                CoverageStatus.INSUFFICIENT, 0, 0, 0);
        assertTrue(pkg.hasInsufficientEvidence());
        assertEquals(CoverageStatus.INSUFFICIENT, pkg.coverageStatus());
    }

    @Test
    void shouldAcceptSufficientCoverageWithoutInsufficientFlag() {
        EvidencePackage pkg = new EvidencePackage(List.of(), false, List.of(),
                CoverageStatus.SUFFICIENT, 2, 2, 2);
        assertFalse(pkg.hasInsufficientEvidence());
        assertEquals(CoverageStatus.SUFFICIENT, pkg.coverageStatus());
    }

    // ── Helper methods ──

    @Test
    void shouldDetectContradictions() {
        Contradiction c = new Contradiction("conflict", List.of("DocA"), List.of("DocB"), "review");
        EvidencePackage pkg = new EvidencePackage(List.of(), false,
                List.of(c), CoverageStatus.SUFFICIENT, 2, 2, 2);
        assertTrue(pkg.hasContradictions());
    }

    // ── Contradiction null safety ──

    @Test
    void contradictionShouldConvertNullDocumentLists() {
        Contradiction c = new Contradiction("desc", null, null, null);
        assertNotNull(c.documentA());
        assertNotNull(c.documentB());
        assertTrue(c.documentA().isEmpty());
        assertTrue(c.documentB().isEmpty());
    }
}
