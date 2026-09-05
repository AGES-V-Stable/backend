package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProgressoCadastroEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProgressoCadastroRepository
        extends JpaRepository<ProgressoCadastroEntity, UUID> {
}
