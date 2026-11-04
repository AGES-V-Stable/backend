package ages.vstable.backend.service;

import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Service
public class DatabaseMigrationService {
    private final DataSource dataSource;

    public DatabaseMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
