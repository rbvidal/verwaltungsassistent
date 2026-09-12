package reasoning.ai.application;

import reasoning.ai.config.AiPipelineProperties;
import reasoning.ai.model.*;
import reasoning.ai.model.EvidencePackage.Contradiction;
import reasoning.ai.model.EvidencePackage.CoverageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Builds a compact {@link EvidencePackage} from retrieval results.
 *
 * <p>Key simplifications over the original:
 * <ul>
 *   <li>Groups chunks by unique document — one EvidenceItem per document</li>
 *   <li>Merges nearby paragraphs within the same document</li>
 *   <li>Deduplicates repeated excerpts</li>
 *   <li>Limits to {@code maxEvidenceSources} documents (default 4)</li>
 *   <li>Each document contributes at most {@code maxParagraphsPerSource} paragraphs</li>
 * </ul>
 */
@Component
public class EvidencePackageBuilder {

    private static final Logger log = LoggerFactory.getLogger(EvidencePackageBuilder.class);

    private final NumericExtractor numericExtractor;
    private final AiPipelineProperties props;
    private final java.util.Set<String> stopWords;

    public EvidencePackageBuilder(NumericExtractor numericExtractor,
                                  AiPipelineProperties props,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${platform.ai.grounding.stop-words:}") String stopWordsCsv) {
        this.numericExtractor = numericExtractor;
        this.props = props;
        java.util.Set<String> words = new java.util.LinkedHashSet<>();
        if (stopWordsCsv != null) {
            for (String w : stopWordsCsv.split(",")) {
                String t = w.trim().toLowerCase(java.util.Locale.GERMANY);
                if (!t.isEmpty()) {
                    words.add(t);
                    words.add(t.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss"));
                }
            }
        }
        this.stopWords = java.util.Set.copyOf(words);
    }

    /**
     * Gewichtete lexikalische Abdeckung eines Beleg-Auszugs gegenüber der
     * Frage (gleiche Methode wie der Keyword-Retrieval): Summe der Häufigkeiten
     * der im Text vorkommenden Frage-Wörter / Summe aller Häufigkeiten.
     * Wiederholte Frage-Wörter sind das Thema und zählen stärker.
     */
    private double lexicalCoverage(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) return 0;
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String w : query.toLowerCase(java.util.Locale.GERMANY).split("[^\\p{L}0-9]+")) {
            if (w.length() < 3 || isStopWord(w)) continue;
            counts.merge(w, 1, Integer::sum);
        }
        if (counts.isEmpty()) return 0;
        String lower = text.toLowerCase(java.util.Locale.GERMANY);
        double total = 0;
        double matched = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            total += e.getValue();
            for (String v : reasoning.common.text.GermanTermVariants.of(e.getKey())) {
                if (lower.contains(v)) {
                    matched += e.getValue();
                    break;
                }
            }
        }
        return total > 0 ? matched / total : 0;
    }

    private boolean isStopWord(String word) {
        if (stopWords.isEmpty()) return false;
        for (String v : reasoning.common.text.GermanTermVariants.of(word)) {
            if (stopWords.contains(v)) return true;
        }
        return false;
    }

    /**
     * Builds a deduplicated, document-grouped evidence package.
     * Each unique document becomes ONE EvidenceItem with merged paragraphs.
     */
    public EvidencePackage build(String query, List<SourceCitation> sources) {
        return build(query, sources, null);
    }

    public EvidencePackage build(String query, List<SourceCitation> sources, LocalDate asOf) {
        if (sources.isEmpty()) {
            return new EvidencePackage(List.of(), true, List.of(),
                    CoverageStatus.INSUFFICIENT, 0, 0, 0, asOf);
        }

        // Group sources by document title, keeping highest-confidence chunks
        Map<String, List<SourceCitation>> byDoc = new LinkedHashMap<>();
        for (SourceCitation s : sources) {
            String key = s.title() != null ? s.title() : "unknown-" + s.documentId();
            byDoc.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        // Build one EvidenceItem per unique document (limited to maxEvidenceSources).
        // Documents must pass the coverage threshold to be included — relevance takes
        // precedence over document count. No artificial padding with low-confidence docs.
        //
        // Relevanz = gewichtete LEXIKALISCHE ABDECKUNG der Frage-Wörter im Beleg
        // (die Vektor-Ähnlichkeit saturiert auf ~0.65-0.75 für beliebige
        // Verwaltungsdokumente und kann allein keine Dokumente unterscheiden).
        List<EvidenceItem> items = new ArrayList<>();
        int maxSources = props.getMaxEvidenceSources();
        int maxParagraphs = props.getMaxParagraphsPerSource();
        int maxExcerpt = props.getMaxExcerptLength();
        double coverageThreshold = props.getCoverageThreshold();
        // Lexikalische Schwelle: 0.30 Anteil der Frage-Wörter im Beleg (die
        // frühere 0.60-Schwelle war auf die saturierte Vektor-Konfidenz kalibriert).
        double lexicalThreshold = 0.30;
        int idx = 0;

        // Relevanz pro Dokument: maximale lexikalische Abdeckung ÜBER ALLE
        // Chunks des Dokuments (die relevante Passage — z. B. die Fristen im
        // BMG — kann in einem anderen Chunk liegen als der Vektor-Top-Chunk).
        record DocRank(String title, List<SourceCitation> chunks, double lexical, double confidence) {}
        List<DocRank> ranked = new ArrayList<>();
        for (var entry : byDoc.entrySet()) {
            List<SourceCitation> chunks = new ArrayList<>(entry.getValue());
            chunks.sort(Comparator.comparingDouble(SourceCitation::confidenceScore).reversed());
            SourceCitation best = chunks.getFirst();
            double lexical = 0;
            for (SourceCitation c : chunks) {
                if (c.excerpt() == null || c.excerpt().isBlank()) continue;
                lexical = Math.max(lexical, lexicalCoverage(query, c.excerpt()));
            }
            ranked.add(new DocRank(entry.getKey(), chunks, lexical, best.confidenceScore()));
        }
        // Descending by lexical coverage, then descending by confidence.
        // A trailing .reversed() on a thenComparing chain inverts the WHOLE
        // comparator (confidence first, lexical ascending) — which put the
        // highest-confidence document first regardless of lexical quality and
        // made topLexical the wrong reference for the relative-gap filter.
        ranked.sort(Comparator.comparingDouble(DocRank::lexical)
                .thenComparingDouble(DocRank::confidence).reversed());
        double topLexical = ranked.isEmpty() ? 0.0 : ranked.getFirst().lexical();

        for (DocRank doc : ranked) {
            if (idx >= maxSources) break;

            String docTitle = doc.title();
            List<SourceCitation> chunks = doc.chunks();
            SourceCitation best = chunks.getFirst();

            // Absolute Schwelle: der Beleg muss einen substanziellen Anteil der
            // Frage-Wörter enthalten (lexikalische Abdeckung).
            if (doc.lexical() < lexicalThreshold) {
                log.info("EvidencePackage: skipping '{}' (lexikalische Abdeckung {} < Schwelle {})",
                        docTitle, String.format("%.3f", doc.lexical()),
                        String.format("%.3f", lexicalThreshold));
                continue;
            }

            // Relative Lücke zum besten Dokument: weit darunterliegende Treffer
            // (z. B. nur ein einzelnes Frage-Wort) sind Rauschen, kein Beleg.
            // Skaliert statt fester Lücke: Schwelle = max(0.20, Hälfte der besten
            // Abdeckung) — dieselbe Regel wie im Retrieval-Service. Eine feste
            // Lücke (> 0.25 bei Top 1.0) würde legitime 0.5-Stufen-Dokumente
            // (z. B. Ummeldung: BMG 1.0 + Folge-Belege 0.5) fälschlich verwerfen.
            double coverageFloor = Math.max(0.20, topLexical * 0.5);
            log.info("EvidencePackage gap check: doc='{}' abdeckung={} top={} schwelle={} idx={}",
                    docTitle, String.format("%.3f", doc.lexical()),
                    String.format("%.3f", topLexical), String.format("%.3f", coverageFloor), idx);
            if (idx > 0 && doc.lexical() < coverageFloor) {
                log.info("EvidencePackage: skipping '{}' (Abdeckung {} < relative Schwelle {})",
                        docTitle, String.format("%.3f", doc.lexical()),
                        String.format("%.3f", coverageFloor));
                continue;
            }
            idx++;

            // Take top N paragraphs, merge nearby ones. Die relevantesten
            // Abschnitte zuerst: Chunks mit der höchsten lexikalischen
            // Abdeckung (die Frage-Wörter stehen im Auszug), dann Konfidenz.
            chunks.sort(Comparator.comparingDouble((SourceCitation c) ->
                            lexicalCoverage(query, c.excerpt() != null ? c.excerpt() : ""))
                    .thenComparingDouble(SourceCitation::confidenceScore).reversed());
            List<String> mergedParagraphs = mergeParagraphs(chunks, maxParagraphs);
            String joinedExcerpt = String.join(" | ", mergedParagraphs);
            if (joinedExcerpt.length() > maxExcerpt) {
                joinedExcerpt = joinedExcerpt.substring(0, maxExcerpt - 3) + "...";
            }

            String authority = resolveAuthority(docTitle);
            String support = "Allgemeine Rechtsgrundlage"; // domain applications supply support metadata
            NumericExtraction numerics = numericExtractor.extractAll(
                    chunks.stream().map(c -> c.excerpt() != null ? c.excerpt() : "").toList());

            items.add(new EvidenceItem(
                    idx,
                    best.documentId(),
                    best.chunkId(),
                    best.documentVersion(),
                    best.pageNumber(),
                    docTitle,
                    authority,
                    String.valueOf(mergedParagraphs.size()) + " Abschnitte",
                    joinedExcerpt,
                    support,
                    best.confidenceScore(),
                    numerics.isEmpty() ? null : numerics,
                    best.publishedAt(),
                    best.validFrom(),
                    best.validUntil(),
                    best.supersededAt()));
        }

        int totalDocs = Math.max(sources.size(), byDoc.size());
        int relevantDocs = 0;
        for (DocRank doc : ranked) {
            if (doc.lexical() >= lexicalThreshold) relevantDocs++;
        }
        int usedDocs = items.size();

        // Coverage is based on quality, not an arbitrary count minimum.
        // A single high-confidence document is PARTIAL, not INSUFFICIENT.
        boolean insufficient = items.isEmpty();
        CoverageStatus coverage;
        if (insufficient) {
            coverage = CoverageStatus.INSUFFICIENT;
        } else if (usedDocs >= 2 && items.getFirst().confidence() >= coverageThreshold) {
            coverage = CoverageStatus.SUFFICIENT;
        } else {
            coverage = CoverageStatus.PARTIAL;
        }

        log.info("EvidencePackage: {} documents (from {} sources, {} unique) | {} items | coverage={}",
                usedDocs, sources.size(), byDoc.size(), usedDocs, coverage);

        return new EvidencePackage(items, insufficient, List.of(), coverage,
                totalDocs, relevantDocs, usedDocs, asOf);
    }

    /** Merges nearby paragraphs from the same document to reduce repetition. */
    private List<String> mergeParagraphs(List<SourceCitation> chunks, int maxParagraphs) {
        List<String> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SourceCitation c : chunks) {
            String text = c.excerpt() != null ? c.excerpt().strip() : "";
            if (text.isEmpty() || text.length() < 20) continue;
            // Deduplicate
            String normalized = text.toLowerCase().substring(0, Math.min(40, text.length()));
            if (!seen.add(normalized)) continue;
            merged.add(text);
            if (merged.size() >= maxParagraphs) break;
        }
        return merged;
    }

    private String resolveAuthority(String title) {
        return "Land Berlin"; // default; domain applications supply authority metadata
    }
}
