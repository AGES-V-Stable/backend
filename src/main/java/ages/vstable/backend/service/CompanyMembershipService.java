package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.CompanyAccessResponse;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyMembershipService {

    private final CompanyMembershipRepository membershipRepository;

    /** Atribui a gestão da empresa ao usuário que a cadastrou. */
    @Transactional
    public CompanyMembershipEntity assignOwner(UUID userId, UUID companyId, OffsetDateTime now) {
        CompanyMembershipEntity membership = CompanyMembershipEntity.builder()
                .companyId(companyId)
                .userId(userId)
                .role(CompanyRole.OWNER)
                .status(MembershipStatus.ACTIVE)
                .joinedAt(now)
                .updatedAt(now)
                .build();
        return membershipRepository.saveAndFlush(membership);
    }

    /**
     * Vínculo atual do usuário: o ativo ou, na falta dele, o último removido, que só serve para
     * informar a perda de acesso. Vazio quando o usuário nunca teve vínculo.
     */
    @Transactional(readOnly = true)
    public Optional<CompanyMembershipEntity> findCurrentMembership(UUID userId) {
        return membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(userId, MembershipStatus.ACTIVE)
                .or(() -> membershipRepository.findFirstByUserIdAndStatusOrderByRemovedAtDesc(
                        userId, MembershipStatus.REMOVED));
    }

    /**
     * Empresa vigente de quem já carrega o vínculo atual (carregado a cada requisição): a do vínculo
     * ativo; nenhuma quando o vínculo foi removido; a gravada na conta enquanto o usuário nunca teve vínculo.
     */
    public static UUID currentCompanyId(UserEntity user) {
        CompanyMembershipEntity membership = user.getMembership();
        if (membership == null) {
            return user.getCompanyId();
        }
        return membership.getStatus() == MembershipStatus.ACTIVE ? membership.getCompanyId() : null;
    }

    /** Perfil e capacidades do vínculo, calculados pelo perfil e pela situação atual. */
    public static CompanyAccessResponse toAccessResponse(CompanyMembershipEntity membership) {
        return new CompanyAccessResponse(
                membership.getId(),
                membership.getCompanyId(),
                membership.getRole(),
                membership.getStatus(),
                membership.getRole().permissionsFor(membership.getStatus()),
                membership.getVersion());
    }
}
