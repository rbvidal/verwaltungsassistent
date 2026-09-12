package reasoning.ai.unit.knowledge;

import reasoning.ai.api.StructuredKnowledgeStore;
import reasoning.ai.knowledge.KnowledgeRegistry;
import reasoning.ai.model.StructuredKnowledgeItem;
import reasoning.ai.model.StructuredKnowledgeKind;
import reasoning.ai.model.StructuredKnowledgeStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic registry lookup by kind/domain/key/as-of. The engine must not know
 * the regulation — "AV §55 LHO" is supplied as data by the item.
 */
class KnowledgeRegistryGenericLookupTest {

    private static final UUID DOC_ID = UUID.randomUUID();

    private StructuredKnowledgeItem threshold(String key, LocalDate effectiveFrom, LocalDate effectiveUntil) {
        return new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", key,
                "{\"bounds\":[{\"min\":0,\"max\":10000,\"outcome\":\"Direktauftrag\"}]}",
                StructuredKnowledgeStatus.ACTIVE, effectiveFrom, effectiveUntil,
                DOC_ID, 1, 1, 1, "Direktauftrag bis 10.000 Euro.", 0.95, "test",
                null, null, null);
    }

    private KnowledgeRegistry registry(List<StructuredKnowledgeItem> active) {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        registry.setStructuredKnowledgeStore(new StubStore(active));
        registry.setExtractionEnabled(true);
        return registry;
    }

    @Test
    void lookupReturnsMatchingItemByKindDomainAndKey() {
        KnowledgeRegistry registry = registry(List.of(
                threshold("AV §55 LHO", LocalDate.of(2024, 1, 1), null),
                threshold("UVgO", LocalDate.of(2024, 1, 1), null)));
        Optional<StructuredKnowledgeItem> found =
                registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO", LocalDate.of(2026, 1, 1));
        assertTrue(found.isPresent());
        assertEquals("AV §55 LHO", found.get().key());
    }

    @Test
    void lookupSelectsHighestEffectiveFromApplicableAtAsOf() {
        KnowledgeRegistry registry = registry(List.of(
                threshold("AV §55 LHO", LocalDate.of(2024, 1, 1), null),
                threshold("AV §55 LHO", LocalDate.of(2025, 1, 1), null)));
        Optional<StructuredKnowledgeItem> found =
                registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO", LocalDate.of(2026, 1, 1));
        assertTrue(found.isPresent());
        assertEquals(LocalDate.of(2025, 1, 1), found.get().effectiveFrom());
    }

    @Test
    void lookupRespectsEffectiveUntilAndHistoricalAsOf() {
        KnowledgeRegistry registry = registry(List.of(
                threshold("AV §55 LHO", LocalDate.of(2018, 1, 1), LocalDate.of(2019, 12, 31))));
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO",
                LocalDate.of(2019, 6, 1)).isPresent(), "valid in 2019");
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO",
                LocalDate.of(2026, 1, 1)).isEmpty(), "expired for today");
    }

    @Test
    void lookupIgnoresFutureItems() {
        KnowledgeRegistry registry = registry(List.of(
                threshold("AV §55 LHO", LocalDate.of(2030, 1, 1), null)));
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO",
                LocalDate.of(2026, 1, 1)).isEmpty());
    }

    @Test
    void lookupReturnsEmptyWhenExtractionIsDisabled() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        registry.setStructuredKnowledgeStore(new StubStore(List.of(
                threshold("AV §55 LHO", LocalDate.of(2024, 1, 1), null))));
        registry.setExtractionEnabled(false);
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO",
                LocalDate.of(2026, 1, 1)).isEmpty());
        assertTrue(registry.findActive("THRESHOLD", "PROCUREMENT").isEmpty());
    }

    @Test
    void lookupDefaultsAsOfToToday() {
        KnowledgeRegistry registry = registry(List.of(
                threshold("AV §55 LHO", LocalDate.of(2024, 1, 1), null)));
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO", null).isPresent());
    }

    @Test
    void onlyActiveItemsSurviveDefenseInDepthFilter() {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        StructuredKnowledgeItem active = threshold("AV §55 LHO", LocalDate.of(2024, 1, 1), null);
        StructuredKnowledgeItem candidate = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "UVgO",
                "{\"bounds\":[]}", StructuredKnowledgeStatus.CANDIDATE,
                LocalDate.of(2024, 1, 1), null, DOC_ID, 1, 1, 1,
                "Kandidat.", 0.8, "test", null, null, null);
        StructuredKnowledgeItem rejected = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "BerlAVG",
                "{\"bounds\":[]}", StructuredKnowledgeStatus.REJECTED,
                LocalDate.of(2024, 1, 1), null, DOC_ID, 1, 1, 1,
                "Abgelehnt.", 0.8, "test", null, null, null);
        StructuredKnowledgeItem superseded = new StructuredKnowledgeItem(
                UUID.randomUUID(), StructuredKnowledgeKind.THRESHOLD, "PROCUREMENT", "AV §55 LHO",
                "{\"bounds\":[]}", StructuredKnowledgeStatus.SUPERSEDED,
                LocalDate.of(2023, 1, 1), null, DOC_ID, 1, 1, 1,
                "Ersetzt.", 0.8, "test", null, null, null);
        // The store stub deliberately returns every item of the kind/domain —
        // the registry itself must gate on ACTIVE (defense in depth).
        registry.setStructuredKnowledgeStore(new MixedStore(List.of(active, candidate, rejected, superseded)));
        registry.setExtractionEnabled(true);

        List<StructuredKnowledgeItem> visible = registry.findActive("THRESHOLD", "PROCUREMENT");
        assertEquals(1, visible.size(), "only ACTIVE items may reach the deterministic engine");
        assertEquals(StructuredKnowledgeStatus.ACTIVE, visible.getFirst().status());
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "UVgO",
                LocalDate.of(2026, 1, 1)).isEmpty(), "CANDIDATE must never be lookable");
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "BerlAVG",
                LocalDate.of(2026, 1, 1)).isEmpty(), "REJECTED must never be lookable");
        assertTrue(registry.lookupActive("THRESHOLD", "PROCUREMENT", "AV §55 LHO",
                LocalDate.of(2026, 1, 1)).isPresent(), "ACTIVE remains lookable");
    }

    /** Store that does NOT filter by status — the registry's own gate must protect the engine. */
    private static final class MixedStore implements StructuredKnowledgeStore {
        private final List<StructuredKnowledgeItem> items;

        MixedStore(List<StructuredKnowledgeItem> items) {
            this.items = items;
        }

        @Override
        public List<StructuredKnowledgeItem> findActive(String kind, String domain) {
            return items.stream()
                    .filter(i -> i.kind().name().equals(kind) && i.domain().equals(domain))
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findBySource(UUID documentId, int version) {
            return items.stream()
                    .filter(i -> documentId.equals(i.sourceDocumentId()) && i.sourceDocumentVersion() == version)
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findByStatus(StructuredKnowledgeStatus status) {
            return items.stream().filter(i -> i.status() == status).toList();
        }

        @Override
        public Optional<StructuredKnowledgeItem> findById(UUID id) {
            return items.stream().filter(i -> i.id().equals(id)).findFirst();
        }

        @Override
        public StructuredKnowledgeItem save(StructuredKnowledgeItem item) {
            return item;
        }

        @Override
        public List<StructuredKnowledgeItem> saveAll(List<StructuredKnowledgeItem> items) {
            return items;
        }

        @Override
        public void markStatus(UUID id, StructuredKnowledgeStatus status) {
        }

        @Override
        public int supersedeBySource(UUID documentId, int version, String extractedBy) {
            return 0;
        }
    }

    private static final class StubStore implements StructuredKnowledgeStore {
        private final List<StructuredKnowledgeItem> active;

        StubStore(List<StructuredKnowledgeItem> active) {
            this.active = active;
        }

        @Override
        public List<StructuredKnowledgeItem> findActive(String kind, String domain) {
            return active.stream()
                    .filter(i -> i.kind().name().equals(kind) && i.domain().equals(domain))
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findBySource(UUID documentId, int version) {
            return active.stream()
                    .filter(i -> i.sourceDocumentId().equals(documentId)
                            && i.sourceDocumentVersion() == version)
                    .toList();
        }

        @Override
        public List<StructuredKnowledgeItem> findByStatus(StructuredKnowledgeStatus status) {
            return active.stream().filter(i -> i.status() == status).toList();
        }

        @Override
        public Optional<StructuredKnowledgeItem> findById(UUID id) {
            return active.stream().filter(i -> i.id().equals(id)).findFirst();
        }

        @Override
        public StructuredKnowledgeItem save(StructuredKnowledgeItem item) {
            return item;
        }

        @Override
        public List<StructuredKnowledgeItem> saveAll(List<StructuredKnowledgeItem> items) {
            return items;
        }

        @Override
        public void markStatus(UUID id, StructuredKnowledgeStatus status) {
        }

        @Override
        public int supersedeBySource(UUID documentId, int version, String extractedBy) {
            return 0;
        }
    }
}
