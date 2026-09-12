package reasoning.search.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;

/**
 * Idempotently creates the PostgreSQL full-text index backing keyword
 * retrieval (German text search configuration). Runs after Hibernate has
 * created the schema; skipped on non-PostgreSQL databases (e.g. the H2
 * test context), where FTS is unavailable.
 */
@Component
public class FtsIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(FtsIndexInitializer.class);

    /**
     * Expression must match the query expression in
     * {@link reasoning.search.application.JpaKeywordSearchProvider}
     * (same config literal) for the planner to use the index.
     */
    static final String FTS_INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_search_chunks_text_fts "
                    + "ON search_document_chunks USING GIN (to_tsvector('"
                    + reasoning.search.application.JpaKeywordSearchProvider.FTS_CONFIG + "', text))";

    private final JdbcTemplate jdbc;

    public FtsIndexInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createIndex() {
        if (!isPostgres()) {
            log.info("FTS index skipped: database is not PostgreSQL");
            return;
        }
        try {
            jdbc.execute(FTS_INDEX_DDL);
            log.info("FTS index idx_search_chunks_text_fts ensured");
        } catch (Exception e) {
            log.warn("FTS index creation failed: {}", e.getMessage());
        }
    }

    private boolean isPostgres() {
        try (var connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() != null
                    && meta.getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception e) {
            return false;
        }
    }
}
