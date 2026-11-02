package ages.vstable.backend.repository;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AdministratorRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AdministratorRepository administratorRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM companies");
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
        assertThat(encontrado.get().getRequire2fa()).isTrue();
        assertThat(encontrado.get().getTwoFactorEnabled()).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_level::text FROM administrators WHERE id = ?", String.class, salvo.getId()))
                .isEqualTo("SUPER_ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM administrators WHERE id = ?", Boolean.class, salvo.getId()))
                .isTrue();
    }

    @Test
    void save_administradorCom2fa_persisteSegredoCriptografadoNoBancoEDescriptografaAoLer() {
        // Arrange
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String rawTotpSecret = "JBSWY3DPEHPK3PXP";

        AdministratorEntity admin = administrador("admin2fa@example.com", now);
        admin.setRequire2fa(true);
        admin.setTwoFactorEnabled(true);
        admin.setTwoFactorSecret(rawTotpSecret);

        // Act
        AdministratorEntity salvo = administratorRepository.saveAndFlush(admin);

        // Assert - leitura via JPA deve descriptografar de forma transparente
        Optional<AdministratorEntity> encontrado = administratorRepository.findById(salvo.getId());
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getTwoFactorSecret()).isEqualTo(rawTotpSecret);
        assertThat(encontrado.get().getTwoFactorEnabled()).isTrue();
        assertThat(encontrado.get().getRequire2fa()).isTrue();

        // Assert - leitura direta no PostgreSQL deve retornar o valor criptografado (não texto puro)
        String rawColumnInDb = jdbcTemplate.queryForObject(
                "SELECT two_factor_secret FROM administrators WHERE id = ?", String.class, salvo.getId());
        assertThat(rawColumnInDb)
                .isNotNull()
                .isNotEqualTo(rawTotpSecret);
    }

    @Test
    void save_usuarioCom2fa_persisteSegredoCriptografadoNoBancoEDescriptografaAoLer() {
        // Arrange
        UUID companyId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO companies (id, legal_name, cnpj) VALUES (?, 'Empresa 2FA Ltda', '12.345.678/0001-99')",
                companyId);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String rawTotpSecret = "KRSXG5CTMVRXEZLU";

        UserEntity user = UserEntity.builder()
                .companyId(companyId)
                .fullName("João Silva")
                .email("joao2fa@example.com")
                .passwordHash("hash-bcrypt")
                .passwordSalt("salt-bcrypt")
                .twoFactorSecret(rawTotpSecret)
                .twoFactorEnabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // Act
        UserEntity salvo = userRepository.saveAndFlush(user);

        // Assert - leitura via JPA deve descriptografar de forma transparente
        Optional<UserEntity> encontrado = userRepository.findById(salvo.getId());
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getTwoFactorSecret()).isEqualTo(rawTotpSecret);
        assertThat(encontrado.get().getTwoFactorEnabled()).isTrue();

        // Assert - leitura direta no PostgreSQL deve retornar o valor criptografado
        String rawColumnInDb = jdbcTemplate.queryForObject(
                "SELECT two_factor_secret FROM users WHERE id = ?", String.class, salvo.getId());
        assertThat(rawColumnInDb)
                .isNotNull()
                .isNotEqualTo(rawTotpSecret);
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
        administrator.setActive(true);
        administrator.setAccessLevel(ages.vstable.backend.entity.enums.AdminAccessLevel.SUPER_ADMIN);
        administrator.setCreatedAt(now);
        administrator.setUpdatedAt(now);
        return administrator;
    }
}