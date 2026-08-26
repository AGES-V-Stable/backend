package ages.vstable.backend.repository;

import ages.vstable.backend.entity.EmpresaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmpresaRepository extends JpaRepository<EmpresaEntity, UUID> {
    Optional<EmpresaEntity> findByCnpj(String cnpj);

    boolean existsByCnpj(String cnpj);
}