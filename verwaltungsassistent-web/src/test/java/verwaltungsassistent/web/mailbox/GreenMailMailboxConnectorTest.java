package verwaltungsassistent.web.mailbox;

import reasoning.mailbox.api.IncomingMessage;
import reasoning.mailbox.api.MailboxConnector;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2C.2: GreenMail als lokale IMAP-Mailbox — der Connector liest die
 * Nachricht über ECHTEN IMAP-Transport und extrahiert Header, Absender,
 * Empfänger, Betreff und Text korrekt.
 */
class GreenMailMailboxConnectorTest {

    private static final int IMAP_PORT = 3144; // eigener Port, damit der Test nie mit der laufenden Demo-Mailbox (3143) kollidiert
    private static final String USER = "info@verwaltungs-demo.de";
    private static final String PASSWORD = "demo1234";

    private GreenMail greenMail;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(new ServerSetup(IMAP_PORT, null, ServerSetup.PROTOCOL_IMAP));
        greenMail.start();
        greenMail.setUser(USER, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        greenMail.stop();
    }

    private void deliverMessage(String messageId, String inReplyTo, String references) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("petra.braun@example.de", "Petra Braun", "UTF-8"));
        message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(USER));
        message.setSubject("Hundesteuer – Anmeldung eines Hundes", "UTF-8");
        message.setText("Betreff: Hundesteuer\n\nGuten Tag, wir haben einen Hund aufgenommen.\n\nMit freundlichen Grüßen\nPetra Braun", "UTF-8");
        if (messageId != null) {
            message.setHeader("Message-ID", messageId);
        }
        if (inReplyTo != null) {
            message.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null) {
            message.setHeader("References", references);
        }
        greenMail.getUserManager().getUser(USER).deliver(message);
    }

    @Test
    void fetch_retrievesMessageAndExtractsHeadersAndBody() throws Exception {
        deliverMessage("msg-test-1", "msg-parent", "msg-parent msg-grandparent");

        GreenMailMailboxConnector connector =
                new GreenMailMailboxConnector("localhost", IMAP_PORT, USER, PASSWORD);
        List<IncomingMessage> messages = connector.fetchNewMessages();

        assertEquals(1, messages.size());
        IncomingMessage m = messages.get(0);
        // GreenMail vergibt beim Zustellen eine eigene Message-ID (Transport-
        // verhalten) — entscheidend sind die Thread-Header und Inhalte.
        assertTrue(m.messageId() != null && !m.messageId().isBlank(),
                "Message-ID ist vorhanden (GreenMail-generiert)");
        assertEquals("msg-parent", m.inReplyTo());
        assertEquals("msg-parent msg-grandparent", m.references());
        assertEquals("Petra Braun", m.senderName());
        assertEquals("petra.braun@example.de", m.senderEmail());
        assertTrue(m.recipients().contains(USER), "Empfänger muss enthalten sein");
        assertEquals("Hundesteuer – Anmeldung eines Hundes", m.subject());
        assertTrue(m.body().contains("Hundesteuer"), "Textkörper muss extrahiert werden");
        assertEquals(List.of("msg-parent", "msg-grandparent"), m.threadReferences());
    }

    @Test
    void fetch_multipartMessage_extractsTextBodyAndAttachments() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("erika.schulze@example.de", "Erika Schulze", "UTF-8"));
        message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(USER));
        message.setSubject("Wohngeldantrag – Unterlagen", "UTF-8");
        Multipart multipart = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Betreff: Wohngeldantrag\n\nDie Unterlagen sind anbei.", "UTF-8");
        multipart.addBodyPart(text);
        for (String filename : new String[]{"Mietvertrag.pdf", "Einkommensnachweis.pdf"}) {
            MimeBodyPart pdf = new MimeBodyPart();
            pdf.setFileName(filename);
            pdf.setContent(new byte[]{37, 80, 68, 70, 45, 49}, "application/pdf");
            pdf.setDisposition(Part.ATTACHMENT);
            multipart.addBodyPart(pdf);
        }
        message.setContent(multipart);
        message.setHeader("Message-ID", "msg-att-1");
        greenMail.getUserManager().getUser(USER).deliver(message);

        GreenMailMailboxConnector connector =
                new GreenMailMailboxConnector("localhost", IMAP_PORT, USER, PASSWORD);
        List<IncomingMessage> messages = connector.fetchNewMessages();

        assertEquals(1, messages.size());
        IncomingMessage m = messages.get(0);
        assertTrue(m.body().contains("Wohngeldantrag"), "Text aus Multipart extrahiert");
        assertEquals(2, m.attachments().size(), "benannte Teile werden als Anhänge übertragen");
        assertEquals("Mietvertrag.pdf", m.attachments().get(0).filename());
        assertEquals("Einkommensnachweis.pdf", m.attachments().get(1).filename());
        assertEquals(0, m.attachments().get(0).partId(), "partId = laufende Anhang-Nummer");
        assertEquals(1, m.attachments().get(1).partId());
        assertTrue(m.attachments().get(0).contentType().toLowerCase().contains("pdf"));
        assertTrue(m.attachments().get(0).bytes().length > 0, "Anhang-Bytes werden übertragen");
    }

    @Test
    void fetch_messageWithoutMessageId_doesNotCrash() throws Exception {
        deliverMessage(null, null, null);

        GreenMailMailboxConnector connector =
                new GreenMailMailboxConnector("localhost", IMAP_PORT, USER, PASSWORD);
        List<IncomingMessage> messages = connector.fetchNewMessages();

        assertEquals(1, messages.size());
        // GreenMail ergänzt eine Message-ID; der Connector darf nie abstürzen.
        // Der synthetische Inhaltsschlüssel der Anwendungs-Schicht bleibt die
        // Absicherung für Transports ohne Message-ID.
        assertTrue(messages.get(0).threadReferences().isEmpty());
    }

    /** Phase 2C.15 — Laufzeit-Grenze: der GreenMail-Connector IST eine
     *  {@link reasoning.mailbox.api.MailboxConnector}-Implementierung
     *  (konkreter GreenMail-Typ leckt nicht in den Vertrag). */
    @Test
    void greenMailConnector_isMailboxConnectorAbstraction() {
        MailboxConnector connector = new GreenMailMailboxConnector(
                "localhost", IMAP_PORT, USER, PASSWORD);
        assertNotNull(connector);
        assertTrue(connector instanceof MailboxConnector,
                "GreenMail-Connector muss die API-MailboxConnector-Implementierung sein");
        assertTrue(connector instanceof verwaltungsassistent.web.mailbox.GreenMailMailboxConnector,
                "konkreter Bean-Typ bleibt fuer die Demo erreichbar");
    }

    /** Phase 2C.15 — die API ist FETCH-ONLY: keine Send-/Reply-/Forward- oder
     *  aehnliche Outbound-Methode existiert im Vertrag. */
    @Test
    void mailboxConnectorApi_isFetchOnly() throws Exception {
        java.util.List<String> methodNames = java.util.Arrays.stream(
                        reasoning.mailbox.api.MailboxConnector.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .toList();
        assertEquals(List.of("fetchNewMessages"), methodNames,
                "MailboxConnector darf ausschliesslich fetchNewMessages() anbieten (inbound-only)");
    }
}