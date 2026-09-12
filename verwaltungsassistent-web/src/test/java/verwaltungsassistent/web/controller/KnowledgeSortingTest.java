package verwaltungsassistent.web.controller;

import verwaltungsassistent.web.controller.KnowledgeController.KbDocumentRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Server-side sorting of the Wissen overview table: every column must sort
 * in both directions.
 */
class KnowledgeSortingTest {

    private List<KbDocumentRow> rows() {
        List<KbDocumentRow> rows = new ArrayList<>();
        rows.add(new KbDocumentRow(UUID.randomUUID(), "Reisepass beantragen", "PDF", "Bürgerdienste",
                "Bereit", "success", 12, "20.08.2026"));
        rows.add(new KbDocumentRow(UUID.randomUUID(), "Gewerbeanmeldung", "PDF", "Wirtschaft",
                "Bereit", "success", 3, "18.08.2026"));
        rows.add(new KbDocumentRow(UUID.randomUUID(), "Baugenehmigung", "TXT", "Bauen",
                "Entwurf", "warning", 40, "22.08.2026"));
        return rows;
    }

    private List<String> titles(List<KbDocumentRow> sorted) {
        return sorted.stream().map(KbDocumentRow::title).toList();
    }

    @Test
    void sortsByTitleBothDirections() {
        assertEquals(List.of("Baugenehmigung", "Gewerbeanmeldung", "Reisepass beantragen"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments("title", "asc")).toList()));
        assertEquals(List.of("Reisepass beantragen", "Gewerbeanmeldung", "Baugenehmigung"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments("title", "desc")).toList()));
    }

    @Test
    void sortsByChunksAndCategory() {
        assertEquals(List.of("Gewerbeanmeldung", "Reisepass beantragen", "Baugenehmigung"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments("chunks", "asc")).toList()));
        assertEquals(List.of("Baugenehmigung", "Reisepass beantragen", "Gewerbeanmeldung"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments("category", "asc")).toList()));
    }

    @Test
    void sortsByStatusAndCreatedAt() {
        assertEquals(List.of("Reisepass beantragen", "Gewerbeanmeldung", "Baugenehmigung"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments("status", "asc")).toList()));
        assertEquals(List.of("Baugenehmigung", "Reisepass beantragen", "Gewerbeanmeldung"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments("createdAt", "desc")).toList()));
    }

    @Test
    void defaultSort_isTitleAscending() {
        assertEquals(List.of("Baugenehmigung", "Gewerbeanmeldung", "Reisepass beantragen"),
                titles(rows().stream().sorted(KnowledgeController.sortKbDocuments(null, null)).toList()));
    }
}
