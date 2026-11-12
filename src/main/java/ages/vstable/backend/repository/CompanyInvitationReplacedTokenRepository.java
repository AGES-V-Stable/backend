package ages.vstable.backend.repository;

import ages.vstable.backend.entity.CompanyInvitationReplacedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CompanyInvitationReplacedTokenRepository
        extends JpaRepository<CompanyInvitationReplacedTokenEntity, UUID> {

    /** Substituições do convite depois do instante dado; cada uma marca a geração de um novo link. */
    List<CompanyInvitationReplacedTokenEntity> findByInvitationIdAndReplacedAtAfter(UUID invitationId,
                                                                                     OffsetDateTime after);
}
