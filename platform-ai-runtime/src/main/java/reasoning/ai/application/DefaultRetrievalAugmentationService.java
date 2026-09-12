package reasoning.ai.application;

import reasoning.ai.api.*;
import reasoning.ai.model.*;
import reasoning.ai.model.Domain;
import reasoning.common.text.GermanTermVariants;
import reasoning.search.api.ChunkManagementService;
import reasoning.search.api.GraphSearchProvider;
import reasoning.search.api.SearchFacade;
import reasoning.search.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Single-pass, diversity-aware retrieval. NO recursive searches.
 *
 * <p>One question → one retrieval → one set of results.
 * When Neo4j graph is available, retrieval mode is upgraded to
 * HYBRID_GRAPH so graph traversal results contribute to the candidate set.
 */
@Service
public class DefaultRetrievalAugmentationService implements RetrievalAugmentationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetrievalAugmentationService.class);

    private final SearchFacade searchFacade;
    private final ChunkManagementService chunkStore;
    private final AuthorityGroundingService authorityGroundingService;
    private final RetrievalPlanner retrievalPlanner;
    private final DomainGate domainGate;
    private final GraphSearchProvider graphSearchProvider;
    private final SourceOrchestrationService sourceOrchestrationService;
    private final Set<String> genericTerms;
    private final int temporalOverscan;

    public DefaultRetrievalAugmentationService(
            SearchFacade searchFacade,
            ChunkManagementService chunkStore,
            AuthorityGroundingService authorityGroundingService,
            RetrievalPlanner retrievalPlanner,
            DomainGate domainGate,
            GraphSearchProvider graphSearchProvider,
            SourceOrchestrationService sourceOrchestrationService,
            @Value("${platform.ai.grounding.stop-words:}") String stopWordsCsv,
            @Value("${platform.ai.retrieval.temporal-overscan:10}") int temporalOverscan) {
        this.searchFacade = searchFacade;
        this.chunkStore = chunkStore;
        this.authorityGroundingService = authorityGroundingService;
        this.retrievalPlanner = retrievalPlanner;
        this.domainGate = domainGate;
        this.graphSearchProvider = graphSearchProvider;
        this.sourceOrchestrationService = sourceOrchestrationService;
        this.genericTerms = stopWordsCsv == null || stopWordsCsv.isBlank()
                ? Set.of()
                : Arrays.stream(stopWordsCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.temporalOverscan = Math.max(0, temporalOverscan);
    }

    @Override
    public RetrievalContext retrieve(AiRequest request) {
        return retrieve(request, (Domain) null);
    }

    @Override
    public RetrievalContext retrieve(AiRequest request, StructuredIntent intent) {
        Domain authoritativeDomain = intent != null ? intent.domain() : null;
        return retrieve(request, authoritativeDomain);
    }

    private RetrievalContext retrieve(AiRequest request, Domain authoritativeDomain) {
        // ── Plan retrieval once ──
        RetrievalPlan plan = retrievalPlanner.plan(request, authoritativeDomain);

        // ── Execute single hybrid search ──
        // The retrieval query is the effective question for SEARCH purposes:
        // case analyses supply the case topic (name + description) instead of
        // the instruction boilerplate wrapped around it. The boilerplate is
        // generic administrative text — embedding it dilutes the semantic
        // query and pushes every Verwaltungsdokument toward the same saturated
        // similarity. The LLM prompt keeps the full question; retrieval,
        // anchoring and coverage judge the case topic only.
        String effectiveQuery = request.retrievalQueryOrQuestion();

        // A caller-supplied SearchFilter (e.g. scoped to the documents of a
        // case) restricts retrieval; the plain default stays global.
        SearchFilter filter = request.searchFilter() instanceof SearchFilter sf
                ? sf : new SearchFilter(null, null, null, null, null, null, null, null, List.of());
        boolean graphAvailable = graphSearchProvider != null && graphSearchProvider.isAvailable();
        SearchMode searchMode = graphAvailable ? SearchMode.HYBRID_GRAPH : SearchMode.HYBRID;
        log.info("Retrieval mode: {} (graphAvailable={})", searchMode, graphAvailable);
        // Temporal overscan: fetch more candidates than the final window so
        // expired/future documents cannot occupy the top-N slots and displace
        // valid evidence before the temporal filter runs (filter stays in the
        // AI layer, after hybrid retrieval/reranking; Qdrant/FTS untouched).
        int candidateWindow = plan.maxResults() + temporalOverscan;
        var searchQuery = new SearchQuery(
                effectiveQuery,
                searchMode,
                filter,
                new SearchRequestContext("system", null, null, null),
                0,
                candidateWindow);
        long searchStart = System.nanoTime();
        var page = searchFacade.search(searchQuery);
        long searchMs = (System.nanoTime() - searchStart) / 1_000_000;
        log.info("Search phase (embedding+keyword+qdrant+neo4j+hybrid+rerank): {}ms | mode={}",
                searchMs, searchMode);

        // ── Hard document scope ──
        // When the caller restricted retrieval to specific documents, drop any
        // candidate outside that set (the keyword side filters in SQL; the
        // vector side cannot, so the final merge is scoped here).
        if (!filter.documentIds().isEmpty()) {
            List<SearchResult> scoped = page.results().stream()
                    .filter(r -> r.chunk() != null && filter.documentIds().contains(r.chunk().documentId()))
                    .toList();
            if (scoped.size() != page.results().size()) {
                log.info("Retrieval scoped to {} document(s): {} of {} candidates kept",
                        filter.documentIds().size(), scoped.size(), page.results().size());
            }
            int scopedPages = scoped.isEmpty() ? 0
                    : (int) Math.ceil((double) scoped.size() / Math.max(1, page.size()));
            page = new SearchResultPage(scoped, page.page(), page.size(),
                    scoped.size(), scopedPages, page.retrievalStrategy());
        }

        // ── Apply domain-aware scoring — soft adjustment, not hard filter ──
        // Domain mismatch is treated as a relevance signal, not an absolute exclusion.
        // This preserves cross-domain documents when they are genuinely relevant.
        // When the semantic-intent path supplied an authoritative domain, it is
        // used instead of re-classifying the question via DomainClassifier.
        List<SearchResult> domainFiltered = page.results();
        if (!page.results().isEmpty()) {
            Domain queryDomain = authoritativeDomain != null
                    ? authoritativeDomain
                    : domainGate.classifyDomain(effectiveQuery);
            log.info("DomainGate query domain: {} ({})",
                    queryDomain, authoritativeDomain != null ? "authoritative" : "classified");
            List<String> titles = page.results().stream()
                    .map(r -> r.citation() != null ? r.citation().title() : null)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!titles.isEmpty()) {
                // Log domain classification for transparency
                DomainGate.FilterResult domainResult = domainGate.filterByDomain(queryDomain, titles);
                log.info("DomainGate [{}]: {} accepted, {} rejected → soft adjustment applied",
                        queryDomain, domainResult.accepted().size(), domainResult.rejected().size());
                if (!domainResult.rejected().isEmpty() && domainResult.rejected().size() <= 5) {
                    log.info("DomainGate cross-domain: {}", String.join(", ", domainResult.rejected()));
                }

                // Apply domain score as a soft multiplier to each result
                List<SearchResult> adjusted = new ArrayList<>();
                for (var r : page.results()) {
                    String title = r.citation() != null ? r.citation().title() : null;
                    double domainMultiplier = domainGate.domainScore(queryDomain, title);
                    if (domainMultiplier < 1.0) {
                        log.debug("Domain adjustment: '{}' score {} × {} = {}",
                                title, String.format("%.3f", r.score()),
                                String.format("%.2f", domainMultiplier),
                                String.format("%.3f", r.score() * domainMultiplier));
                    }
                    adjusted.add(new SearchResult(
                            r.chunk(), r.text(),
                            r.score() * domainMultiplier,
                            r.confidenceScore() * domainMultiplier,
                            r.provider(), r.citation(),
                            r.keywordScore(), r.vectorScore(), r.rerankScore(),
                            r.intent(), r.retrievalStrategy()));
                }
                // Re-sort by adjusted score
                adjusted.sort(java.util.Comparator.comparingDouble(SearchResult::score).reversed());
                domainFiltered = adjusted;
            }
        }

        // ── Apply diversity constraint: max N chunks per document ──
        List<SearchResult> diverseResults = enforceDiversity(
                domainFiltered, plan.maxChunksPerDocument());

        // ── Evidence anchor: generic vocabulary alone is not evidence ──
        // Wenn mindestens ein Kandidat eine LEXIKALISCHE ÜBEREINSTIMMUNG mit
        // der Frage hat (keywordScore > 0 — die gewichtete Abdeckung der
        // Frage-Wörter ÜBER ALLE Chunks des Dokuments), sind Kandidaten ohne
        // jegliche Übereinstimmung kein Beleg und werden verworfen. Nur wenn
        // GAR KEIN Kandidat übereinstimmt (z. B. reine Synonym-Frage), bleibt
        // die Roh-Reihung erhalten. Der keywordScore ersetzt den Text-Check
        // des Gewinner-Chunks: die relevanten Wörter können in einem anderen
        // Chunk desselben Dokuments liegen (z. B. die Fristen-Passage tief im
        // BMG), ohne dass das Dokument dadurch seinen Belegcharakter verliert.
        List<SearchResult> evidenceResults = anchorEvidence(effectiveQuery, diverseResults, genericTerms);
        if (evidenceResults.size() < diverseResults.size()) {
            log.info("Evidence anchor: dropped {} of {} results without lexical overlap",
                    diverseResults.size() - evidenceResults.size(), diverseResults.size());
            // Dokument-Erweiterung: Der Anker verankert das DOKUMENT über seinen
            // Beleg-Chunk. Die Passage, die die Frage tatsächlich beantwortet
            // (z. B. die Unterlagen-Liste im info_gewerbe_anmelden-Dokument),
            // kann in einem anderen Chunk desselben Dokuments liegen — sogar
            // ohne die Frage-Wörter zu wiederholen. Die Geschwister-Chunks der
            // verankerten Dokumente mit lexikalischer Abdeckung (gleiche Regel
            // wie der Anker) werden zusätzlich als Kandidaten aufgenommen, damit
            // der Evidenz-Package-Builder die beantwortende Passage auswählen
            // kann. Neue Dokumente kommen dadurch NICHT hinzu — nur Chunks
            // bereits verankerter Dokumente — die Müll/BinSchPersV-Absicherung
            // (Abdeckungs-Schwellen des Builders) bleibt unverändert wirksam.
            String intentLabel = evidenceResults.getFirst() != null
                    ? evidenceResults.getFirst().intent() : "GENERAL";
            evidenceResults = expandAnchoredDocuments(
                    effectiveQuery, evidenceResults, intentLabel, page.retrievalStrategy());
        }

        // ── Semantische Verankerung als Relevanz-Gate ──
        // Früher entschied die LEXIKALISCHE Abdeckung (keywordScore, gewichtete
        // Frage-Wort-Übereinstimmung) über die Zulassung. Mit einem auf wenige
        // Themenwörter reduzierten Suchtext (Fallname + Beschreibung) reicht
        // EIN einziges vorkommendes Frage-Wort für eine Abdeckung von 0.5 —
        // damit qualifizierte sich z. B. die Gaststätten-Erlaubnis ("…ersetzt
        // nicht die Baugenehmigung…") als Beleg für einen Baugenehmigungs-Fall.
        // Die Zulassung nutzt daher die SEMANTISCHE Ähnlichkeit (vectorScore,
        // semantisch-primärer Kanal): ein Beleg muss mindestens die Hälfte der
        // besten Vektor-Ähnlichkeit im Treffer-Satz erreichen (Boden 0.20).
        // Der keywordScore bleibt als Anker (lexikalische Verankerung) und in
        // der Fusions-Reihung erhalten; die reine Wort-Überlappung allein
        // lässt keine Dokumente mehr zu.
        double maxVector = evidenceResults.stream()
                .mapToDouble(SearchResult::vectorScore)
                .max().orElse(0);
        double vectorFloor = Math.max(0.20, maxVector * 0.5);
        List<SearchResult> semanticallyAnchored = evidenceResults.stream()
                .filter(r -> r.vectorScore() >= vectorFloor)
                .toList();
        if (semanticallyAnchored.size() < evidenceResults.size()) {
            log.info("Semantische Verankerung: {} von {} Kandidaten unter Vektor-Schwelle {} verworfen",
                    evidenceResults.size() - semanticallyAnchored.size(), evidenceResults.size(),
                    String.format(java.util.Locale.GERMANY, "%.2f", vectorFloor));
        }
        evidenceResults = semanticallyAnchored;

        // ── Temporal validity filter (explicit dates only) ──
        // Documents explicitly not valid for the analysis as-of date (future
        // start, expired end, or superseded) are removed from current evidence.
        // Unknown validity is kept but flagged as unknown downstream — never
        // silently treated as proven current validity. Historical as-of dates
        // keep historical documents retrievable.
        LocalDate asOf = request.asOf() != null ? request.asOf() : LocalDate.now();
        evidenceResults = filterByTemporalValidity(evidenceResults, asOf);
        evidenceResults = trimToWindow(evidenceResults, plan.maxResults());

        log.info("Retrieval: {} total → {} domain-scored → {} diverse (max {}/doc) → {} evidence-anchored | domain={}",
                page.results().size(), domainFiltered.size(), diverseResults.size(),
                plan.maxChunksPerDocument(), evidenceResults.size(), plan.primaryDomain());

        // ── Per-source breakdown ──
        long keywordHits = page.results().stream().filter(r -> r.keywordScore() > 0).count();
        long vectorHits = page.results().stream().filter(r -> r.vectorScore() > 0).count();
        long graphHits = page.results().stream().filter(r -> "graph".equalsIgnoreCase(r.provider())).count();
        long reranked = page.results().stream().filter(r -> r.rerankScore() > 0).count();
        log.info("Retrieval breakdown: keyword={} vector={} graph={} reranked={}",
                keywordHits, vectorHits, graphHits, reranked);

        // ── Build citations ──
        List<SourceCitation> sources = new ArrayList<>();
        for (var r : evidenceResults) {
            List<String> retrievalSources = computeRetrievalSources(r);
            sources.add(new SourceCitation(
                    r.chunk().documentId(), r.chunk().chunkId(),
                    r.chunk().documentVersion(),
                    r.citation().title() != null ? r.citation().title() : "",
                    r.citation().pageNumber(),
                    r.citation().startOffset(),
                    r.citation().endOffset(),
                    r.citation().excerpt() != null ? r.citation().excerpt() : r.text(),
                    r.score(),
                    SourceCitation.classifyTier(r.score()),
                    SourceCitation.SourceType.FACTUAL,
                    retrievalSources,
                    r.citation().publishedAt(),
                    r.citation().validFrom(),
                    r.citation().validUntil(),
                    r.citation().supersededAt()));
        }

        // ── Authority grounding ──
        var authorityResult = authorityGroundingService.ground(effectiveQuery);

        // ── Build source dossier for coverage confidence ──
        SourceDossier dossier = sourceOrchestrationService.buildDossier(
                sources, effectiveQuery);
        log.info("Source dossier: coverageScore={}, {} sources classified",
                dossier.coverageScore(), sources.size());

        log.info("Retrieval diversity: {} docs across {} unique titles | {} authorities",
                sources.size(), countUniqueDocs(sources), authorityResult.references().size());

        return new RetrievalContext(
                effectiveQuery,
                plan.retrievalStrategy(),
                sources,
                authorityResult.references(),
                null, dossier, null, null);
    }

    /**
     * Determines which retrieval mechanisms discovered this result.
     * Preserves multiple origins when a candidate was found by more than one path.
     */
    static List<String> computeRetrievalSources(SearchResult r) {
        List<String> sources = new ArrayList<>();
        if (r.keywordScore() > 0) sources.add("KEYWORD");
        if (r.vectorScore() > 0) sources.add("VECTOR");
        String provider = r.provider() != null ? r.provider().toLowerCase() : "";
        if (provider.contains("graph") || provider.equals("hybrid+graph")) {
            sources.add("GRAPH");
        }
        if (sources.isEmpty() && !provider.isEmpty()) {
            // Provider string like "hybrid" without individual scores — use as-is
            sources.add(provider.toUpperCase());
        }
        return sources;
    }

    /**
     * Enforces diversity: at most maxPerDoc chunks from any single document.
     * Chunks are kept in ranking order but the maxPerDoc constraint
     * ensures different regulations appear.
     */
    private List<SearchResult> enforceDiversity(List<SearchResult> results, int maxPerDoc) {
        List<SearchResult> diverse = new ArrayList<>();
        Map<UUID, Integer> docCounts = new LinkedHashMap<>();

        for (var r : results) {
            UUID docKey = r.chunk().documentId();
            int count = docCounts.getOrDefault(docKey, 0);
            if (count < maxPerDoc) {
                diverse.add(r);
                docCounts.put(docKey, count + 1);
            }
        }
        return diverse;
    }

    private long countUniqueDocs(List<SourceCitation> sources) {
        return sources.stream()
                .map(s -> s.title() != null ? s.title() : s.documentId().toString())
                .distinct().count();
    }

    // ── Evidence anchor ──

    /**
     * Keeps only results that carry at least one SPECIFIC question term when
     * such an anchor exists anywhere in the result set. {@code genericTerms}
     * (German generic vocabulary, configured via
     * {@code platform.ai.grounding.stop-words}) never qualifies as evidence.
     *
     * <p>Terms are matched morphologically ({@link GermanTermVariants}):
     * "Ummeldung" in the question also anchors "Anmeldung"/"meldung" in the
     * corpus text, so the rule never starves semantically correct evidence on
     * German prefix differences. As a safety net the filter is skipped when it
     * would leave fewer than two candidates — evidence must never be reduced
     * to a single guess by a morphological rule.</p>
     */
    static List<SearchResult> anchorEvidence(String question, List<SearchResult> results,
                                             Set<String> genericTerms) {
        if (results == null || results.isEmpty()) return List.copyOf(results);
        List<String> specific = specificTerms(question, genericTerms);
        if (specific.isEmpty()) return List.copyOf(results);
        // Lexikalische Übereinstimmung über den keywordScore (gewichtete
        // Abdeckung der Frage-Wörter im Dokument). Ein Kandidat ohne jegliche
        // Übereinstimmung (keywordScore 0) ist kein Beleg, sobald irgendein
        // Kandidat übereinstimmt.
        boolean anchorPresent = results.stream()
                .anyMatch(r -> r.keywordScore() > 0);
        if (!anchorPresent) return List.copyOf(results);
        List<SearchResult> anchored = results.stream()
                .filter(r -> r.keywordScore() > 0)
                .toList();
        if (anchored.size() < 2) {
            log.info("Evidence anchor: skipped filter (would leave only {} candidate(s)) — "
                    + "raw ranking kept", anchored.size());
            return List.copyOf(results);
        }
        return anchored;
    }

    private static String textOf(SearchResult r) {
        if (r == null) return "";
        return r.text() != null ? r.text() : "";
    }

    // ── Dokument-Erweiterung (beantwortende Passage innerhalb verankerter Dokumente) ──

    /**
     * Erweitert die verankerten Kandidaten um die Geschwister-Chunks der
     * verankerten Dokumente. Die Geschwister eines Dokuments werden im
     * DOKUMENT (chunk_index aufsteigend) geladen — ein Gesetz wie das BMG ist
     * in Normenreihenfolge aufgebaut, die Grundnorm (§ 17 Abs. 1 Anmeldepflicht)
     * steht VOR ihren Sonder-/Ausnahmeregelungen; ein nur an der Vektor-
     * Ähnlichkeit ausgerichtetes „tiefer im Dokument"-Kriterium bevorzugt
     * dagegen die spezielleren (und oft wortgleicher formulierten) Passagen.
     * Bei gleicher lexikalischer Abdeckung entscheidet deshalb die Reihenfolge
     * im Dokument, damit die beantwortende Passage in das begrenzte
     * Auszugsfenster des Package-Builders passt.
     */
    private static final int EXPANSION_FETCH_WINDOW = 500;

    private List<SearchResult> expandAnchoredDocuments(String question, List<SearchResult> anchored,
                                                       String intent, String strategy) {
        if (anchored == null || anchored.isEmpty()) return anchored;
        // Geschwister pro Dokument einmal laden (ein Fetch pro Dokument). Der
        // Fetch muss das GANZE Dokument abdecken (Gesetze haben hunderte
        // Chunks) — ein 30-Chunk-Fenster würde die Fristen-Passage tief im
        // BMG (chunk_index 64+) nie zu Gesicht bekommen.
        Map<UUID, List<DocumentChunk>> siblingsByDoc = new HashMap<>();
        for (var r : anchored) {
            if (r.chunk() == null) continue;
            UUID docId = r.chunk().documentId();
            if (siblingsByDoc.containsKey(docId)) continue;
            try {
                siblingsByDoc.put(docId, chunkStore.findChunks(
                        new SearchFilter(Set.of(docId), null, null, null, null, null,
                                null, null, List.of()),
                        0, EXPANSION_FETCH_WINDOW));
            } catch (Exception e) {
                log.warn("Evidence expansion: chunk fetch failed for {}: {}", docId, e.getMessage());
                siblingsByDoc.put(docId, List.of());
            }
        }
        List<SearchResult> expanded = new ArrayList<>();
        int added = 0;
        for (var beleg : anchored) {
            if (beleg.chunk() == null) {
                expanded.add(beleg);
                continue;
            }
            // Geschwister mit lexikalischer Abdeckung (gleiche Regel wie der
            // Anker), in Dokumentreihenfolge (bei gleicher Abdeckung der
            // FRÜHERE Chunk — die Normenhierarchie im Gesetz). Die
            // Geschwister stehen VOR dem Beleg: der Package-Builder führt die
            // Chunks eines Dokuments in dieser Reihenfolge zusammen, und die
            // beantwortende Passage muss in das begrenzte Auszugsfenster passen.
            List<DocumentChunk> covered = siblingsByDoc.getOrDefault(
                            beleg.chunk().documentId(), List.of()).stream()
                    .filter(s -> !s.id().equals(beleg.chunk().chunkId()))
                    .filter(s -> weightedCoverage(question, s.text()) > 0)
                    .sorted(java.util.Comparator
                            .comparingDouble((DocumentChunk s) -> weightedCoverage(question, s.text()))
                            .reversed()
                            .thenComparingInt(s -> s.position().chunkIndex()))
                    // Fenster-Grenze pro Dokument: genug für die relevante
                    // Passage eines Gesetzes, ohne das Evidenz-Fenster mit den
                    // Geschwistern EINES Dokuments zu dominieren.
                    .limit(6)
                    .toList();
            for (DocumentChunk s : covered) {
                expanded.add(toExpandedResult(s, beleg, weightedCoverage(question, s.text()), intent, strategy));
                added++;
            }
            expanded.add(beleg);
        }
        if (added > 0) {
            log.info("Evidence expansion: {} Geschwister-Chunks zu {} verankerten Dokumenten hinzugefügt",
                    added, siblingsByDoc.size());
        }
        return expanded;
    }

    private SearchResult toExpandedResult(DocumentChunk chunk, SearchResult beleg, double coverage,
                                          String intent, String strategy) {
        ChunkPosition pos = chunk.position();
        String excerpt = chunk.text() != null && chunk.text().length() > 240
                ? chunk.text().substring(0, 240) : chunk.text();
        CitationReference citation = new CitationReference(
                chunk.documentId(), chunk.id(), chunk.documentVersion(),
                chunk.metadata().title(), pos.pageNumber(), pos.startOffset(), pos.endOffset(),
                excerpt,
                chunk.metadata().publishedAt(), chunk.metadata().validFrom(),
                chunk.metadata().validUntil(), chunk.metadata().supersededAt());
        return new SearchResult(
                new ChunkReference(chunk.id(), chunk.documentId(), chunk.documentVersion(),
                        chunk.metadata().title(), pos, chunk.metadata().documentType()),
                chunk.text(),
                beleg.score(), beleg.confidenceScore(), "document-expansion",
                citation, coverage, beleg.vectorScore(), 0.0, intent, strategy);
    }

    /** Gewichtete lexikalische Abdeckung (gleiche Methode wie der Keyword-Search). */
    private double weightedCoverage(String question, String text) {
        if (question == null || question.isBlank() || text == null || text.isBlank()) return 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String w : question.toLowerCase(Locale.GERMANY).split("[^\\p{L}0-9]+")) {
            if (w.length() < 3 || isGenericTerm(w)) continue;
            counts.merge(w, 1, Integer::sum);
        }
        if (counts.isEmpty()) return 0;
        double total = 0;
        double matched = 0;
        String lower = text.toLowerCase(Locale.GERMANY);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            total += e.getValue();
            for (String v : GermanTermVariants.of(e.getKey())) {
                if (lower.contains(v)) {
                    matched += e.getValue();
                    break;
                }
            }
        }
        return total > 0 ? matched / total : 0;
    }

    private boolean isGenericTerm(String word) {
        if (genericTerms.isEmpty()) return false;
        for (String v : GermanTermVariants.of(word)) {
            if (genericTerms.contains(v)) return true;
        }
        return false;
    }

    public static List<String> specificTerms(String question, Set<String> genericTerms) {
        if (question == null || question.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        // Split on any non-alphanumeric separator (spaces AND hyphens), so
        // compound tokens like "Online-Termin" decompose into words the
        // generic list can exclude.
        for (String w : question.toLowerCase().split("[^\\p{L}0-9]+")) {
            String cleaned = w.trim();
            if (cleaned.length() > 3 && !genericTerms.contains(cleaned)) {
                terms.add(cleaned);
                String ascii = cleaned.replace("ä", "a").replace("ö", "o")
                        .replace("ü", "u").replace("ß", "ss");
                if (!ascii.equals(cleaned)) terms.add(ascii);
            }
        }
        return new ArrayList<>(terms);
    }

    static boolean containsAnyVariant(String text, List<Set<String>> variantSets) {
        String lower = text.toLowerCase(Locale.GERMANY);
        for (Set<String> variants : variantSets) {
            for (String v : variants) {
                if (lower.contains(v)) return true;
            }
        }
        return false;
    }

    // ── Temporal validity filter ──

    /**
     * Drops results whose citation dates prove the source is NOT valid for the
     * analysis as-of date: future (validFrom after asOf), expired (validUntil
     * before asOf), or superseded (supersededAt at/before asOf). Results with
     * unknown validity (all dates null) are KEPT — they are flagged as unknown
     * downstream, never silently treated as current.
     */
    static List<SearchResult> filterByTemporalValidity(List<SearchResult> results, LocalDate asOf) {
        if (results == null || results.isEmpty() || asOf == null) {
            return results == null ? List.of() : results;
        }
        List<SearchResult> eligible = new ArrayList<>();
        int dropped = 0;
        for (var r : results) {
            CitationReference c = r.citation();
            LocalDate validFrom = c != null ? c.validFrom() : null;
            LocalDate validUntil = c != null ? c.validUntil() : null;
            LocalDate supersededAt = c != null ? c.supersededAt() : null;
            boolean future = validFrom != null && validFrom.isAfter(asOf);
            boolean expired = validUntil != null && validUntil.isBefore(asOf);
            boolean superseded = supersededAt != null && !supersededAt.isAfter(asOf);
            if (future || expired || superseded) {
                dropped++;
                continue;
            }
            eligible.add(r);
        }
        if (dropped > 0) {
            log.info("Temporal validity filter (asOf={}): dropped {} result(s) not valid at that date, kept {}",
                    asOf, dropped, eligible.size());
        }
        return eligible;
    }

    /**
     * Trims the (overscanned, temporally filtered) candidate set back to the
     * intended final window before downstream evidence selection/ranking.
     */
    static List<SearchResult> trimToWindow(List<SearchResult> results, int maxResults) {
        if (results == null || results.isEmpty() || maxResults <= 0) {
            return results == null ? List.of() : results;
        }
        if (results.size() <= maxResults) {
            return results;
        }
        log.info("Temporal overscan: trimmed {} → {} candidate(s) after validity filtering",
                results.size(), maxResults);
        return List.copyOf(results.subList(0, maxResults));
    }
}
