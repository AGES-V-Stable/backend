package ages.vstable.backend.repository;

import ages.vstable.backend.entity.CompanyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<CompanyEntity, UUID> {
    Optional<CompanyEntity> findByCnpj(String cnpj);

    boolean existsByCnpj(String cnpj);
}