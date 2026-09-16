package ages.vstable.backend.repository;

import ages.vstable.backend.entity.UsuarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<UsuarioEntity, UUID> {
    boolean existsByEmail(String email);

    Optional<UsuarioEntity> findByEmailIgnoreCase(String email);
}
