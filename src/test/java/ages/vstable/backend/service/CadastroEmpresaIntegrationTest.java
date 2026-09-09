package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.CadastroEmpresaRequest;
import ages.vstable.backend.dto.empresa.CadastroEmpresaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class CadastroEmpresaIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CadastroEmpresaService cadastroEmpresaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM progresso_cadastros");
        jdbcTemplate.update("DELETE FROM usuarios");
        jdbcTemplate.update("DELETE FROM empresas");
    }

    @Test
    void create_persisteTodosOsVinculosComMigrationReal() {
        UUID usuarioId = UUID.randomUUID();
        UUID progressoId = UUID.randomUUID();
        insertUsuarioEProgresso(usuarioId, progressoId);

        CadastroEmpresaResponse response = cadastroEmpresaService.create(progressoId, requestValido());

        assertThat(response.getEtapaAtual()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT empresa_id FROM usuarios WHERE id = ?", UUID.class, usuarioId))
                .isEqualTo(response.getEmpresaId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT empresa_id FROM progresso_cadastros WHERE id = ?", UUID.class, progressoId))
                .isEqualTo(response.getEmpresaId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT etapa_atual FROM progresso_cadastros WHERE id = ?", Integer.class, progressoId))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cidade FROM empresas WHERE id = ?", String.class, response.getEmpresaId()))
                .isEqualTo("Porto Alegre");
    }

    @Test
    void create_falhaTardia_reverteEmpresaUsuarioEProgresso() {
        UUID usuarioId = UUID.randomUUID();
        UUID progressoId = UUID.randomUUID();
        insertUsuarioEProgresso(usuarioId, progressoId);
        createFailureTrigger();

        try {
            assertThatThrownBy(() -> cadastroEmpresaService.create(progressoId, requestValido()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            dropFailureTrigger();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM empresas WHERE cnpj = '11222333000181'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT empresa_id FROM usuarios WHERE id = ?", UUID.class, usuarioId))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT empresa_id FROM progresso_cadastros WHERE id = ?", UUID.class, progressoId))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT etapa_atual FROM progresso_cadastros WHERE id = ?", Integer.class, progressoId))
                .isEqualTo(2);
    }

    private void insertUsuarioEProgresso(UUID usuarioId, UUID progressoId) {
        jdbcTemplate.update("""
                INSERT INTO usuarios (id, nome_completo, email, hash_senha)
                VALUES (?, 'Representante Teste', ?, 'hash')
                """, usuarioId, usuarioId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO progresso_cadastros
                    (id, usuario_id, idempotency_key, payload_hash, etapa_atual, status_geral)
                VALUES (?, ?, ?, ?, 2, 'RASCUNHO')
                """, progressoId, usuarioId, UUID.randomUUID().toString(), "a".repeat(64));
    }

    private void createFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_empresa_onboarding_update()
                RETURNS trigger AS $$
                BEGIN
                    IF NEW.empresa_id IS NOT NULL THEN
                        RAISE EXCEPTION 'falha tardia simulada';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_empresa_onboarding_update
                BEFORE UPDATE ON progresso_cadastros
                FOR EACH ROW EXECUTE FUNCTION fail_empresa_onboarding_update()
                """);
    }

    private void dropFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_empresa_onboarding_update ON progresso_cadastros");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_empresa_onboarding_update()");
    }

    private CadastroEmpresaRequest requestValido() {
        CadastroEmpresaRequest request = new CadastroEmpresaRequest();
        request.setRazaoSocial("Empresa Exemplo Ltda");
        request.setPais("Brasil");
        request.setCnpj("11.222.333/0001-81");
        request.setCep("90000-000");
        request.setCidade("Porto Alegre");
        request.setEstado("RS");
        return request;
    }
}
