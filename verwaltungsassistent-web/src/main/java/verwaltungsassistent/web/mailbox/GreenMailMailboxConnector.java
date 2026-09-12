package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * IMAP-Connector zur lokalen GreenMail-Demo-Mailbox (Phase 2C.2/2C.3a) —
 * die DEMO-Implementierung der Laufzeit-Grenze (Phase 2C.15): Spring wählt
 * diesen Bean als EINZIGEN {@link MailboxConnector} (API-Typ) in den Profilen
 * demo/playwright; Konsumenten wie {@code MailboxIngestionService} kennen nur
 * das Interface. Weitere Implementierungen koennten denselben API-Typ als
 * profil- oder qualifier-gebundene Bean bereitstellen — ohne die Anwendungs-
 * Schicht zu aendern. Es gibt KEINEN plugin-/SPI-Framework.
 *
 * <p>Reiner Transport: Verbindung herstellen, Nachrichten aus dem INBOX
 * auslesen, auf die transportneutralen {@link IncomingMessage}-Felder
 * reduzieren (Message-ID, In-Reply-To, References, Absender, Betreff, Text,
 * Anhänge, Empfangszeit). Multipart-Nachrichten werden in Text (benannte
 * Teile ausgenommen) und {@link IncomingMessage.Attachment} (benannte
 * Blatt-Teile) zerlegt. Keine Prioritäts-, Fall-, LLM- oder
 * Zuordnungslogik in dieser Klasse. Fetch-only: nichts wird je versendet.</p>
 */
@Component
@Profile({"demo", "playwright"})
public class GreenMailMailboxConnector implements MailboxConnector {

    private static final Logger log = LoggerFactory.getLogger(GreenMailMailboxConnector.class);

    private final String host;
    private final int port;
    private final String user;
    private final String password;

    public GreenMailMailboxConnector(
            @Value("${reasoning.mailbox.imap.host:localhost}") String host,
            @Value("${reasoning.mailbox.imap.port:3143}") int port,
            @Value("${reasoning.mailbox.imap.user:info@verwaltungs-demo.de}") String user,
            @Value("${reasoning.mailbox.imap.password:demo1234}") String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    @Override
    public List<IncomingMessage> fetchNewMessages() {
        List<IncomingMessage> result = new ArrayList<>();
        Properties props = new Properties();
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", String.valueOf(port));
        Session session = Session.getInstance(props);
        try (Store store = session.getStore("imap")) {
            store.connect(host, port, user, password);
            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_ONLY);
                for (Message message : inbox.getMessages()) {
                    result.add(toIncomingMessage(message));
                }
            }
        } catch (Exception e) {
            log.warn("GreenMail-IMAP-Abruf fehlgeschlagen ({}:{}): {}", host, port, e.getMessage());
        }
        return result;
    }

    private IncomingMessage toIncomingMessage(Message message) throws Exception {
        String fromName = null;
        String fromEmail = null;
        if (message.getFrom() != null && message.getFrom().length > 0
                && message.getFrom()[0] instanceof InternetAddress addr) {
            String personal = addr.getPersonal();
            fromName = personal != null && !personal.isBlank() ? personal : null;
            fromEmail = addr.getAddress();
        }
        List<String> recipients = new ArrayList<>();
        if (message.getRecipients(Message.RecipientType.TO) != null) {
            for (jakarta.mail.Address a : message.getRecipients(Message.RecipientType.TO)) {
                if (a instanceof InternetAddress ia && ia.getAddress() != null) {
                    recipients.add(ia.getAddress());
                }
            }
        }
        Instant receivedAt = message.getReceivedDate() != null
                ? message.getReceivedDate().toInstant() : Instant.now();
        ExtractedContent content = extractContent(message);
        return new IncomingMessage(
                header(message, "Message-ID"),
                header(message, "In-Reply-To"),
                header(message, "References"),
                fromName,
                fromEmail,
                recipients,
                message.getSubject(),
                content.body.toString(),
                content.attachments,
                receivedAt);
    }

    private static String header(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        if (values == null || values.length == 0) {
            return null;
        }
        String value = values[0].trim();
        return value.isEmpty() ? null : value;
    }

    /** Zerlegt Multipart-Inhalte: unbenannte Text-Teile → Body, benannte Teile → Anhänge. */
    private static final class ExtractedContent {
        final StringBuilder body = new StringBuilder();
        final List<IncomingMessage.Attachment> attachments = new ArrayList<>();
        int attachmentIndex = 0;
    }

    private static ExtractedContent extractContent(Message message) throws Exception {
        ExtractedContent out = new ExtractedContent();
        collectContent(message, out);
        if (out.body.isEmpty() && out.attachments.isEmpty()) {
            // Fallback wie vor 2C.3a: beliebiger Nicht-Multipart-Inhalt
            Object content = message.getContent();
            if (content != null) {
                out.body.append(content.toString());
            }
        }
        return out;
    }

    private static void collectContent(Part part, ExtractedContent out) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectContent(multipart.getBodyPart(i), out);
            }
            return;
        }
        String fileName = part.getFileName();
        if (fileName != null && !fileName.isBlank()) {
            // Benanntes Blatt-Teil (attachment oder inline) → Anhang; partId ist
            // die laufende Anhang-Nummer innerhalb der Nachricht (deterministisch).
            try (InputStream in = part.getInputStream()) {
                out.attachments.add(new IncomingMessage.Attachment(
                        MimeUtility.decodeText(fileName),
                        part.getContentType(),
                        in.readAllBytes(),
                        out.attachmentIndex++));
            }
            return;
        }
        // Unbenanntes Blatt-Teil → Text-Body (nur Text-Inhalte; sonst ignorieren).
        String contentType = part.getContentType() != null ? part.getContentType().toLowerCase() : "";
        if (contentType.startsWith("text/") || content instanceof String) {
            if (out.body.length() > 0) {
                out.body.append("\n");
            }
            out.body.append(content.toString());
        }
    }
}
