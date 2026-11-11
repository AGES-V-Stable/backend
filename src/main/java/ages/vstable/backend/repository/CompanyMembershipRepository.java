package ages.vstable.backend.repository;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.enums.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembershipEntity, UUID> {

    /** Vínculo do usuário na situação informada; entre removidos, o encerrado mais recentemente. */
    Optional<CompanyMembershipEntity> findFirstByUserIdAndStatusOrderByRemovedAtDesc(UUID userId, MembershipStatus status);

    List<CompanyMembershipEntity> findByCompanyIdAndStatus(UUID companyId, MembershipStatus status);
}
