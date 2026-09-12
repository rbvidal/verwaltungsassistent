package verwaltungsassistent.web.planning;

import verwaltungsassistent.web.planning.persistence.CasePlanningEntity;
import verwaltungsassistent.web.planning.persistence.JpaCasePlanningRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistenz-Roundtrip des Planungsstands (echte PostgreSQL-Repository).
 * Ein Eintrag je Fall; Faktoren als JSON; Basis-Fingerabdruck für die
 * Frische-Wiederverwendung.
 */
@SpringBootTest
@ActiveProfiles("dev")
class CasePlanningPersistenceTest {

    @Autowired
    private JpaCasePlanningRepository repository;

    @Test
    void planningRow_roundTrips() {
        UUID caseId = UUID.randomUUID();
        try {
            CasePlanningEntity entity = new CasePlanningEntity(caseId);
            entity.setPriorityScore(72);
            entity.setPriorityClass("SEHR_HOCH");
            entity.setPriorityReason("Frist in 2 Tagen · Bürger wartet seit 5 Tagen");
            entity.setFactorsJson("{\"waitingDays\":5,\"deadlineDays\":2,\"reasons\":[\"Grund 1\"],"
                    + "\"analysisStale\":false,\"missingDocs\":[]}");
            entity.setWorkable(true);
            entity.setBlockedReason(null);
            entity.setCalculatedAt(Instant.now());
            entity.setBasisAnalysisVersion(3);
            entity.setBasisFingerprint("fingerprint-1");
            repository.save(entity);

            Optional<CasePlanningEntity> loaded = repository.findByCaseId(caseId);
            assertTrue(loaded.isPresent());
            assertEquals(72, loaded.get().getPriorityScore());
            assertEquals("SEHR_HOCH", loaded.get().getPriorityClass());
            assertTrue(loaded.get().getFactorsJson().contains("\"waitingDays\":5"));
            assertEquals("fingerprint-1", loaded.get().getBasisFingerprint());
            assertEquals(3, loaded.get().getBasisAnalysisVersion());
            assertTrue(loaded.get().isWorkable());

            // Aktualisierung (Neuberechnung) ersetzt den Zustand desselben Falls.
            CasePlanningEntity updated = loaded.get();
            updated.setPriorityScore(41);
            updated.setPriorityClass("MITTEL");
            repository.save(updated);
            assertEquals(41, repository.findByCaseId(caseId).orElseThrow().getPriorityScore(),
                    "the same row is updated, one entry per case");
        } finally {
            repository.deleteByCaseId(caseId);
        }
        assertTrue(repository.findByCaseId(caseId).isEmpty(), "cleanup removes the planning row");
    }
}
