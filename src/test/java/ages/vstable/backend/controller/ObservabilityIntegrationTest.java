package ages.vstable.backend.controller;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.service.observability.ObservabilityRecorder;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT -> HTTP -> committed business write -> bounded recorder -> PostgreSQL -> admin API. */
@SpringBootTest(properties = "transfers.reconciliation.enabled=false")
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class ObservabilityIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("observability.enabled", () -> "true");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired AdministratorRepository administrators;
    @Autowired JwtTokenUtils tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObservabilityRecorder recorder;
    @Autowired ages.vstable.backend.service.observability.ObservabilityWriter writer;

    @Test
    void businessActionIsCommittedPersistedFilteredAndAvailableAsDetails() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var admin = administrators.save(AdministratorEntity.builder().fullName("Observability admin")
                .email("observability@example.test").passwordHash("synthetic-hash").passwordSalt("synthetic-salt")
                .createdAt(now).updatedAt(now).build());
        String bearer = "Bearer " + tokens.generateToken(admin);
        UUID correlation = UUID.randomUUID();

        mvc.perform(post("/v1/companies").header("Authorization", bearer).header("X-Request-ID", correlation)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"legalName":"Sensitive company name","cnpj":"11.222.333/0001-81","country":"Brasil",
                     "zipCode":"90000-000","city":"Porto Alegre","state":"RS"}
                    """)).andExpect(status().isCreated());
        // Drain deterministically; a worker may already have taken the batch.
        recorder.flush();
        UUID eventId = awaitEvent(correlation, "company.creation.completed");
        mvc.perform(get("/v1/admin/observability/events").header("Authorization", bearer)
                .param("correlationId", correlation.toString()).param("action", "company.creation.completed")
                .param("severity", "INFO").param("module", "company").param("outcome", "success")
                .param("from", Instant.now().minusSeconds(60).toString()).param("to", Instant.now().plusSeconds(60).toString())
                .param("size", "1").param("order", "asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorId").value(admin.getId().toString()))
                .andExpect(jsonPath("$.content[0].entityType").value("company"));
        var detail = mvc.perform(get("/v1/admin/observability/events/" + eventId).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correlationId").value(correlation.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("Sensitive company name", "11222333000181", "synthetic-hash", bearer);
        mvc.perform(get("/v1/admin/observability/events").header("Authorization", bearer)
                .param("correlationId", correlation.toString()).param("outcome", "failure"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        // A conflict must produce failure, never a second successful creation milestone.
        mvc.perform(post("/v1/companies").header("Authorization", bearer).header("X-Request-ID", correlation)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"legalName":"Sensitive company name","cnpj":"11.222.333/0001-81","country":"Brasil",
                     "zipCode":"90000-000","city":"Porto Alegre","state":"RS"}
                    """)).andExpect(status().isConflict());
        recorder.flush();
        awaitEvent(correlation, "http.request.completed", "failure");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observability_events WHERE correlation_id = ? AND action = 'company.creation.completed'", Long.class, correlation)).isEqualTo(1);

        // Same browser occurrence submitted twice is stored once and cannot impersonate a backend event.
        UUID browserId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + browserId + "\",\"correlationId\":\"" + correlation
                + "\",\"kind\":\"UI_ERROR\",\"module\":\"TRANSFERS\",\"actorId\":\"forged\",\"password\":\"discard-me\"}";
        for (int i = 0; i < 2; i++) mvc.perform(post("/v1/observability/browser-events").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        recorder.flush();
        awaitEvent(correlation, "browser.ui_error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observability_events WHERE actor_id = ? AND client_event_id = ?", Long.class, admin.getId(), browserId)).isEqualTo(1);
        String metadata = jdbc.queryForObject("SELECT metadata::text FROM observability_events WHERE client_event_id = ?", String.class, browserId);
        assertThat(metadata).isEqualTo("{\"trusted\": false}");
        mvc.perform(get("/v1/admin/observability/events").header("Authorization", bearer)
                .param("correlationId", correlation.toString()).param("severity", "ERROR").param("module", "transfer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.failuresByModule[0].failures").value(1));

        // The dashboard consumes the very same real business, HTTP and browser events.
        var dashboard = dashboard(bearer, correlation, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), null, null, 1);
        long persisted = jdbc.queryForObject("SELECT count(*) FROM observability_events WHERE correlation_id = ?", Long.class, correlation);
        assertThat(dashboard.at("/summary/total").asLong()).isEqualTo(persisted);
        assertThat(dashboard.at("/events/totalElements").asLong()).isEqualTo(persisted);
        assertThat(dashboard.at("/events/content").size()).isEqualTo(1);
        assertThat(dashboard.at("/summary/warnings").asLong()).isPositive();
        assertThat(dashboard.at("/summary/infos").asLong()).isPositive();
        assertThat(dashboard.at("/summary/errors").asLong()).isPositive();
        long seriesTotal = 0;
        for (var bucket : dashboard.at("/summary/series"))
            seriesTotal += bucket.get("error").asLong() + bucket.get("warn").asLong() + bucket.get("info").asLong();
        assertThat(seriesTotal).isEqualTo(persisted);
        var browserDashboard = dashboard(bearer, correlation, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), "ERROR", "transfer", 25);
        assertThat(browserDashboard.at("/summary/total").asLong()).isEqualTo(1);
        assertThat(browserDashboard.at("/summary/errorsByModule/0/module").asText()).isEqualTo("transfer");
        mvc.perform(get("/v1/admin/observability/events/dashboard").header("Authorization", bearer)
                .param("correlationId", correlation.toString()).param("action", "company.creation.completed")
                .param("outcome", "success").param("severity", "INFO").param("module", "company"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.total").value(1))
                .andExpect(jsonPath("$.summary.infos").value(1)).andExpect(jsonPath("$.events.totalElements").value(1));
        mvc.perform(get("/v1/admin/observability/events/dashboard").header("Authorization", bearer)
                .param("correlationId", correlation.toString()).param("action", "company.creation.completed")
                .param("outcome", "failure"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.total").value(0))
                .andExpect(jsonPath("$.events.totalElements").value(0));

        // Retained history is bounded and expired events are invisible before physical cleanup.
        jdbc.update("UPDATE observability_events SET occurred_at = ? WHERE id = ?", now.minusDays(31), eventId);
        mvc.perform(get("/v1/admin/observability/events/" + eventId).header("Authorization", bearer)).andExpect(status().isNotFound());
        writer.purge(Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observability_events WHERE id = ?", Long.class, eventId)).isZero();
    }

    @Test
    void dashboardFillsUtcBucketsAndHonorsBoundariesFiltersAndPagination() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var admin = administrators.save(AdministratorEntity.builder().fullName("Dashboard admin")
                .email("dashboard@example.test").passwordHash("synthetic-hash").passwordSalt("synthetic-salt")
                .createdAt(now).updatedAt(now).build());
        String bearer = "Bearer " + tokens.generateToken(admin);
        UUID correlation = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        insertEvent(correlation, start, "ERROR", "transfer");
        insertEvent(correlation, start.plusSeconds(30), "ERROR", "transfer");
        insertEvent(correlation, start.plusSeconds(120), "WARN", "transfer");
        insertEvent(correlation, start.plusSeconds(120), "INFO", "company");
        insertEvent(correlation, start.plusSeconds(180), "ERROR", "company"); // exclusive end
        var result = dashboard(bearer, correlation, start, start.plusSeconds(180), null, null, 1);
        assertThat(result.at("/summary/interval").asText()).isEqualTo("minute");
        assertThat(result.at("/summary/total").asLong()).isEqualTo(4);
        assertThat(result.at("/events/totalPages").asInt()).isEqualTo(4);
        assertThat(result.at("/summary/series").size()).isEqualTo(3);
        assertThat(result.at("/summary/series/0/error").asLong()).isEqualTo(2);
        assertThat(result.at("/summary/series/1/error").asLong()).isZero();
        assertThat(result.at("/summary/series/1/warn").asLong()).isZero();
        assertThat(result.at("/summary/series/1/info").asLong()).isZero();
        assertThat(result.at("/summary/series/2/warn").asLong()).isEqualTo(1);
        assertThat(result.at("/summary/series/2/info").asLong()).isEqualTo(1);
        assertThat(result.at("/summary/errorsByModule/0/errors").asLong()).isEqualTo(2);
        var point = dashboard(bearer, correlation, start, start.plusSeconds(60), "ERROR", "transfer", 25);
        assertThat(point.at("/events/totalElements").asLong()).isEqualTo(2);
        assertThat(point.at("/summary/errors").asLong()).isEqualTo(2);
        var hourly = dashboard(bearer, correlation, start, start.plusSeconds(86400), null, null, 1);
        assertThat(hourly.at("/summary/interval").asText()).isEqualTo("hour");
        assertThat(hourly.at("/summary/series").size()).isEqualTo(24);
        var daily = dashboard(bearer, correlation, start, start.plusSeconds(7 * 86400), null, null, 1);
        assertThat(daily.at("/summary/interval").asText()).isEqualTo("day");
        assertThat(daily.at("/summary/series").size()).isBetween(7, 8);
        var empty = dashboard(bearer, UUID.randomUUID(), start, start.plusSeconds(180), null, null, 25);
        assertThat(empty.at("/summary/total").asLong()).isZero();
        assertThat(empty.at("/summary/series").size()).isEqualTo(3);
        for (Instant invalidEnd : new Instant[]{ start, start.minusSeconds(1), start.plusSeconds(91 * 86400L) })
            mvc.perform(get("/v1/admin/observability/events/dashboard").header("Authorization", bearer)
                    .param("from", start.toString()).param("to", invalidEnd.toString())).andExpect(status().isBadRequest());
        var expired = dashboard(bearer, correlation, start.minusSeconds(40 * 86400L), start.minusSeconds(39 * 86400L), null, null, 25);
        assertThat(expired.at("/summary/total").asLong()).isZero();
        assertThat(expired.at("/summary/series").size()).isZero();
    }

    private com.fasterxml.jackson.databind.JsonNode dashboard(String bearer, UUID correlation, Instant from, Instant to,
            String severity, String module, int size) throws Exception {
        var request = get("/v1/admin/observability/events/dashboard").header("Authorization", bearer)
                .param("correlationId", correlation.toString()).param("from", from.toString())
                .param("to", to.toString()).param("size", String.valueOf(size));
        if (severity != null) request.param("severity", severity);
        if (module != null) request.param("module", module);
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    }

    private void insertEvent(UUID correlation, Instant at, String severity, String module) {
        jdbc.update("INSERT INTO observability_events (id, occurred_at, severity, module, action, outcome, failed, message, correlation_id, source) "
                + "VALUES (?, ?, ?, ?, 'http.request.completed', 'success', false, 'Application event', ?, 'backend')",
                UUID.randomUUID(), java.sql.Timestamp.from(at), severity, module, correlation);
    }

    private UUID awaitEvent(UUID correlation, String action) { return awaitEvent(correlation, action, null); }
    private UUID awaitEvent(UUID correlation, String action, String outcome) {
        // Await asynchronous persistence without arbitrary sleeps or provider calls.
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            var ids = jdbc.queryForList("SELECT id FROM observability_events WHERE correlation_id = ? AND action = ?"
                    + (outcome == null ? "" : " AND outcome = 'failure'"), UUID.class, correlation, action);
            if (!ids.isEmpty()) return ids.getFirst();
            Thread.yield();
        }
        throw new AssertionError("Event not persisted: " + action);
    }
}
