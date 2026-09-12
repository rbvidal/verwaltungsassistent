package verwaltungsassistent.web.analysis.persistence;

import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.AddressedTo;
import verwaltungsassistent.web.analysis.persistence.IncomingEmailEntity.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fokussierter JPA-Smoke-Test der Warteschlangen-Abfragen (Phase 2D.13-Folge):
 * die Listen-Abfragen der E-Mail-Warteschlange müssen real gegen die
 * Datenbank ausführbar sein — die frühere ORDER-BY-CASE-Sortierung mit
 * benannten Parametern war zur Laufzeit fehlerhaft ("No parameter named
 * ':mittelCutoff' …"). Die Prioritäts-Reihung erfolgt jetzt im Service.
 */
@SpringBootTest
@Transactional
class JpaIncomingEmailRepositoryTest {

    @Autowired
    private JpaIncomingEmailRepository repo;

    private IncomingEmailEntity mail(String subject, String recipientEmail,
                                     AddressedTo to, Instant receivedAt) {
        IncomingEmailEntity e = new IncomingEmailEntity(
                UUID.randomUUID(), subject, "Bürger/in", "buerger@example.de",
                "Text", receivedAt, to, recipientEmail);
        return repo.save(e);
    }

    @Test
    void adminQueueAndSearchQueries_executeWithoutParameterErrors() {
        mail("Wohngeldantrag – Unterlagen", "kontakt@verwaltungs-demo.de",
                AddressedTo.GENERAL, Instant.now().minus(10, ChronoUnit.DAYS));
        mail("Ummeldung nach Umzug", "info@verwaltungs-demo.de",
                AddressedTo.GENERAL, Instant.now());

        List<IncomingEmailEntity> queue = repo.findByStatusOrderByReceivedAtDesc(Status.NEW);
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getSubject()).isEqualTo("Ummeldung nach Umzug");

        List<IncomingEmailEntity> found = repo.searchNewByStatus(Status.NEW, "Wohngeld");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSubject()).startsWith("Wohngeldantrag");
    }

    @Test
    void employeeVisibleQueueAndSearch_executeWithoutParameterErrors() {
        mail("An Mitarbeiterin adressiert", "demo01@verwaltungsassistent.local",
                AddressedTo.EMPLOYEE, Instant.now().minus(5, ChronoUnit.DAYS));
        mail("Allgemeine Anfrage unzugewiesen", "kontakt@verwaltungs-demo.de",
                AddressedTo.GENERAL, Instant.now());
        IncomingEmailEntity foreign = mail("Fremde, bereits übernommene Anfrage",
                "info@verwaltungs-demo.de", AddressedTo.GENERAL, Instant.now());
        foreign.setAssignedTo("demo02@verwaltungsassistent.local");
        repo.save(foreign);

        List<IncomingEmailEntity> visible = repo.findVisibleQueueList(
                "demo01@verwaltungsassistent.local", Status.NEW, AddressedTo.GENERAL);
        assertThat(visible).hasSize(2);

        List<IncomingEmailEntity> searched = repo.searchVisibleQueueList(
                "demo01@verwaltungsassistent.local", Status.NEW, AddressedTo.GENERAL, "Mitarbeiterin");
        assertThat(searched).hasSize(1);
    }
}
