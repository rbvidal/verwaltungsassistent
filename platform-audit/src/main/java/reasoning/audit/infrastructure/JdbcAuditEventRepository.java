package reasoning.audit.infrastructure;

import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditEventPage;
import reasoning.audit.api.AuditEventRepository;
import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JDBC-based implementation of {@link AuditEventRepository} supporting H2 and PostgreSQL with auto-table creation. */
@Repository
public class JdbcAuditEventRepository implements AuditEventRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditEventRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private boolean isH2;

    public JdbcAuditEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Ensures the {@code audit_events} table exists with the correct schema, handling H2 and PostgreSQL differences. */
    @PostConstruct
    public void ensureTableExists() {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            isH2 = conn.getMetaData().getDriverName().contains("H2");
        } catch (Exception e) {
            isH2 = false;
        }

        // Drop old table if it has wrong schema (id must be varchar, not bigint/serial)
        try {
            String colType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE lower(table_name) = 'audit_events' AND lower(column_name) = 'id'",
                String.class);
            if (colType != null && !colType.toLowerCase().contains("char") && !colType.toLowerCase().contains("uuid")) {
                log.warn("Dropping audit_events table with wrong id type: {}", colType);
                jdbcTemplate.execute("DROP TABLE audit_events");
            }
        } catch (Exception e) {
            // Table doesn't exist — that's fine
        }

        String metadataCol = isH2 ? "TEXT DEFAULT '{}'" : "JSONB DEFAULT '{}'::jsonb";
        String tsType = isH2 ? "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" : "TIMESTAMPTZ NOT NULL DEFAULT NOW()";

        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS audit_events (" +
                "    id              VARCHAR(36) PRIMARY KEY," +
                "    timestamp       " + tsType + "," +
                "    actor_id        VARCHAR(255)," +
                "    tenant_id       VARCHAR(255)," +
                "    event_type      VARCHAR(100) NOT NULL," +
                "    entity_type     VARCHAR(100)," +
                "    entity_id       VARCHAR(255)," +
                "    source_module   VARCHAR(100)," +
                "    correlation_id  VARCHAR(100)," +
                "    request_id      VARCHAR(100)," +
                "    request_path    VARCHAR(500)," +
                "    http_method     VARCHAR(10)," +
                "    ip_address      VARCHAR(64)," +
                "    metadata        " + metadataCol +
                ")");
            log.info("audit_events table ready (id=VARCHAR(36))");
            ensureColumn();
        } catch (Exception e) {
            log.error("FAILED to create audit_events table: {}", e.getMessage(), e);
        }
    }

    /** Adds ip_address to pre-existing tables (rows created earlier keep NULL). */
    private void ensureColumn() {
        try {
            Integer found = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE lower(table_name) = 'audit_events' AND lower(column_name) = 'ip_address'",
                Integer.class);
            if (found == null || found == 0) {
                jdbcTemplate.execute("ALTER TABLE audit_events ADD COLUMN ip_address VARCHAR(64)");
                log.info("audit_events: added ip_address column");
            }
        } catch (Exception e) {
            log.warn("Could not ensure ip_address column: {}", e.getMessage());
        }
    }

    @Override
    public void append(AuditEvent event) {
        try {
            String metadataJson = event.metadata().toString();
            jdbcTemplate.update(
                "insert into audit_events (" +
                "    id, timestamp, actor_id, tenant_id, event_type, entity_type, entity_id," +
                "    source_module, correlation_id, request_id, request_path, http_method, ip_address, metadata" +
                ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, event.id().toString());
                    ps.setTimestamp(2, Timestamp.from(event.timestamp()));
                    ps.setString(3, event.actorId());
                    ps.setString(4, event.tenantId());
                    ps.setString(5, event.eventType().name());
                    ps.setString(6, event.entityType());
                    ps.setString(7, event.entityId());
                    ps.setString(8, event.sourceModule());
                    ps.setString(9, event.correlationId());
                    ps.setString(10, event.requestId());
                    ps.setString(11, event.requestPath());
                    ps.setString(12, event.httpMethod());
                    ps.setString(13, event.ipAddress());
                    if (isH2) {
                        ps.setString(14, metadataJson);
                    } else {
                        ps.setObject(14, metadataJson, Types.OTHER);
                    }
                });
        } catch (Exception e) {
            log.error("AUDIT INSERT FAILED: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public AuditEventPage find(AuditQuery query) {
        SqlFilter filter = buildFilter(query);
        Long total = jdbcTemplate.queryForObject(
            "select count(*) from audit_events" + filter.whereClause(),
            Long.class, filter.args().toArray());
        int offset = query.page() * query.size();
        List<AuditEvent> events = jdbcTemplate.query(
            "select * from audit_events" + filter.whereClause() + " order by timestamp desc limit ? offset ?",
            ps -> {
                int idx = 1;
                for (Object arg : filter.args()) ps.setObject(idx++, arg);
                ps.setInt(idx++, query.size());
                ps.setInt(idx, offset);
            },
            new AuditEventRowMapper(objectMapper));
        return new AuditEventPage(events, query.page(), query.size(), total != null ? total : 0,
                (total != null && total > 0) ? (int) Math.ceil((double) total / query.size()) : 0);
    }

    private SqlFilter buildFilter(AuditQuery query) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (query.ids() != null && !query.ids().isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(query.ids().size(), "?"));
            where.append(" and id in (").append(placeholders).append(")");
            args.addAll(query.ids());
        }
        if (query.eventType() != null) {
            where.append(" and event_type = ?");
            args.add(query.eventType().name());
        }
        if (query.actorId() != null) {
            where.append(" and actor_id = ?");
            args.add(query.actorId());
        }
        if (query.tenantId() != null) {
            where.append(" and tenant_id = ?");
            args.add(query.tenantId());
        }
        if (query.correlationId() != null) {
            where.append(" and correlation_id = ?");
            args.add(query.correlationId());
        }
        if (query.from() != null) {
            where.append(" and timestamp >= ?");
            args.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            where.append(" and timestamp < ?");
            args.add(Timestamp.from(query.to()));
        }
        String clause = where.length() > 0 ? " where " + where.substring(4) : "";
        return new SqlFilter(clause, args);
    }

    private record SqlFilter(String whereClause, List<Object> args) {}

    private static class AuditEventRowMapper implements RowMapper<AuditEvent> {
        private final ObjectMapper mapper;
        AuditEventRowMapper(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            String metadataStr = rs.getString("metadata");
            JsonNode metadata = null;
            if (metadataStr != null) {
                try { metadata = mapper.readTree(metadataStr); } catch (Exception ignored) {}
            }
            return new AuditEvent(
                UUID.fromString(rs.getString("id")),
                rs.getTimestamp("timestamp").toInstant(),
                rs.getString("actor_id"),
                rs.getString("tenant_id"),
                AuditEventType.valueOf(rs.getString("event_type")),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("source_module"),
                rs.getString("correlation_id"),
                rs.getString("request_id"),
                rs.getString("request_path"),
                rs.getString("http_method"),
                rs.getString("ip_address"),
                metadata
            );
        }
    }
}
