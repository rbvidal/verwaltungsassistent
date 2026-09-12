package reasoning.mailbox.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Transportneutral normalisierte eingehende Nachricht — die einzige Brücke
 * zwischen einem Mailbox-Transport und der Anwendungs-E-Mail-Pipeline.
 * Enthält NUR die Felder, die die Pipeline braucht — keine Priorität, keine
 * Fall-Logik, keine KI. Provider-spezifische Details (IMAP, GreenMail,
 * Jakarta Mail) erscheinen hier bewusst nicht.
 *
 * <p>Anhänge werden als {@link Attachment} mitgeführt: Dateiname, Inhaltstyp,
 * Byte-Inhalt und die deterministische Teil-Position ({@code partId}) als
 * Identität innerhalb der Nachricht. Die Entscheidung, ob ein Anhang als
 * Dokument übernommen wird, trifft die Anwendung (Whitelist, Checksumme,
 * Provenienz), nicht der Transport.</p>
 */
public record IncomingMessage(
        String messageId,
        String inReplyTo,
        String references,
        String senderName,
        String senderEmail,
        List<String> recipients,
        String subject,
        String body,
        List<Attachment> attachments,
        Instant receivedAt) {

    /** Anhang einer Nachricht: Transport-Ausschnitt ohne Pipeline-Semantik. */
    public record Attachment(String filename, String contentType, byte[] bytes, int partId) {}

    /** Nachrichten ohne Anhänge (bestehende Aufrufer, Tests). */
    public IncomingMessage(String messageId, String inReplyTo, String references,
                           String senderName, String senderEmail, List<String> recipients,
                           String subject, String body, Instant receivedAt) {
        this(messageId, inReplyTo, references, senderName, senderEmail, recipients,
                subject, body, List.of(), receivedAt);
    }

    /** Thread-Referenzen: In-Reply-To plus alle Einzel-IDs aus References (dedupliziert). */
    public List<String> threadReferences() {
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            refs.add(inReplyTo.trim());
        }
        if (references != null && !references.isBlank()) {
            for (String part : references.split("\\s+")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    refs.add(t);
                }
            }
        }
        return new ArrayList<>(refs);
    }
}
