package ages.vstable.backend.repository;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.enums.MembershipStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembershipEntity, UUID> {

    /** Vínculo do usuário na situação informada; entre removidos, o encerrado mais recentemente. */
    Optional<CompanyMembershipEntity> findFirstByUserIdAndStatusOrderByRemovedAtDesc(UUID userId, MembershipStatus status);

    List<CompanyMembershipEntity> findByCompanyIdAndStatus(UUID companyId, MembershipStatus status);

    /** Serializa alteracoes de perfil e revogacoes concorrentes do mesmo vinculo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from CompanyMembershipEntity m where m.id = :memberId and m.companyId = :companyId")
    Optional<CompanyMembershipEntity> findForUpdate(@Param("memberId") UUID memberId,
                                                    @Param("companyId") UUID companyId);
}
