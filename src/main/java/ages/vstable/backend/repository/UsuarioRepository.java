package ages.vstable.backend.repository;

import ages.vstable.backend.entity.UsuarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<UsuarioEntity, UUID> {
    Optional<UsuarioEntity> findByCpf(String cpf);

    boolean existsByCpf(String cpf);
}
