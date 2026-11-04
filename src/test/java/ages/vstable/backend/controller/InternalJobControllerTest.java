package ages.vstable.backend.controller;

import ages.vstable.backend.service.DatabaseMigrationService;
import ages.vstable.backend.service.InitialAdministratorSeeder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalJobControllerTest {
    @Test
    void migration_reportsSuccessOnlyAfterFlywayReturns() {
        DatabaseMigrationService migration = mock(DatabaseMigrationService.class);

        var response = new MigrationJobController(migration).migrate();

        verify(migration).migrate();
        assertThat(response).containsEntry("status", "migrated");
    }

    @Test
    void seed_reportsCreationOrExistingAdministrator() {
        InitialAdministratorSeeder seeder = mock(InitialAdministratorSeeder.class);
        var request = new SeedJobController.InitialAdminRequest(
                "First Admin", "admin@example.test", "temporary-secret-123");
        when(seeder.seed(request.fullName(), request.email(), request.password()))
                .thenReturn(true, false);
        var controller = new SeedJobController(seeder);

        assertThat(controller.seed(request)).containsEntry("status", "seeded");
        assertThat(controller.seed(request)).containsEntry("status", "already_exists");
    }
}
