package ages.vstable.backend.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.external.ai.SupportChatClient;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Caracterização de saturação de pool HikariCP (perfil lambda: maximum-pool-size=2).
 * PASS comprova exaustão local controlada e retorno HTTP 500 INTERNAL_ERROR em vez de 503.
 */
@Tag("pentest-impact")
@SpringBootTest(properties = {
        "transfers.reconciliation.enabled=false",
        "logging.level.root=ERROR",
        "avenia.base-url=http://127.0.0.1:0",
        "blindpay.base-url=http://127.0.0.1:0",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=500"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Testcontainers
class AvailabilityPoolImpactAssessmentTest extends ImpactEvidence {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdministratorRepository admins;
    @Autowired JwtTokenUtils jwt;
    @MockitoBean AveniaClient avenia;
    @MockitoBean BlindPayClient blindpay;
    @MockitoBean SupportChatClient supportChat;
    @MockitoBean BlindPayProvisioningService provisioning;

    private String adminToken;

    @BeforeEach
    void arrange() {
        assertThat(postgres.getJdbcUrl()).contains("jdbc:postgresql:");
        jdbc.execute("TRUNCATE companies, administrators, users, blindpay_webhook_events CASCADE");
        var admin = admins.saveAndFlush(AdministratorEntity.builder()
                .fullName("Admin Sintetico")
                .email(UUID.randomUUID() + "@example.test")
                .passwordHash("unused")
                .passwordSalt("synthetic")
                .accessLevel(AdminAccessLevel.SUPER_ADMIN)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());
        adminToken = jwt.generateToken(admin);
    }

    @Test
    @DisplayName("P86-28 DOS-01 saturacao de pool HikariCP (size=2) gera HTTP 500 INTERNAL_ERROR sem 503")
    void hikariPoolExhaustionReturnsHttp500InsteadOf503() throws Exception {
        CountDownLatch connectionsHeld = new CountDownLatch(2);
        CountDownLatch releaseConnections = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        List<String> errorCodes = new ArrayList<>();

        try (var holders = Executors.newFixedThreadPool(2);
             var callers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> heldFutures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                heldFutures.add(holders.submit(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
                        stmt.execute();
                        connectionsHeld.countDown();
                        releaseConnections.await(10, TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }

            assertThat(connectionsHeld.await(5, TimeUnit.SECONDS)).isTrue();

            try {
                List<Future<Map.Entry<Integer, String>>> responses = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    final int idx = i;
                    responses.add(callers.submit(() -> {
                        var res = mvc.perform(post("/v1/auth/login")
                                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content("{\"email\":\"pool-" + idx + "@example.test\",\"password\":\"WrongPass1!\"}"))
                                .andReturn().getResponse();
                        String body = res.getContentAsString();
                        String code = "";
                        if (!body.isBlank() && body.trim().startsWith("{")) {
                            JsonNode node = JSON.readTree(body);
                            code = node.path("code").asText("");
                        }
                        return Map.entry(res.getStatus(), code);
                    }));
                }

                for (Future<Map.Entry<Integer, String>> future : responses) {
                    var entry = future.get(10, TimeUnit.SECONDS);
                    statuses.add(entry.getKey());
                    if (!entry.getValue().isBlank()) {
                        errorCodes.add(entry.getValue());
                    }
                }

                // Requisição autenticada falha ainda no JwtTokenFilter antes do GlobalExceptionHandler.
                assertThatThrownBy(() -> mvc.perform(get("/v1/companies")
                                .header("Authorization", "Bearer " + adminToken)))
                        .hasRootCauseInstanceOf(java.sql.SQLTransientConnectionException.class);
            } finally {
                releaseConnections.countDown();
            }

            for (Future<?> heldFuture : heldFutures) {
                heldFuture.get(10, TimeUnit.SECONDS);
            }
        }

        long count500 = statuses.stream().filter(s -> s == 500).count();
        long count503 = statuses.stream().filter(s -> s == 503).count();
        long count429 = statuses.stream().filter(s -> s == 429).count();

        assertThat(count500).isGreaterThanOrEqualTo(1);
        assertThat(count503).isZero();
        assertThat(count429).isZero();
        assertThat(errorCodes).contains("INTERNAL_ERROR");
        verifyNoInteractions(avenia, blindpay, supportChat, provisioning);

        observe("poolConfig", Map.of("maximumPoolSize", 2, "connectionTimeoutMs", 500));
        observe("resultado", Map.of(
                "requisicoesConcorrentes", 4,
                "statuses", statuses,
                "http500", count500,
                "http503", count503,
                "http429", count429,
                "errorCodes", errorCodes
        ));
    }
}
