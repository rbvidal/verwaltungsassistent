package reasoning.workspace.application;

import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import reasoning.workspace.api.StructuredKnowledgeItemEntity;
import reasoning.workspace.infrastructure.persistence.JpaStructuredKnowledgeItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Persistence of generic structured knowledge items (create/load/status/provenance/dates/version). */
@DataJpaTest
@ContextConfiguration(classes = JpaStructuredKnowledgeStoreTest.MinimalConfig.class)
@Import(JpaStructuredKnowledgeStore.class)
class JpaStructuredKnowledgeStoreTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = JpaStructuredKnowledgeItemRepository.class)
    @EntityScan(basePackageClasses = StructuredKnowledgeItemEntity.class)
    static class MinimalConfig {
    }


    @Autowired
    private JpaStructuredKnowledgeStore store;

    @Autowired
    private JpaStructuredKnowledgeItemRepository repository;

    private static final UUID DOC_ID = UUID.randomUUID();

    private StructuredKnowledgeItem thresholdItem(String key, double min, double max,
                                                  StructuredKnowledgeStatus status) {
        return new StructuredKnowledgeItem(
                null,
                StructuredKnowledgeKind.THRESHOLD,
                "PROCUREMENT",
                key,
                "{\"bounds\":[{\"min\":" + min + ",\"max\":" + max
                        + ",\"outcome\":\"Direktauftrag\",\"requirements\":[\"Vergabevermerk\"]}]}",
                status,
                LocalDate.of(2024, 1, 1),
                null,
                DOC_ID,
                1,
                3,
                7,
                "Direktauftrag bis 10.000 Euro mit Vergabevermerk.",
                0.97,
                "test",
                null, null, null);
    }

    @Test
    void savesAndLoadsItemWithFullProvenanceAndDates() {
        StructuredKnowledgeItem saved = store.save(thresholdItem("AV §55 LHO", 0, 10000,
                StructuredKnowledgeStatus.ACTIVE));

        List<StructuredKnowledgeItem> active = store.findActive("THRESHOLD", "PROCUREMENT");
        assertEquals(1, active.size());
        StructuredKnowledgeItem loaded = active.getFirst();
        assertEquals(saved.id(), loaded.id());
        assertEquals("AV §55 LHO", loaded.key(), "regulation identity is data, not code");
        assertEquals(DOC_ID, loaded.sourceDocumentId());
        assertEquals(1, loaded.sourceDocumentVersion());
        assertEquals(3, loaded.sourcePage());
        assertEquals(7, loaded.sourceChunkIndex());
        assertEquals(LocalDate.of(2024, 1, 1), loaded.effectiveFrom());
        assertEquals(StructuredKnowledgeStatus.ACTIVE, loaded.status());
        assertTrue(loaded.sourceExcerpt().contains("10.000"));
    }

    @Test
    void candidateItemsAreNotVisibleToActiveLookup() {
        store.save(thresholdItem("AV §55 LHO", 0, 10000, StructuredKnowledgeStatus.CANDIDATE));
        assertTrue(store.findActive("THRESHOLD", "PROCUREMENT").isEmpty());
    }

    @Test
    void markStatusPromotesCandidateToActive() {
        StructuredKnowledgeItem saved = store.save(thresholdItem("AV §55 LHO", 0, 10000,
                StructuredKnowledgeStatus.CANDIDATE));
        store.markStatus(saved.id(), StructuredKnowledgeStatus.ACTIVE);
        assertEquals(1, store.findActive("THRESHOLD", "PROCUREMENT").size());
    }

    @Test
    void supersedeBySourceMarksOnlyTheReplacedVersion() {
        store.save(thresholdItem("AV §55 LHO", 0, 10000, StructuredKnowledgeStatus.ACTIVE));
        StructuredKnowledgeItem v2 = new StructuredKnowledgeItem(
                null, StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "AV §55 LHO",
                "{\"bounds\":[]}", StructuredKnowledgeStatus.ACTIVE,
                LocalDate.of(2025, 1, 1), null, DOC_ID, 2, 1, 1,
                "Neue Wertgrenzen.", 0.98, "test", null, null, null);
        store.save(v2);

        int superseded = store.supersedeBySource(DOC_ID, 1, "test");

        assertEquals(1, superseded);
        assertEquals(1, store.findBySource(DOC_ID, 1).stream()
                .filter(i -> i.status() == StructuredKnowledgeStatus.SUPERSEDED).count());
        assertEquals(1, store.findActive("THRESHOLD", "PROCUREMENT").size(),
                "v2 item stays ACTIVE; only v1 knowledge is superseded");
    }

    @Test
    void domainAndKindAreStoredAsData() {
        store.save(thresholdItem("AV §55 LHO", 0, 10000, StructuredKnowledgeStatus.ACTIVE));
        assertTrue(store.findActive("THRESHOLD", "PROCUREMENT").size() == 1);
        assertTrue(store.findActive("TABLE", "PROCUREMENT").isEmpty());
        assertTrue(store.findActive("THRESHOLD", "ENVIRONMENT").isEmpty());
    }
}
