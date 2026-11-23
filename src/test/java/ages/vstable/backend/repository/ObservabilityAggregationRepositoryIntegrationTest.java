package ages.vstable.backend.repository;

import ages.vstable.backend.dto.observability.EventFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ObservabilityAggregationRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(60);
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static JdbcTemplate jdbc;
    private static ObservabilityAggregationRepository repository;

    @BeforeAll
    static void createSchema() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V21__add_observability_events.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new ObservabilityAggregationRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    @BeforeEach
    void insertEvents() {
        jdbc.execute("TRUNCATE observability_events");
        insert(FROM, "ERROR", "transfer", "completed", "failure", CORRELATION);
        insert(FROM, "ERROR", "transfer", "other", "failure", CORRELATION);
        insert(FROM, "ERROR", "transfer", "completed", "success", CORRELATION);
        insert(FROM, "WARN", "transfer", "completed", "failure", CORRELATION);
        insert(FROM, "ERROR", "company", "completed", "failure", CORRELATION);
        insert(FROM, "ERROR", "transfer", "completed", "failure", UUID.randomUUID());
        insert(FROM, "ERROR", "transfer", "completed", "failure", null);
        insert(FROM.minusSeconds(1), "ERROR", "transfer", "completed", "failure", CORRELATION);
        insert(TO, "ERROR", "transfer", "completed", "failure", CORRELATION);
    }

    @Test
    void aggregate_withoutFilters_includesNullCorrelationsAndHonorsTimeBoundsAndGrouping() {
        var result = repository.aggregate(filter(null, null, null, null, null), FROM, TO, 60);

        long bucket = FROM.getEpochSecond() / 60;
        assertThat(result).containsExactly(
                new ObservabilityAggregationRepository.Aggregate(bucket, "ERROR", "company", 1),
                new ObservabilityAggregationRepository.Aggregate(bucket, "ERROR", "transfer", 5),
                new ObservabilityAggregationRepository.Aggregate(bucket, "WARN", "transfer", 1));
    }

    @ParameterizedTest
    @MethodSource("filters")
    void aggregate_appliesEachOptionalFilterAndTheirCombination(EventFilter filter, long expected) {
        var result = repository.aggregate(filter, FROM, TO, 60);

        assertThat(result.stream().mapToLong(ObservabilityAggregationRepository.Aggregate::events).sum())
                .isEqualTo(expected);
    }

    static Stream<Arguments> filters() {
        String injection = "' OR 1=1 --";
        return Stream.of(
                Arguments.of(filter("ERROR", null, null, null, null), 6L),
                Arguments.of(filter(null, "transfer", null, null, null), 6L),
                Arguments.of(filter(null, null, "completed", null, null), 6L),
                Arguments.of(filter(null, null, null, "failure", null), 6L),
                Arguments.of(filter(null, null, null, null, CORRELATION), 5L),
                Arguments.of(filter("ERROR", "transfer", "completed", "failure", CORRELATION), 1L),
                Arguments.of(filter(injection, null, null, null, null), 0L),
                Arguments.of(filter(null, injection, null, null, null), 0L),
                Arguments.of(filter(null, null, injection, null, null), 0L),
                Arguments.of(filter(null, null, null, injection, null), 0L));
    }

    private static EventFilter filter(String severity, String module, String action, String outcome, UUID correlation) {
        return new EventFilter(null, null, severity, module, action, outcome, correlation, null, null, null);
    }

    private void insert(Instant at, String severity, String module, String action, String outcome, UUID correlation) {
        jdbc.update("""
                INSERT INTO observability_events
                    (id, occurred_at, severity, module, action, outcome, failed, message, correlation_id, source)
                VALUES (?, ?, ?, ?, ?, ?, false, 'Application event', ?, 'backend')
                """, UUID.randomUUID(), Timestamp.from(at), severity, module, action, outcome, correlation);
    }
}
