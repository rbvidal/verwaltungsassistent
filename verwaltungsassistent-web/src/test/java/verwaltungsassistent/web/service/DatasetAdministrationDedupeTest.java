package verwaltungsassistent.web.service;

import reasoning.common.model.DocumentStatus;
import reasoning.document.api.DocumentFacade;
import reasoning.search.api.IndexingOrchestrationService;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.DocumentPage;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2D.16 — Ein Datensatz-Neuaufbau darf je Dokument nur EINEN
 * Indexierungs-Job erzeugen: Dokumente, die in den Schritten 1–3 bereits
 * (neu) indexiert wurden, werden im Korpus-Neuaufbau (Schritt 4) übersprungen.
 * Zuvor erzeugte ein einzelner Neuaufbau zwei identische Verlaufszeilen je
 * Dokument („doppelte Indexierung").
 */
class DatasetAdministrationDedupeTest {

    @TempDir
    Path tempDir;

    private DocumentFacade documentFacade;
    private IndexingOrchestrationService orchestration;
    private DatasetAdministrationService service;
    private final Document doc = new Document(
            UUID.randomUUID(), "tenant",
            new DocumentMetadata("x.txt", reasoning.common.model.DocumentFileType.TXT,
                    "OTHER", java.util.Set.of(), "INTERNAL"),
            DocumentStatus.READY, 1, "u", "u", Instant.now(), Instant.now(),
            null, null, null, null, List.of());

    @BeforeEach
    void setUp() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("x.txt"), "Inhalt");
        documentFacade = mock(DocumentFacade.class);
        orchestration = mock(IndexingOrchestrationService.class);
        when(documentFacade.findDocuments(any(DocumentFilter.class)))
                .thenAnswer(i -> new DocumentPage(List.of(doc), 0, 1, 1, 1));
        service = new DatasetAdministrationService(tempDir.toString(), documentFacade,
                orchestration, mock(verwaltungsassistent.web.ingestion.DefaultDocumentIngestionProcessor.class),
                mock(GeoPhotoService.class), mock(GeoPhotoRepository.class));
    }

    @Test
    void rebuild_indexesEachRegisteredDocumentOnlyOnce() {
        var report = service.rebuild(stage -> { });

        // Schritt 1 (vorhandenes Dokument unter uploads/docs → reindex) …
        verify(orchestration, times(1)).reindexDocument(doc.id());
        // … und Schritt 4 (Korpus-Neuaufbau) überspringt dieses Dokument.
        assertEquals(1, report.docsReindexed(), "docs/ existing document reindexed once");
        assertEquals(0, report.corpusReindexed(),
                "the corpus rebuild must not index freshly processed documents a second time");
    }
}
