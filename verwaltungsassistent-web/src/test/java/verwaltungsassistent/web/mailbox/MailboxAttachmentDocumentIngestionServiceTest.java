package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import reasoning.common.model.DocumentFileType;
import reasoning.common.model.DocumentStatus;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.DocumentFilter;
import reasoning.document.api.DocumentPage;
import reasoning.document.model.Document;
import reasoning.document.model.DocumentMetadata;
import reasoning.workspace.api.AttachDocumentCommand;
import reasoning.workspace.api.WorkspaceDocumentLinkEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2C.3a: E-Mail-Anhänge werden über die BESTEHENDE Dokument-Pipeline
 * (createDocument → Ingestion-Job → ingest → completeIngestion) als normale
 * Fall-Dokumente übernommen — mit Provenienz-Tags, Idempotenz (Teil-Identität
 * messageId+partId) und Fehlerisolierung (nicht unterstützte Anhänge).
 */
class MailboxAttachmentDocumentIngestionServiceTest {

    private final DocumentFacade documentFacade = mock(DocumentFacade.class);
    private final reasoning.document.api.DocumentIngestionProcessor processor =
            mock(reasoning.document.api.DocumentIngestionProcessor.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final MailboxAttachmentDocumentIngestionService service =
            new MailboxAttachmentDocumentIngestionService(
                    documentFacade, processor, workspaceService, "target/2c3a-test-uploads");

    private static final byte[] PDF_BYTES = {37, 80, 68, 70, 45, 49, 46, 52}; // %PDF-1.4

    private static IncomingMessage.Attachment attachment(String filename, byte[] bytes, int partId) {
        return new IncomingMessage.Attachment(filename, "application/pdf", bytes, partId);
    }

    private static IncomingMessage message(List<IncomingMessage.Attachment> attachments) {
        return new IncomingMessage("msg-1", null, null,
                "Erika Schulze", "erika.schulze@example.de", List.of("info@verwaltungs-demo.de"),
                "Wohngeldantrag – Unterlagen", "Betreff: Wohngeldantrag\n\nUnterlagen anbei.",
                attachments, Instant.now());
    }

    private static Document doc(UUID id, String title) {
        return new Document(id, "default",
                new DocumentMetadata(title, DocumentFileType.PDF, "OTHER", Set.of(), "INTERNAL"),
                DocumentStatus.READY, 1, "admin@verwaltungsassistent.local", "admin@verwaltungsassistent.local",
                Instant.now(), Instant.now(), List.of());
    }

    private static DocumentPage pageOf(List<Document> documents) {
        return new DocumentPage(documents, 0, documents.size(), documents.size(), 1);
    }

    @Test
    void importsSupportedAttachment_withProvenanceTags_andRunsIngestion() throws Exception {
        when(documentFacade.findDocuments(any())).thenReturn(pageOf(List.of()));
        ArgumentCaptor<CreateDocumentCommand> cmdCaptor = ArgumentCaptor.forClass(CreateDocumentCommand.class);
        when(documentFacade.createDocument(cmdCaptor.capture()))
                .thenAnswer(i -> doc(UUID.randomUUID(), "Mietvertrag.pdf"));
        when(documentFacade.createIngestionJob(any(), any())).thenReturn(
                new reasoning.document.model.DocumentIngestionJob(
                        UUID.randomUUID(), UUID.randomUUID(),
                        reasoning.common.model.IngestionStatus.RUNNING,
                        null, null, null, null, Instant.now(), Instant.now(), null, 0));
        when(workspaceService.getWorkspaceDocuments(any())).thenReturn(List.of());

        var result = service.ingestAttachments(
                message(List.of(attachment("Mietvertrag.pdf", PDF_BYTES, 0))), null);

        assertEquals(1, result.imported());
        assertEquals(0, result.failed());
        CreateDocumentCommand cmd = cmdCaptor.getValue();
        assertNotNull(cmd);
        assertEquals("Mietvertrag.pdf", cmd.title());
        assertEquals(DocumentFileType.PDF, cmd.type());
        assertEquals("application/pdf", cmd.contentType());
        assertEquals(PDF_BYTES.length, cmd.sizeBytes());
        assertEquals("local", cmd.storageProvider());
        assertEquals("OTHER", cmd.category());
        assertEquals("INTERNAL", cmd.visibility());
        assertEquals(DemoMailboxServer.MAILBOX_USER, cmd.actorId());
        assertEquals("default", cmd.tenantId());
        // Provenienz über das kleinste bestehende Mittel: Tags.
        assertTrue(cmd.tags().contains("email:msg-1"), "E-Mail-Provenienz-Tag");
        assertTrue(cmd.tags().contains("email-attach:msg-1:0"), "Anhang-Identitäts-Tag");
        assertTrue(cmd.tags().stream().anyMatch(t -> t.startsWith("sha256:")), "Inhalts-Tag");
        // Datei liegt im Upload-Verzeichnis unter dem Storage-Key.
        Path stored = Path.of("target/2c3a-test-uploads", cmd.storageKey());
        assertTrue(Files.isRegularFile(stored), "Anhang muss wie ein Upload im Storage liegen");
        assertArrayEquals(PDF_BYTES, Files.readAllBytes(stored));
        // Ingestion über die bestehende Pipeline (Upload-Muster).
        verify(documentFacade).createIngestionJob(any(), any());
        verify(documentFacade).startIngestion(any(), any());
        verify(processor).ingest(any());
        verify(documentFacade).completeIngestion(any(), any());
        verify(workspaceService, never()).attachDocument(any());
    }

    @Test
    void skipsAlreadyImportedAttachment_secondImportIsIdempotent() {
        UUID existing = UUID.randomUUID();
        when(documentFacade.findDocuments(any())).thenReturn(pageOf(List.of(doc(existing, "Mietvertrag.pdf"))));

        var result = service.ingestAttachments(
                message(List.of(attachment("Mietvertrag.pdf", PDF_BYTES, 0))), null);

        assertEquals(0, result.imported(), "bereits übernommener Anhang wird übersprungen");
        assertEquals(0, result.failed());
        verify(documentFacade, never()).createDocument(any());
        verify(documentFacade, never()).createIngestionJob(any(), any());
        ArgumentCaptor<DocumentFilter> filterCaptor = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(documentFacade).findDocuments(filterCaptor.capture());
        assertEquals("email-attach:msg-1:0", filterCaptor.getValue().tag(),
                "Deduplizierung über die Anhang-Identität (E-Mail + Teil)");
    }

    @Test
    void unsupportedAttachment_failsIsolated_withoutDocumentCreation() {
        when(documentFacade.findDocuments(any())).thenReturn(pageOf(List.of()));

        var result = service.ingestAttachments(
                message(List.of(attachment("anhang.exe", new byte[]{1}, 0))), null);

        assertEquals(0, result.imported());
        assertEquals(1, result.failed(), "nicht unterstützter Anhang wird gezählt, nicht geworfen");
        verify(documentFacade, never()).createDocument(any());
    }

    @Test
    void attachesToWorkspace_whenProvided_butNeverTwice() {
        String workspaceId = "11111111-1111-1111-1111-111111111111";
        UUID docId = UUID.randomUUID();
        when(documentFacade.findDocuments(any())).thenReturn(pageOf(List.of()));
        when(documentFacade.createDocument(any())).thenAnswer(i -> doc(docId, "Mietvertrag.pdf"));
        when(workspaceService.getWorkspaceDocuments(workspaceId)).thenReturn(List.of());

        var first = service.ingestAttachments(
                message(List.of(attachment("Mietvertrag.pdf", PDF_BYTES, 0))), workspaceId);

        assertEquals(1, first.imported());
        ArgumentCaptor<AttachDocumentCommand> cmdCaptor = ArgumentCaptor.forClass(AttachDocumentCommand.class);
        verify(workspaceService).attachDocument(cmdCaptor.capture());
        assertEquals(workspaceId, cmdCaptor.getValue().workspaceId());
        assertNotNull(cmdCaptor.getValue().documentId());
        assertNotNull(cmdCaptor.getValue().documentType(), "Dokumenttyp ist Pflicht (nullable=false)");
        assertEquals("E-Mail-Anhang (automatisch übernommen)", cmdCaptor.getValue().notes());
        // Bereits verknüpft → kein weiterer attachDocument-Aufruf (Link-Idempotenz).
        when(workspaceService.getWorkspaceDocuments(workspaceId))
                .thenReturn(List.of(link(workspaceId, cmdCaptor.getValue().documentId())));
        var second = service.ingestAttachments(
                message(List.of(attachment("Mietvertrag.pdf", PDF_BYTES, 0))), workspaceId);
        assertEquals(1, second.imported(), "Dokument-Deduplizierung erfolgt über das Tag; hier nur Link-Schutz");
        verify(workspaceService, times(1)).attachDocument(any());
    }

    private static WorkspaceDocumentLinkEntity link(String workspaceId, String documentId) {
        return new WorkspaceDocumentLinkEntity(null, workspaceId, documentId, null,
                reasoning.common.model.DocumentCategory.OTHER, "OTHER");
    }

    @Test
    void attachEmailDocumentsToWorkspace_findsDocumentsByEmailTag_andLinks() {
        String workspaceId = "11111111-1111-1111-1111-111111111111";
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        when(documentFacade.findDocuments(argThat(f -> f.tag() != null && f.tag().equals("email:msg-1"))))
                .thenReturn(pageOf(List.of(doc(doc1, "Mietvertrag.pdf"), doc(doc2, "Einkommensnachweis.pdf"))));
        when(workspaceService.getWorkspaceDocuments(workspaceId)).thenReturn(List.of());

        service.attachEmailDocumentsToWorkspace("msg-1", workspaceId);

        verify(workspaceService, times(2)).attachDocument(any());
        ArgumentCaptor<DocumentFilter> filterCaptor = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(documentFacade).findDocuments(filterCaptor.capture());
        assertEquals("email:msg-1", filterCaptor.getValue().tag(),
                "Fall-Verknüpfung sucht über den E-Mail-Provenienz-Tag");
    }

    @Test
    void attachEmailDocumentsToWorkspace_noMessageId_isNoOp() {
        service.attachEmailDocumentsToWorkspace(null, "11111111-1111-1111-1111-111111111111");
        service.attachEmailDocumentsToWorkspace("", "11111111-1111-1111-1111-111111111111");
        service.attachEmailDocumentsToWorkspace("msg-1", null);
        verify(documentFacade, never()).findDocuments(any());
        verify(workspaceService, never()).attachDocument(any());
    }

    @Test
    void helperSanitizeAndChecksum() {
        assertEquals("datei.pdf", MailboxAttachmentDocumentIngestionService.sanitizeFileName(
                "ordner\\unterordner/datei.pdf"));
        assertEquals("unknown", MailboxAttachmentDocumentIngestionService.sanitizeFileName(null));
        String checksum = MailboxAttachmentDocumentIngestionService.sha256(PDF_BYTES);
        assertEquals(64, checksum.length(), "SHA-256-Hex");
        assertFalse(checksum.contains("unknown"));
    }
}
