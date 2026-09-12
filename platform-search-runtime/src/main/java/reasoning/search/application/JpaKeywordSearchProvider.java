package reasoning.search.application;

import reasoning.common.model.DocumentFileType;
import reasoning.common.text.GermanTermVariants;
import reasoning.search.api.CitationService;
import reasoning.search.api.KeywordSearchProvider;
import reasoning.search.infrastructure.persistence.DocumentChunkEntity;
import reasoning.search.infrastructure.persistence.SearchMapper;
import reasoning.search.model.ChunkReference;
import reasoning.search.model.ChunkType;
import reasoning.search.model.DocumentChunk;
import reasoning.search.model.MetadataFilter;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchFilter;
import reasoning.search.model.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL full-text search keyword provider (German configuration).
 *
 * <p>Replaces LIKE-based token matching: the built-in {@code german} text
 * search configuration handles stopword removal, stemming and normalization
 * (including umlauts and {@code ß}) for both the query and the documents,
 * and candidates are ranked by {@code ts_rank_cd} (cover density) instead
 * of arbitrary chunk order. Only chunks containing every content term of
 * the query are candidates; recall for partial matches is provided by the
 * vector channel of the hybrid merge.</p>
 *
 * <p>On non-PostgreSQL databases the provider returns no candidates (FTS is
 * a PostgreSQL feature; the H2 test context is the affected case).</p>
 */
