package ages.vstable.backend.repository;

import ages.vstable.backend.entity.AdministratorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdministratorRepository extends JpaRepository<AdministratorEntity, UUID> {
    Optional<AdministratorEntity> findByEmail(String email);
}
