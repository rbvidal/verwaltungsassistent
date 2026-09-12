package verwaltungsassistent.web.prepare;

import reasoning.mailbox.api.IncomingMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Erzeugt aus den geparsten Dataset-E-Mails die transportneutralen
 * {@link IncomingMessage}-Objekte der BESTEHENDEN Mailbox-Connector-API —
 * kein eigener E-Mail-Typ, kein Transport. Anhänge werden relativ zu
 * {@code <dataset-root>/attachments/} aufgelöst, als Bytes geladen und mit
 * deterministischer partId (1-basierte Position in der Attachments-Liste)
 * versehen; danach durchläuft jede Nachricht unverändert
 * {@code MailboxIngestionService.processOne(...)}.
 */
public final class FilesystemDemoDatasetLoader {

    /** Maximale Anhangsgröße je Datei (Schutz vor versehentlich riesigen Dateien). */
    public static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private FilesystemDemoDatasetLoader() {
    }

    /** Wandelt die geparsten E-Mails in IncomingMessage-Objekte (deterministische Reihenfolge bleibt erhalten). */
    public static List<IncomingMessage> toIncomingMessages(
            List<DemoEmailFileParser.ParsedEmail> emails, Path attachmentsRoot) throws IOException {
        List<IncomingMessage> messages = new ArrayList<>();
        for (DemoEmailFileParser.ParsedEmail e : emails) {
            messages.add(toIncomingMessage(e, attachmentsRoot));
        }
        return List.copyOf(messages);
    }

    public static IncomingMessage toIncomingMessage(
            DemoEmailFileParser.ParsedEmail e, Path attachmentsRoot) throws IOException {
        List<IncomingMessage.Attachment> attachments = new ArrayList<>();
        int partId = 1;
        for (String rel : e.attachmentPaths()) {
            Path file = resolveAttachment(attachmentsRoot, rel);
            long size = Files.size(file);
            if (size > MAX_ATTACHMENT_BYTES) {
                throw new IOException("Anhang zu groß (> " + MAX_ATTACHMENT_BYTES
                        + " Bytes): " + file);
            }
            String fileName = file.getFileName().toString();
            attachments.add(new IncomingMessage.Attachment(
                    fileName,
                    contentTypeOf(fileName),
                    Files.readAllBytes(file),
                    partId++));
        }
        List<String> recipients = e.recipient() == null || e.recipient().isBlank()
                ? List.of()
                : List.of(e.recipient());
        return new IncomingMessage(
                e.messageId(),
                e.inReplyTo(),
                e.references(),
                e.senderName(),
                e.senderEmail(),
                recipients,
                e.subject(),
                e.body(),
                List.copyOf(attachments),
                e.receivedAt());
    }

    /** Löst einen relativen Anhang-Pfad sicher auf (kein Verlassen des Attachment-Roots). */
    static Path resolveAttachment(Path attachmentsRoot, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Leerer Anhang-Pfad im Dataset");
        }
        Path normalized = attachmentsRoot.resolve(relativePath).normalize();
        if (!normalized.startsWith(attachmentsRoot.normalize())) {
            throw new IOException("Anhang-Pfad verlässt das Attachment-Verzeichnis: " + relativePath);
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Anhang-Datei nicht vorhanden: " + relativePath);
        }
        return normalized;
    }

    /** MIME-Typ aus der Dateiendung (identisch zur Anhang-Verarbeitung der bestehenden Pipeline). */
    static String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html;charset=UTF-8";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain;charset=UTF-8";
        }
        return "application/octet-stream";
    }
}
