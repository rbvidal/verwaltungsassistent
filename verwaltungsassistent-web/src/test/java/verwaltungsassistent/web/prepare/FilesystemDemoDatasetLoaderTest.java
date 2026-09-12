package verwaltungsassistent.web.prepare;

import reasoning.mailbox.api.IncomingMessage;
import verwaltungsassistent.web.prepare.DemoEmailFileParser.ParsedEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 2D.15 — fokussierte Unit-Tests für den IncomingMessage-Bau aus dem Dataset. */
class FilesystemDemoDatasetLoaderTest {

    @TempDir
    Path tmp;

    private ParsedEmail emailWith(List<String> attachments) {
        return new ParsedEmail(
                "001.txt", "<demo-email-001@verwaltungs-demo.de>", null, null,
                "Bernd Becker", "bernd.becker@example.de", "info@verwaltungs-demo.de",
                "Baugenehmigung – Carport Nachfrage",
                Instant.parse("2026-08-06T06:42:00Z"),
                "Sehr geehrte Damen und Herren,\n\neine Nachfrage …",
                attachments);
    }

    @Test
    void buildsTransportNeutralIncomingMessageWithDeterministicPartIds() throws Exception {
        Path attachments = Files.createDirectories(tmp.resolve("attachments"));
        Files.writeString(attachments.resolve("mietvertrag.pdf"), "%PDF-1.4-test");
        Files.writeString(attachments.resolve("lageplan.txt"), "Lageplan");

        IncomingMessage message = FilesystemDemoDatasetLoader.toIncomingMessage(
                emailWith(List.of("mietvertrag.pdf", "lageplan.txt")), attachments);

        assertThat(message.messageId()).isEqualTo("<demo-email-001@verwaltungs-demo.de>");
        assertThat(message.senderEmail()).isEqualTo("bernd.becker@example.de");
        assertThat(message.recipients()).containsExactly("info@verwaltungs-demo.de");
        assertThat(message.body()).contains("Sehr geehrte Damen und Herren");
        assertThat(message.receivedAt()).isEqualTo(Instant.parse("2026-08-06T06:42:00Z"));
        assertThat(message.attachments()).hasSize(2);
        assertThat(message.attachments().get(0).partId()).isEqualTo(1);
        assertThat(message.attachments().get(0).contentType()).isEqualTo("application/pdf");
        assertThat(message.attachments().get(1).partId()).isEqualTo(2);
        assertThat(new String(message.attachments().get(0).bytes())).isEqualTo("%PDF-1.4-test");
    }

    @Test
    void missingAttachmentFile_rejects() throws Exception {
        Path attachments = Files.createDirectories(tmp.resolve("attachments"));

        assertThatThrownBy(() -> FilesystemDemoDatasetLoader.toIncomingMessage(
                emailWith(List.of("fehlt.pdf")), attachments))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("nicht vorhanden");
    }

    @Test
    void pathTraversalOutsideAttachmentRoot_isRejected() throws Exception {
        Path attachments = Files.createDirectories(tmp.resolve("attachments"));
        Path secret = tmp.resolve("secret.pdf");
        Files.writeString(secret, "geheim");

        assertThatThrownBy(() -> FilesystemDemoDatasetLoader.toIncomingMessage(
                emailWith(List.of("../secret.pdf")), attachments))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("verlässt");
    }

    @Test
    void contentTypeIsDerivedFromFileName() {
        assertThat(FilesystemDemoDatasetLoader.contentTypeOf("a.PDF")).isEqualTo("application/pdf");
        assertThat(FilesystemDemoDatasetLoader.contentTypeOf("b.docx")).contains("wordprocessingml");
        assertThat(FilesystemDemoDatasetLoader.contentTypeOf("c.txt")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(FilesystemDemoDatasetLoader.contentTypeOf("d.bin")).isEqualTo("application/octet-stream");
    }
}
