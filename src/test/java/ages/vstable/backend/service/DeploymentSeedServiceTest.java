package ages.vstable.backend.service;

import ages.vstable.backend.dto.DeploymentSeedRequest;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.enums.AdminAccessLevel;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentSeedServiceTest {
    @Mock AdministratorRepository administrators;
    @Mock UserRepository users;
    @Mock SyntheticDataSeeder syntheticDataSeeder;

    private static final String ADMIN_PASSWORD = "long-initial-admin-password";
    private static final String DEMO_PASSWORD = "long-staging-demo-password";

    private DeploymentSeedService service(String environment) {
        return new DeploymentSeedService(administrators, users, syntheticDataSeeder,
                new BCryptPasswordEncoder(4), environment);
    }

    private DeploymentSeedRequest request(String demoPassword) {
        return new DeploymentSeedRequest(
                new DeploymentSeedRequest.Admin("Initial Admin", "admin@example.test", ADMIN_PASSWORD), demoPassword);
    }

    @Test
    void production_createsOnlySuperAdmin() {
        // Act
        service("production").seed(request(null));

        // Assert
        ArgumentCaptor<AdministratorEntity> captor = ArgumentCaptor.forClass(AdministratorEntity.class);
        verify(administrators).saveAndFlush(captor.capture());
        AdministratorEntity admin = captor.getValue();
        assertThat(admin.getAccessLevel()).isEqualTo(AdminAccessLevel.SUPER_ADMIN);
        assertThat(admin.getEmail()).isEqualTo("admin@example.test");
        assertThat(admin.isEnabled()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches(ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
        verifyNoInteractions(syntheticDataSeeder);
    }

    @Test
    void staging_createsDemoRecordsWithoutSecondAdmin() {
        // Act
        service("staging").seed(request(DEMO_PASSWORD));

        // Assert
        verify(administrators).saveAndFlush(any());
        verify(syntheticDataSeeder).seed(DEMO_PASSWORD, false);
    }

    @Test
    void existingAdmin_keepsCredentialsAndCompletesStagingData() {
        // Arrange
        when(administrators.findByEmail("admin@example.test")).thenReturn(Optional.of(
                AdministratorEntity.builder().accessLevel(AdminAccessLevel.SUPER_ADMIN).active(true).build()));

        // Act
        service("staging").seed(request(DEMO_PASSWORD));

        // Assert
        verify(administrators, never()).saveAndFlush(any());
        verify(syntheticDataSeeder).seed(DEMO_PASSWORD, false);
    }

    @Test
    void changedAdminIdentity_failsBeforeWrites() {
        // Arrange
        when(administrators.count()).thenReturn(1L);

        // Act / Assert
        assertThatThrownBy(() -> service("production").seed(request(null)))
                .isInstanceOf(ConflictException.class);
        verify(administrators, never()).saveAndFlush(any());
    }

    @Test
    void existingAdminWithoutSuperAdminAccess_fails() {
        when(administrators.findByEmail("admin@example.test")).thenReturn(Optional.of(
                AdministratorEntity.builder().accessLevel(AdminAccessLevel.SUPPORT).active(true).build()));

        assertThatThrownBy(() -> service("production").seed(request(null)))
                .isInstanceOf(ConflictException.class);
        verify(administrators, never()).saveAndFlush(any());
    }

    @Test
    void userEmailCollision_failsBeforeWrites() {
        // Arrange
        when(users.existsByEmail("admin@example.test")).thenReturn(true);

        // Act / Assert
        assertThatThrownBy(() -> service("production").seed(request(null)))
                .isInstanceOf(ConflictException.class);
        verify(administrators, never()).saveAndFlush(any());
    }

    @Test
    void stagingDemoIdentityCollision_failsBeforeWrites() {
        DeploymentSeedRequest collision = new DeploymentSeedRequest(
                new DeploymentSeedRequest.Admin("Initial Admin", "dev.user@example.test", ADMIN_PASSWORD),
                DEMO_PASSWORD);

        assertThatThrownBy(() -> service("staging").seed(collision))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(administrators, users, syntheticDataSeeder);
    }

    @Test
    void invalidPasswordsAndProductionDemoData_failBeforeWrites() {
        assertThatThrownBy(() -> service("production").seed(new DeploymentSeedRequest(
                new DeploymentSeedRequest.Admin("Initial Admin", "admin@example.test", "short"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service("staging").seed(request(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service("production").seed(request(DEMO_PASSWORD)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(administrators, users, syntheticDataSeeder);
    }
}
