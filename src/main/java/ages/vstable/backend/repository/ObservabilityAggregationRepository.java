package ages.vstable.backend.repository;

import ages.vstable.backend.dto.observability.EventFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ObservabilityAggregationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public record Aggregate(long bucket, String severity, String module, long events) { }

    public List<Aggregate> aggregate(EventFilter filter, Instant from, Instant to, long seconds) {
        var params = new MapSqlParameterSource().addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to)).addValue("seconds", seconds);
        var where = new StringBuilder("occurred_at >= :from AND occurred_at < :to");
        equal(where, params, "severity", filter.severity());
        equal(where, params, "module", filter.module());
        equal(where, params, "action", filter.action());
        equal(where, params, "outcome", filter.outcome());
        equal(where, params, "correlation_id", filter.correlationId());
        // Epoch boundaries are UTC regardless of the database session time zone.
        return jdbc.query("SELECT floor(extract(epoch FROM occurred_at) / :seconds)::bigint AS bucket, "
                + "severity, module, count(*) AS events FROM observability_events WHERE " + where
                + " GROUP BY bucket, severity, module ORDER BY bucket, severity, module", params,
                (rs, row) -> new Aggregate(rs.getLong("bucket"), rs.getString("severity"),
                        rs.getString("module"), rs.getLong("events")));
    }

    private void equal(StringBuilder where, MapSqlParameterSource params, String column, Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = :").append(column);
            params.addValue(column, value);
        }
    }
}
