package ages.vstable.backend.service;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InitialAdministratorSeederTest {
    @Mock
    private AdministratorRepository administrators;
    @Mock
    private UserRepository users;

    @Test
    void emptyDatabase_createsOnlyTheFirstAdministrator() {
        var encoder = new BCryptPasswordEncoder(4);
        var seeder = new InitialAdministratorSeeder(administrators, users, encoder);
        String password = "temporary-secret-123";

        boolean created = seeder.seed("First Admin", "admin@example.test", password);

        assertThat(created).isTrue();
        ArgumentCaptor<AdministratorEntity> saved = ArgumentCaptor.forClass(AdministratorEntity.class);
        verify(administrators).saveAndFlush(saved.capture());
        AdministratorEntity admin = saved.getValue();
        assertThat(admin.getFullName()).isEqualTo("First Admin");
        assertThat(admin.getEmail()).isEqualTo("admin@example.test");
        assertThat(encoder.matches(password, admin.getPasswordHash())).isTrue();
        assertThat(admin.getPasswordHash()).isNotEqualTo(password);
        assertThat(admin.getPasswordSalt()).startsWith("$2");
        assertThat(admin.getAccessLevel()).isEqualTo(AdminAccessLevel.SUPER_ADMIN);
        assertThat(admin.getActive()).isTrue();
        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getUpdatedAt()).isNotNull();
    }

    @Test
    void existingAdministrator_skipsWithoutChangingCredentials() {
        when(administrators.count()).thenReturn(1L);
        var seeder = new InitialAdministratorSeeder(administrators, users, new BCryptPasswordEncoder(4));

        boolean created = seeder.seed("Other Admin", "other@example.test", "another-secret-123");

        assertThat(created).isFalse();
        verifyNoInteractions(users);
    }

    @Test
    void emailUsedByCompanyUser_rejectsTheBootstrap() {
        when(users.existsByEmail("admin@example.test")).thenReturn(true);
        var seeder = new InitialAdministratorSeeder(administrators, users, new BCryptPasswordEncoder(4));

        assertThatThrownBy(() -> seeder.seed("First Admin", "admin@example.test", "temporary-secret-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
    }
}
