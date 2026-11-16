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

        // Retained history is bounded and expired events are invisible before physical cleanup.
        jdbc.update("UPDATE observability_events SET occurred_at = ? WHERE id = ?", now.minusDays(31), eventId);
        mvc.perform(get("/v1/admin/observability/events/" + eventId).header("Authorization", bearer)).andExpect(status().isNotFound());
        writer.purge(Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observability_events WHERE id = ?", Long.class, eventId)).isZero();
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