@Component
public class JpaKeywordSearchProvider implements KeywordSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(JpaKeywordSearchProvider.class);

    /**
     * Text search configuration; must match the FTS index expression in
     * {@link reasoning.search.infrastructure.persistence.FtsIndexInitializer}.
     */
    public static final String FTS_CONFIG = "german";

    private final JdbcTemplate jdbc;
    private final CitationService citationService;
    private final RetrievalProperties properties;
    /** Generisches Verwaltungsvokabular (gleiche Quelle wie die Grounding-Stoppwörter der App). */
    private final Set<String> stopWords;

    public JpaKeywordSearchProvider(JdbcTemplate jdbc,
                                    CitationService citationService,
                                    RetrievalProperties properties,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${platform.ai.grounding.stop-words:}") String stopWordsCsv) {
        this.jdbc = jdbc;
        this.citationService = citationService;
        this.properties = properties;
        Set<String> words = new java.util.LinkedHashSet<>();
        if (stopWordsCsv != null) {
            for (String w : stopWordsCsv.split(",")) {
                String t = w.trim().toLowerCase(java.util.Locale.GERMANY);
                if (!t.isEmpty()) {
                    words.add(t);
                    words.add(asciiFold(t));
                }
            }
        }
        this.stopWords = Set.copyOf(words);
    }

    private static String asciiFold(String s) {
        return s.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
    }

    @Override
    public List<RetrievalCandidate> search(SearchQuery query) {
        String question = query.query();
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (!isPostgres()) {
            log.warn("Keyword FTS skipped: database is not PostgreSQL");
            return List.of();
        }

        // Fragen-Wörter (≥3 Zeichen, ohne generisches Verwaltungsvokabular)
        // mit Häufigkeit: wiederholte Wörter sind das Thema der Frage und
        // zählen stärker.
        List<TokenWeight> tokens = weightedTokens(question);
        if (tokens.isEmpty()) {
            return List.of();
        }
        // Deduplizierte Suchbegriffe (Wort + deutsche Präfix-Varianten,
        // z. B. "ummeldung" → "ummeldung | meldung").
        List<String> patterns = new ArrayList<>();
        for (TokenWeight t : tokens) {
            for (String v : t.variants()) {
                if (!patterns.contains(v)) patterns.add(v);
            }
        }
        if (patterns.isEmpty()) {
            return List.of();
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT c.id, c.document_id, c.document_version, c.chunk_type, c.text, c.page_number, ")
                .append("c.section_index, c.chunk_index, c.start_offset, c.end_offset, c.title, c.document_type, ")
                .append("c.category, c.source, c.tenant_id, c.document_created_at, c.published_at, c.valid_from, ")
                .append("c.valid_until, c.superseded_at, c.embedding_reference, ")
                .append("c.created_at, c.updated_at ")
                .append("FROM search_document_chunks c ")
                .append("WHERE (");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("c.text ILIKE ?");
            params.add("%" + patterns.get(i) + "%");
        }
        sql.append(")");
        appendFilterClauses(sql, params, query.filter());
        // KEIN ORDER BY chunk_index: eine chunk_index-geordnete SQL-Fensterung
        // würde nur die ersten Chunks jedes Dokuments fetchen (alle
        // chunk_index 0..N über das Korpus sortiert) und die beantwortende
        // Passage tiefer im Dokument (z. B. die Unterlagen-Liste des
        // info_gewerbe_anmelden-Dokuments oder die Fristen-Passage im BMG)
        // gar nicht erst liefern — sie käme ohne keywordScore in den Merge und
        // verlöre die Beleg-Auswahl gegen einen thematischen Chunk mit
        // Wortübereinstimmung. Die Reihung (Abdeckung DESC) wird in Java
        // angewandt; der Fetch holt großzügig alle Treffer.
        sql.append(" LIMIT ?");
        params.add(KEYWORD_FETCH_LIMIT);

        List<Row> rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> toRow(rs),
                params.toArray());

        // Gewichtete lexikalische Abdeckung in Java: Summe der Häufigkeiten
        // der im Chunk-Text vorkommenden Frage-Wörter / Summe aller
        // Häufigkeiten. Ein Einzelwort-Treffer ("Ufer" in einer
        // Binnenschifffahrtsverordnung) skaliert damit korrekt herunter —
        // die frühere ts_rank_cd * scale-Klammer saturierte bei 1.0.
        double totalWeight = tokens.stream().mapToDouble(TokenWeight::weight).sum();
        List<Row> scored = rows.stream()
                .map(row -> {
                    String lower = row.entity().getText() != null
                            ? row.entity().getText().toLowerCase(java.util.Locale.GERMANY) : "";
                    double matched = 0;
                    for (TokenWeight t : tokens) {
                        if (containsAny(lower, t.variants())) {
                            matched += t.weight();
                        }
                    }
                    double coverage = totalWeight > 0 ? matched / totalWeight : 0;
                    // TOC-Artefakt-Penalty: OCR-Inhaltsverzeichnisse wiederholen
                    // die Frage-Wörter (z. B. "Gewerbe anmelden .... 2") und
                    // würden sonst über echte Inhalts-Chunks gewinnen — der
                    // Beleg-Chunk eines Dokuments wäre dann der TOC-Auszug,
                    // nicht die beantwortende Passage. Chunks, deren Text zu
                    // großen Teilen aus Punkt-Führungslinien besteht, zählen
                    // daher nicht als lexikalische Treffer.
                    return new Row(row.entity(), isTocJunk(lower) ? 0.0 : coverage);
                })
                .sorted(java.util.Comparator.comparingDouble(Row::score).reversed()
                        .thenComparingInt(r -> r.entity().getChunkIndex()))
                // KEINE weitere Kürzung unterhalb des SQL-Fetch: Der lexikalische
                // Kanal muss ALLE gefundenen Chunks (≤ KEYWORD_FETCH_LIMIT) in
                // den Merge geben. Ein hartes Top-N nach (Abdeckung, chunk_index)
                // würde die beantwortende Passage großer Gesetze abschneiden —
                // bei gleicher Abdeckung reihen sich hunderte Rausch-Chunks
                // (häufige Frage-Wörter in frühen Chunk-Indizes) vor die tief
                // im Dokument liegende Fristen-Passage. Die Dokument-
                // Deduplikation und die Evidenz-Gates downstream entscheiden
                // über die Zulassung; die Reihung hier dient nur der Stabilität.
                .toList();

        scored.stream().limit(12).forEach(r -> log.debug("KEYWORD-HIT: {} kw={}",
                r.entity().getTitle() != null ? r.entity().getTitle() : r.entity().getDocumentId(),
                String.format(java.util.Locale.GERMANY, "%.3f", r.score())));
        return scored.stream().map(row -> toCandidate(row)).toList();
    }

    private record TokenWeight(String word, double weight, List<String> variants) {}

    /** Fragen-Wörter mit Häufigkeit und Präfix-Varianten (Stoppwörter ausgenommen). */
    private List<TokenWeight> weightedTokens(String question) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String word : question.toLowerCase(java.util.Locale.GERMANY).split("[^\\p{L}0-9]+")) {
            if (word.length() < 3 || isStopWord(word)) continue;
            counts.merge(word, 1, Integer::sum);
        }
        List<TokenWeight> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            List<String> variants = new ArrayList<>();
            for (String v : GermanTermVariants.of(e.getKey())) {
                String folded = asciiFold(v);
                if (!variants.contains(v)) variants.add(v);
                if (!folded.equals(v) && !variants.contains(folded)) variants.add(folded);
            }
            out.add(new TokenWeight(e.getKey(), e.getValue(), variants));
        }
        log.debug("KEYWORD-TOKENS: {}", out.stream().map(t -> t.word() + "x" + t.weight())
                .collect(java.util.stream.Collectors.joining(", ")));
        return out;
    }

    private static boolean containsAny(String lower, List<String> variants) {
        for (String v : variants) {
            if (lower.contains(v)) return true;
        }
        return false;
    }

    /** Obergrenze des SQL-Fetch (alle ILIKE-Treffer; das Scoring erfolgt in Java). */
    private static final int KEYWORD_FETCH_LIMIT = 2000;

    /** Anteil Punkt-Führungslinien ab dem ein Chunk als TOC-Artefakt gilt. */
    private static final double TOC_DOT_DENSITY = 0.08;

    /**
     * True für OCR-TOC-Fragmente: lange Punkt-Reihen (".... 2") als
     * Führungslinien. Kurze Ellipsen in Fließtext (max. 2 Punkte) zählen nicht.
     */
    private static boolean isTocJunk(String lower) {
        if (lower == null || lower.length() <= 40) return false;
        int dots = 0;
        int run = 0;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) == '.') {
                run++;
            } else {
                if (run >= 3) dots += run;
                run = 0;
            }
        }
        if (run >= 3) dots += run;
        return dots >= lower.length() * TOC_DOT_DENSITY;
    }

    /** Varianten-bewusst: prüft das rohe Wort UND seine Präfix-Varianten gegen die Stoppwörter. */
    private boolean isStopWord(String word) {
        if (stopWords.isEmpty()) return false;
        for (String v : GermanTermVariants.of(word)) {
            if (stopWords.contains(v) || stopWords.contains(asciiFold(v))) {
                return true;
            }
        }
        return false;
    }

    // ── Row mapping ──

    private record Row(DocumentChunkEntity entity, double score) {}

    private Row toRow(ResultSet rs) throws SQLException {
        DocumentChunkEntity entity = new DocumentChunkEntity(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getInt("document_version"),
                parseChunkType(rs.getString("chunk_type")),
                rs.getString("text"),
                asInteger(rs, "page_number"),
                asInteger(rs, "section_index"),
                rs.getInt("chunk_index"),
                asInteger(rs, "start_offset"),
                asInteger(rs, "end_offset"),
                rs.getString("title"),
                parseDocType(rs.getString("document_type")),
                rs.getString("category"),
                new HashSet<>(),
                rs.getString("source"),
                rs.getString("tenant_id"),
                asInstant(rs, "document_created_at"),
                new HashSet<>(),
                rs.getString("embedding_reference"),
                asLocalDate(rs, "published_at"),
                asLocalDate(rs, "valid_from"),
                asLocalDate(rs, "valid_until"),
                asLocalDate(rs, "superseded_at"));
        // Der Abdeckungs-Score wird nach dem Fetch in Java berechnet (siehe search()).
        return new Row(entity, 0.0);
    }

    private RetrievalCandidate toCandidate(Row row) {
        DocumentChunk chunk = SearchMapper.toModel(row.entity);
        return new RetrievalCandidate(
                new ChunkReference(chunk.id(), chunk.documentId(), chunk.documentVersion(),
                        chunk.metadata().title(), chunk.position(), chunk.metadata().documentType()),
                chunk.text(),
                row.score,
                0.0,
                row.score,
                row.score,
                "keyword",
                citationService.citationFor(chunk));
    }

    // ── Filter clauses (mirrors DocumentChunkSpecifications.from) ──

    private void appendFilterClauses(StringBuilder sql, List<Object> params, SearchFilter filter) {
        if (filter == null) {
            return;
        }
        if (filter.documentIds() != null && !filter.documentIds().isEmpty()) {
            sql.append(" AND c.document_id IN (");
            boolean first = true;
            for (UUID documentId : filter.documentIds()) {
                if (!first) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(documentId);
                first = false;
            }
            sql.append(")");
        }
        if (filter.documentType() != null) {
            sql.append(" AND c.document_type = ?");
            params.add(filter.documentType().name());
        }
        if (hasText(filter.category())) {
            sql.append(" AND c.category = ?");
            params.add(filter.category().trim());
        }
        if (hasText(filter.tag())) {
            sql.append(" AND EXISTS (SELECT 1 FROM search_document_chunk_tags t "
                    + "WHERE t.chunk_id = c.id AND t.tag = ?)");
            params.add(filter.tag().trim().toLowerCase());
        }
        if (hasText(filter.source())) {
            sql.append(" AND c.source = ?");
            params.add(filter.source().trim());
        }
        if (hasText(filter.tenantId())) {
            sql.append(" AND c.tenant_id = ?");
            params.add(filter.tenantId().trim());
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND c.document_created_at >= ?");
            params.add(filter.createdFrom());
        }
        if (filter.createdTo() != null) {
            sql.append(" AND c.document_created_at <= ?");
            params.add(filter.createdTo());
        }
        if (filter.metadata() != null && !filter.metadata().isEmpty()) {
            for (MetadataFilter metadata : filter.metadata()) {
                sql.append(" AND EXISTS (SELECT 1 FROM search_document_chunk_metadata m "
                        + "WHERE m.chunk_id = c.id AND m.attr_key = ? AND m.attr_value = ?)");
                params.add(metadata.key());
                params.add(metadata.value());
            }
        }
    }

    // ── Helpers ──

    private boolean isPostgres() {
        try (var connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() != null
                    && meta.getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ChunkType parseChunkType(String value) {
        if (value == null) {
            return ChunkType.UNKNOWN;
        }
        try {
            return ChunkType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ChunkType.UNKNOWN;
        }
    }

    private static DocumentFileType parseDocType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DocumentFileType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer asInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant asInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private static LocalDate asLocalDate(ResultSet rs, String column) throws SQLException {
        var date = rs.getDate(column);
        return date != null ? date.toLocalDate() : null;
    }
}
