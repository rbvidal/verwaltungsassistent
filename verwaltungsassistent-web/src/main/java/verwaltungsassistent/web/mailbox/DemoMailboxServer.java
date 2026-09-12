package verwaltungsassistent.web.mailbox;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Lokale GreenMail-Demo-Mailbox (Phase 2C.2) — reine Demo-/Test-Infrastruktur.
 * Stellt einen echten IMAP-Server im lokalen Prozess bereit, damit die
 * Verwaltungsassistent-E-Mail-Pipeline über realen IMAP-Transport beliefert wird. Kein
 * Produktions-Postfach, kein POP3, kein SMTP-Versand.
 */
@Component
@Profile({"demo", "playwright"})
public class DemoMailboxServer {

    private static final Logger log = LoggerFactory.getLogger(DemoMailboxServer.class);

    public static final String MAILBOX_USER = "info@verwaltungs-demo.de";
    public static final String MAILBOX_PASSWORD = "demo1234";

    private final int imapPort;
    private GreenMail greenMail;
    private GreenMailUser mailboxUser;

    public DemoMailboxServer(@Value("${reasoning.mailbox.imap.port:3143}") int imapPort) {
        this.imapPort = imapPort;
    }

    @PostConstruct
    public synchronized void start() {
        if (greenMail != null) {
            return;
        }
        greenMail = new GreenMail(new ServerSetup(imapPort, null, ServerSetup.PROTOCOL_IMAP));
        greenMail.start();
        mailboxUser = greenMail.setUser(MAILBOX_USER, MAILBOX_PASSWORD);
        log.info("GreenMail-Demo-Mailbox gestartet (IMAP localhost:{} user {})", imapPort, MAILBOX_USER);
    }

    @PreDestroy
    public synchronized void stop() {
        if (greenMail != null) {
            greenMail.stop();
            greenMail = null;
            mailboxUser = null;
        }
    }

    /** Leert die Mailbox vollständig und legt den Demo-Benutzer neu an. */
    public synchronized void resetMailbox() {
        if (greenMail == null) {
            start();
        }
        greenMail.reset();
        mailboxUser = greenMail.setUser(MAILBOX_USER, MAILBOX_PASSWORD);
        log.info("GreenMail-Demo-Mailbox geleert (IMAP localhost:{})", imapPort);
    }

    /** Stellt eine Nachricht in die Demo-Mailbox (GreenMail-API, kein SMTP-Versand). */
    public synchronized void deliver(MimeMessage message) {
        if (greenMail == null || mailboxUser == null) {
            start();
        }
        try {
            mailboxUser.deliver(message);
        } catch (Exception e) {
            log.warn("Nachricht konnte nicht in die Demo-Mailbox gestellt werden: {}", e.getMessage());
        }
    }
}
