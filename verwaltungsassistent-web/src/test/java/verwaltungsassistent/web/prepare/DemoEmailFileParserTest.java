package verwaltungsassistent.web.prepare;

import verwaltungsassistent.web.prepare.DemoEmailFileParser.ParsedEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 2D.15 — fokussierte Unit-Tests für den Dateisystem-E-Mail-Parser. */
class DemoEmailFileParserTest {

    @TempDir
    Path tmp;

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void parsesFullMessageWithMarkerAttachmentsAndThreadHeaders() throws Exception {
        Path file = write("001.txt", """
                From: Bernd Becker <bernd.becker@example.de>
                To: info@verwaltungs-demo.de
                Subject: Baugenehmigung – Carport Nachfrage
                Date: 2026-08-06T08:42:00+02:00
                Message-ID: <demo-email-001@verwaltungs-demo.de>
                In-Reply-To: <demo-email-000@verwaltungs-demo.de>
                References: <demo-email-000@verwaltungs-demo.de>
                Attachments: wohngeld/mietvertrag.pdf, baugenehmigung/lageplan.pdf

                --- body ab hier ---
                Sehr geehrte Damen und Herren,

                eine Nachfrage …
                """);

        ParsedEmail email = DemoEmailFileParser.parseFile(file);

        assertThat(email.senderName()).isEqualTo("Bernd Becker");
        assertThat(email.senderEmail()).isEqualTo("bernd.becker@example.de");
        assertThat(email.recipient()).isEqualTo("info@verwaltungs-demo.de");
        assertThat(email.subject()).isEqualTo("Baugenehmigung – Carport Nachfrage");
        assertThat(email.messageId()).isEqualTo("<demo-email-001@verwaltungs-demo.de>");
        assertThat(email.inReplyTo()).isEqualTo("<demo-email-000@verwaltungs-demo.de>");
        assertThat(email.references()).isEqualTo("<demo-email-000@verwaltungs-demo.de>");
        assertThat(email.receivedAt()).isEqualTo(Instant.parse("2026-08-06T06:42:00Z"));
        assertThat(email.attachmentPaths())
                .containsExactly("wohngeld/mietvertrag.pdf", "baugenehmigung/lageplan.pdf");
        assertThat(email.body()).contains("Sehr geehrte Damen und Herren").contains("eine Nachfrage");
    }

    @Test
    void fromWithoutAngleBrackets_usesPlainEmail() throws Exception {
        Path file = write("plain.txt", """
                From: bernd.becker@example.de
                To: info@verwaltungs-demo.de
                Subject: Betreff
                Date: 2026-08-06T08:42:00+02:00
                Message-ID: <x@y>

                --- body ab hier ---
                Hallo
                """);

        ParsedEmail email = DemoEmailFileParser.parseFile(file);

        assertThat(email.senderEmail()).isEqualTo("bernd.becker@example.de");
        assertThat(email.senderName()).isNull();
        assertThat(email.body()).isEqualTo("Hallo");
    }

    @Test
    void emptyBodyIsSupported() throws Exception {
        Path file = write("empty.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Ohne Text
                Date: 2026-08-06T08:42:00+02:00
                Message-ID: <empty@example.de>

                --- body ab hier ---
                """);

        assertThat(DemoEmailFileParser.parseFile(file).body()).isEmpty();
    }

    @Test
    void missingBodyMarker_usesFirstBlankLine() throws Exception {
        Path file = write("nofallback.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Ohne Marker
                Date: 2026-08-06T08:42:00+02:00
                Message-ID: <nomarker@example.de>

                Text nach der Leerzeile
                """);

        assertThat(DemoEmailFileParser.parseFile(file).body()).isEqualTo("Text nach der Leerzeile");
    }

    @Test
    void missingMandatoryField_rejects() throws Exception {
        Path file = write("noid.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Keine Message-ID
                Date: 2026-08-06T08:42:00+02:00

                --- body ab hier ---
                Text
                """);

        assertThatThrownBy(() -> DemoEmailFileParser.parseFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Message-ID");
    }

    @Test
    void invalidFrom_rejects() throws Exception {
        Path file = write("badfrom.txt", """
                From: Nur Ein Name
                To: info@verwaltungs-demo.de
                Subject: X
                Date: 2026-08-06T08:42:00+02:00
                Message-ID: <bad@x>

                --- body ab hier ---
                Text
                """);

        assertThatThrownBy(() -> DemoEmailFileParser.parseFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absender");
    }

    @Test
    void parseDirectory_ordersByDateThenFilename() throws Exception {
        write("b.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Spaeter
                Date: 2026-08-07T08:00:00+02:00
                Message-ID: <late@x>

                --- body ab hier ---
                """);
        write("a.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Frueher
                Date: 2026-08-05T08:00:00+02:00
                Message-ID: <early@x>

                --- body ab hier ---
                """);
        write("c.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Gleicher Tag – a vor b
                Date: 2026-08-06T08:00:00+02:00
                Message-ID: <c@x>

                --- body ab hier ---
                """);

        List<ParsedEmail> emails = DemoEmailFileParser.parseDirectory(tmp);

        assertThat(emails).extracting(ParsedEmail::fileName)
                .containsExactly("a.txt", "c.txt", "b.txt");
    }

    @Test
    void parseDirectory_rejectsDuplicateMessageIds() throws Exception {
        write("a.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: X
                Date: 2026-08-05T08:00:00+02:00
                Message-ID: <dup@x>

                --- body ab hier ---
                """);
        write("b.txt", """
                From: A <a@example.de>
                To: info@verwaltungs-demo.de
                Subject: Y
                Date: 2026-08-06T08:00:00+02:00
                Message-ID: <dup@x>

                --- body ab hier ---
                """);

        assertThatThrownBy(() -> DemoEmailFileParser.parseDirectory(tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Doppelte Message-IDs");
    }
}
