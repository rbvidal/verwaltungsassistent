package verwaltungsassistent.web.mailbox;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import verwaltungsassistent.web.service.CaseIdService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Deterministische Demo-Befüllung der GreenMail-Mailbox (Phase 2C.2/2C.3a).
 * Liefert eine kleine, feste Auswahl realistischer Nachrichten, die die
 * Phase-2C.1-Szenarien über den ECHTEN IMAP-Transport abbilden:
 *
 * <ul>
 *   <li>Neues Anliegen (neuer Fall, keine Fall-Verknüpfung)</li>
 *   <li>Folge-E-Mail mit In-Reply-To/References → Thread-Zugehörigkeit</li>
 *   <li>Unsichere Zuordnung (Wohngeld-Nachfrage, Bestätigung nötig)</li>
 *   <li>Bekannte Person → bestehender (abgeschlossener) Vorgang</li>
 *   <li>Weiteres neues Anliegen</li>
 *   <li>Wohngeldantrag mit zwei PDF-Anhängen (Mietvertrag, Einkommensnachweis)</li>
 * </ul>
 *
 * <p>Beim Reset wird die Mailbox geleert und mit denselben Message-IDs neu
 * befüllt — ein zweiter Reset akkumuliert nichts (Deduplizierung über
 * Message-ID in der Anwendungs-Schicht).</p>
 */
