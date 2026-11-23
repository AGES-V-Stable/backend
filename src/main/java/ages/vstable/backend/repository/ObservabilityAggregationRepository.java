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
                .addValue("to", Timestamp.from(to)).addValue("seconds", seconds)
                .addValue("severity", filter.severity()).addValue("module", filter.module())
                .addValue("action", filter.action()).addValue("outcome", filter.outcome())
                .addValue("correlationId", filter.correlationId());
        // Epoch boundaries are UTC regardless of the database session time zone.
        // Casts let PostgreSQL resolve the type of each optional parameter even when it is null.
        return jdbc.query("""
                SELECT floor(extract(epoch FROM occurred_at) / :seconds)::bigint AS bucket,
                       severity, module, count(*) AS events
                FROM observability_events
                WHERE occurred_at >= :from AND occurred_at < :to
                  AND (CAST(:severity AS varchar) IS NULL OR severity = :severity)
                  AND (CAST(:module AS varchar) IS NULL OR module = :module)
                  AND (CAST(:action AS varchar) IS NULL OR action = :action)
                  AND (CAST(:outcome AS varchar) IS NULL OR outcome = :outcome)
                  AND (CAST(:correlationId AS uuid) IS NULL OR correlation_id = :correlationId)
                GROUP BY bucket, severity, module
                ORDER BY bucket, severity, module
                """, params,
                (rs, row) -> new Aggregate(rs.getLong("bucket"), rs.getString("severity"),
                        rs.getString("module"), rs.getLong("events")));
    }
}
