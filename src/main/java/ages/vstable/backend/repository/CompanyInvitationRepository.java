package ages.vstable.backend.repository;

import ages.vstable.backend.entity.CompanyInvitationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanyInvitationRepository extends JpaRepository<CompanyInvitationEntity, UUID> {
}