@Component
@Profile({"demo", "playwright"})
public class DemoMailboxSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoMailboxSeeder.class);

    private final DemoMailboxServer mailboxServer;

    public DemoMailboxSeeder(DemoMailboxServer mailboxServer) {
        this.mailboxServer = mailboxServer;
    }

    private record MailboxSeedMessage(String messageId, String inReplyTo, String references,
                                      String senderName, String senderEmail,
                                      String subject, String body, List<SeedAttachment> attachments) {
        private MailboxSeedMessage(String messageId, String inReplyTo, String references,
                                   String senderName, String senderEmail,
                                   String subject, String body) {
            this(messageId, inReplyTo, references, senderName, senderEmail, subject, body, List.of());
        }
    }

    private record SeedAttachment(String filename, byte[] bytes) {
        String contentType() {
            return filename().toLowerCase().endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
        }
    }

    private static final List<String> MIETVERTRAG_LINES = List.of(
            "Mietvertrag (Auszug)",
            "",
            "Mieterin: Erika Schulze",
            "Wohnung: Hauptstraße 12, 14542 Werder (Havel)",
            "Vermieterin: Wohnungsbaugesellschaft Werder mbH",
            "",
            "Nettokaltmiete: 480,00 EUR monatlich",
            "Vorauszahlung Nebenkosten: 150,00 EUR monatlich",
            "Kaution: 960,00 EUR",
            "",
            "Mietbeginn: 1. Februar 2024",
            "Vertragslaufzeit: unbefristet");

    private static final List<String> EINKOMMENS_NACHWEIS_LINES = List.of(
            "Arbeitgeberbescheinigung (Einkommensnachweis)",
            "",
            "Hiermit wird bestätigt, dass Frau Erika Schulze",
            "bei der Bäckerei Sonnenberg GmbH & Co. KG",
            "in 14542 Werder (Havel) als Verkäuferin beschäftigt ist.",
            "",
            "Monatliches Bruttoeinkommen: 2.180,00 EUR",
            "Monatliches Nettoeinkommen: 1.640,00 EUR",
            "",
            "Beschäftigt seit: 1. April 2023",
            "Dauer des Arbeitsverhältnisses: unbefristet");

    private static final List<String> MIETBESCHEINIGUNG_LINES = List.of(
            "Mietbescheinigung",
            "",
            "Hiermit wird bescheinigt, dass Frau Erika Schulze",
            "in der Wohnung Hauptstraße 12, 14542 Werder (Havel)",
            "als Hauptmieterin wohnt.",
            "",
            "Wohnfläche: 58 m²",
            "Nettokaltmiete: 480,00 EUR monatlich",
            "",
            "Vermieterin: Wohnungsbaugesellschaft Werder mbH",
            "Ausgestellt am: 1. September 2026");

    private static final List<MailboxSeedMessage> MAILBOX_MESSAGES = buildMessages();

    private static List<MailboxSeedMessage> buildMessages() {
        List<MailboxSeedMessage> messages = new ArrayList<>();
        // Szenario A — neues Anliegen (Gewerbe)
        messages.add(new MailboxSeedMessage("msg-mailbox-gewerbe-01", null, null,
                "Deniz Yılmaz", "deniz.yilmaz@example.de",
                "Anmeldung Gewerbe – Imbiss",
                "Betreff: Anmeldung Gewerbe\n\nGuten Tag,\n\nich möchte zum 1. Oktober einen Imbiss anmelden. "
                        + "Welche Unterlagen benötige ich für die Gewerbeanmeldung?\n\nMit freundlichen Grüßen\nDeniz Yılmaz"));
        // Szenario C — Folge-E-Mail mit Thread-Referenzen (Müllsäcke-Thread)
        messages.add(new MailboxSeedMessage("msg-mailbox-muell-01", "msg-muell-2026-09-01",
                "msg-muell-2026-08-29 msg-muell-2026-09-01",
                "Familie Nowak", "nowak.familie@example.de",
                "Müllsäcke an der Sammelstelle – erneute Abholung",
                "Betreff: Müllsäcke erneut\n\nSehr geehrte Damen und Herren,\n\nan der Sammelstelle in der "
                        + "Lindenstraße liegen erneut Müllsäcke. Bitte veranlassen Sie die Abholung.\n\n"
                        + "Mit freundlichen Grüßen\nFamilie Nowak"));
        // Szenario D — unsichere Zuordnung (Wohngeld-Nachfrage)
        messages.add(new MailboxSeedMessage("msg-mailbox-wohngeld-01", null, null,
                "Erika Schulze", "erika.schulze@example.de",
                "Wohngeld – Nachfrage zu meinem Antrag",
                "Betreff: Wohngeld Nachfrage\n\nGuten Tag,\n\nich habe vor einigen Wochen einen Wohngeldantrag "
                        + "gestellt und frage nach dem aktuellen Stand. Welche Unterlagen werden noch benötigt?\n\n"
                        + "Mit freundlichen Grüßen\nErika Schulze"));
        // Szenario E — bekannte Person, bestehender (abgeschlossener) Vorgang
        messages.add(new MailboxSeedMessage("msg-mailbox-laterne-01", null, null,
                "Horst Günther", "horst.guenther@example.de",
                "Straßenlaterne – erneute Störung",
                "Betreff: Straßenlaterne erneut defekt\n\nGuten Tag,\n\ndie Straßenlaterne in der Lehnitzer "
                        + "Straße fällt erneut aus. Bitte prüfen Sie die Reparatur.\n\nMit freundlichen Grüßen\n"
                        + "Horst Günther"));
        // Weiteres neues Anliegen (Bau)
        messages.add(new MailboxSeedMessage("msg-mailbox-bau-01", null, null,
                "Bernd Vogel", "bernd.vogel@example.de",
                "Baugenehmigung – Carport Nachfrage",
                "Betreff: Baugenehmigung Carport\n\nGuten Tag,\n\nich möchte auf meinem Grundstück einen "
                        + "Carport bauen. Ist dafür eine Baugenehmigung erforderlich und welche Unterlagen "
                        + "brauche ich?\n\nMit freundlichen Grüßen\nBernd Vogel"));
        // Phase 2C.3a — Wohngeldantrag mit zwei PDF-Anhängen (Antragsunterlagen)
        messages.add(new MailboxSeedMessage("msg-mailbox-wohngeld-antrag-01", null, null,
                "Erika Schulze", "erika.schulze@example.de",
                "Wohngeldantrag – Unterlagen",
                "Betreff: Wohngeldantrag\n\nGuten Tag,\n\nanbei übersende ich die angeforderten Unterlagen zu "
                        + "meinem Wohngeldantrag: den Mietvertrag und den Einkommensnachweis.\n\nMit freundlichen "
                        + "Grüßen\nErika Schulze",
                attachmentPdfs()));
        // Phase 2C.3b — Folge-E-Mail mit expliziter Vorgangsnummer im Betreff.
        // Die Nummer ist deterministisch aus dem Betreff der Auslöser-E-Mail
        // (Wohngeld-Nachfrage) berechnet — transport-stabil und über Resets
        // gleich. Der Anhang belegt die Zuordnung zu einem BESTEHENDEN Vorgang.
        String wohngeldCode = CaseIdService.deterministicCaseCode("Wohngeld – Nachfrage zu meinem Antrag");
        messages.add(new MailboxSeedMessage("msg-mailbox-wohngeld-folge-01", "msg-mailbox-wohngeld-01",
                "msg-mailbox-wohngeld-01",
                "Erika Schulze", "erika.schulze@example.de",
                "AW: [" + wohngeldCode + "] Wohngeld – Nachfrage zu meinem Antrag",
                "Betreff: Wohngeld Nachfrage\n\nGuten Tag,\n\neine Frage habe ich noch: Muss ich die "
                        + "Unterlagen persönlich im Bürgeramt einreichen?\n\nMit freundlichen Grüßen\nErika Schulze",
                List.of(new SeedAttachment("Mietbescheinigung.pdf", pdfFromLinesQuietly(MIETBESCHEINIGUNG_LINES)))));
        // Phase 2C.3b — syntaktisch gültige, aber nicht existierende
        // Vorgangsnummer → Prüfung erforderlich, KEIN neuer Vorgang.
        messages.add(new MailboxSeedMessage("msg-mailbox-invalid-id-01", null, null,
                "Claudia Keller", "claudia.keller@example.de",
                "AW: [WS-AB12CD34] Nachfrage zur Gewerbeanmeldung",
                "Betreff: Gewerbeanmeldung\n\nGuten Tag,\n\nich habe vor einiger Zeit eine Gewerbeanmeldung "
                        + "eingereicht und frage nach dem Bearbeitungsstand.\n\nMit freundlichen Grüßen\nClaudia Keller"));
        // Phase 2C.3b — abschließende Dank-Nachricht: gleicher Vorgang (Nummer
        // im Betreff), keine Antwort erforderlich (requiresResponse=false).
        messages.add(new MailboxSeedMessage("msg-mailbox-danke-01", "msg-mailbox-wohngeld-01",
                "msg-mailbox-wohngeld-01",
                "Erika Schulze", "erika.schulze@example.de",
                "AW: [" + wohngeldCode + "] Danke – alles erledigt",
                "Betreff: Wohngeld Nachfrage\n\nGuten Tag,\n\nvielen Dank für die Information, das war schon "
                        + "alles. Auf Wiedersehen!\n\nErika Schulze"));
        return List.copyOf(messages);
    }

    /** Deterministische PDF-Anhänge (PDFBox, gleicher Inhalt bei jedem Reset). */
    private static List<SeedAttachment> attachmentPdfs() {
        try {
            return List.of(
                    new SeedAttachment("Mietvertrag.pdf", pdfFromLines(MIETVERTRAG_LINES)),
                    new SeedAttachment("Einkommensnachweis.pdf", pdfFromLines(EINKOMMENS_NACHWEIS_LINES)));
        } catch (IOException e) {
            log.warn("Demo-PDF-Anhänge konnten nicht erzeugt werden: {}", e.getMessage());
            return List.of();
        }
    }

    /** Einzelner PDF-Anhang (Fallback: leeres PDF bei Fehler — Nachricht bleibt zustellbar). */
    private static byte[] pdfFromLinesQuietly(List<String> lines) {
        try {
            return pdfFromLines(lines);
        } catch (IOException e) {
            log.warn("Demo-PDF-Anhang konnte nicht erzeugt werden: {}", e.getMessage());
            return new byte[]{37, 80, 68, 70}; // "%PDF" (Anhang bleibt sichtbar)
        }
    }

    private static byte[] pdfFromLines(List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 11);
                cs.newLineAtOffset(56, 780);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Leert die Mailbox und stellt die deterministischen Demo-Nachrichten
     * erneut hinein (gleiche Message-IDs → idempotente Folge-Abrufe).
     */
    public synchronized void reseed() {
        mailboxServer.resetMailbox();
        Session session = Session.getInstance(new Properties());
        for (MailboxSeedMessage m : MAILBOX_MESSAGES) {
            try {
                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(m.senderEmail(), m.senderName(), "UTF-8"));
                message.setRecipient(MimeMessage.RecipientType.TO,
                        new InternetAddress(DemoMailboxServer.MAILBOX_USER));
                message.setSubject(m.subject(), "UTF-8");
                if (m.attachments().isEmpty()) {
                    message.setText(m.body(), "UTF-8");
                } else {
                    Multipart multipart = new MimeMultipart();
                    MimeBodyPart text = new MimeBodyPart();
                    text.setText(m.body(), "UTF-8");
                    multipart.addBodyPart(text);
                    for (SeedAttachment a : m.attachments()) {
                        MimeBodyPart part = new MimeBodyPart();
                        part.setFileName(a.filename());
                        part.setContent(a.bytes(), a.contentType());
                        part.setDisposition(Part.ATTACHMENT);
                        multipart.addBodyPart(part);
                    }
                    message.setContent(multipart);
                }
                message.setSentDate(Date.from(Instant.now().minusSeconds(3600)));
                if (m.messageId() != null) {
                    message.setHeader("Message-ID", m.messageId());
                }
                if (m.inReplyTo() != null) {
                    message.setHeader("In-Reply-To", m.inReplyTo());
                }
                if (m.references() != null) {
                    message.setHeader("References", m.references());
                }
                mailboxServer.deliver(message);
            } catch (Exception e) {
                log.warn("Demo-Mailbox-Nachricht '{}' konnte nicht erzeugt werden: {}", m.subject(), e.getMessage());
            }
        }
        log.info("GreenMail-Demo-Mailbox neu befüllt: {} Nachrichten", MAILBOX_MESSAGES.size());
    }
}
