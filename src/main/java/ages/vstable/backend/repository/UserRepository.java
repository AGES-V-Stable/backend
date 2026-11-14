package ages.vstable.backend.repository;

import ages.vstable.backend.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    boolean existsByEmail(String email);

    Optional<UserEntity> findByEmail(String email);

    /** O e-mail do convite é normalizado em minúsculas; a conta pode ter sido cadastrada com outra caixa. */
    Optional<UserEntity> findFirstByEmailIgnoreCase(String email);

    Optional<UserEntity> findFirstByCompanyIdOrderByCreatedAtAsc(UUID companyId);
}
