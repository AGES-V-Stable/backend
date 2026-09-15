package ages.vstable.backend.repository;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdministratorRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AdministratorRepository administratorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM administrators");
    }

    @Test
    void save_persisteEDevolveTodosOsCamposMapeados() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdministratorEntity administrator = new AdministratorEntity();
        administrator.setFullName("Maria Souza");
        administrator.setEmail("maria@example.com");
        administrator.setPasswordHash("hash-bcrypt");
        administrator.setPasswordSalt("salt-bcrypt");
        administrator.setCreatedAt(now);
        administrator.setUpdatedAt(now);

        AdministratorEntity salvo = administratorRepository.saveAndFlush(administrator);

        Optional<AdministratorEntity> encontrado = administratorRepository.findById(salvo.getId());
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getFullName()).isEqualTo("Maria Souza");
        assertThat(encontrado.get().getEmail()).isEqualTo("maria@example.com");
        assertThat(encontrado.get().getPasswordHash()).isEqualTo("hash-bcrypt");
        assertThat(encontrado.get().getPasswordSalt()).isEqualTo("salt-bcrypt");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_level::text FROM administrators WHERE id = ?", String.class, salvo.getId()))
                .isEqualTo("SUPER_ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM administrators WHERE id = ?", Boolean.class, salvo.getId()))
                .isTrue();
    }

    @Test
    void save_emailDuplicado_violaConstraintUnica() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        administratorRepository.saveAndFlush(administrador("duplicado@example.com", now));

        assertThatThrownBy(() -> administratorRepository.saveAndFlush(administrador("duplicado@example.com", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private AdministratorEntity administrador(String email, OffsetDateTime now) {
        AdministratorEntity administrator = new AdministratorEntity();
        administrator.setFullName("Admin Teste");
        administrator.setEmail(email);
        administrator.setPasswordHash("hash");
        administrator.setPasswordSalt("salt");
        administrator.setCreatedAt(now);
        administrator.setUpdatedAt(now);
        return administrator;
    }
}
