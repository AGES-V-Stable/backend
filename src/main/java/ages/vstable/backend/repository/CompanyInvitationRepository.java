package ages.vstable.backend.repository;

import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.enums.InvitationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyInvitationRepository extends JpaRepository<CompanyInvitationEntity, UUID> {

    List<CompanyInvitationEntity> findByCompanyIdAndStatus(UUID companyId, InvitationStatus status);

    /** Convite da empresa; um id de outra empresa não é encontrado. */
    Optional<CompanyInvitationEntity> findByIdAndCompanyId(UUID id, UUID companyId);

    /** Convite do e-mail na situação dada, sem diferenciar maiúsculas, como o índice único dos pendentes. */
    Optional<CompanyInvitationEntity> findFirstByCompanyIdAndStatusAndEmailIgnoreCase(
            UUID companyId, InvitationStatus status, String email);

    /** Serializa regeneração e revogação concorrentes do mesmo convite. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CompanyInvitationEntity i where i.id = :id and i.companyId = :companyId")
    Optional<CompanyInvitationEntity> findForUpdate(UUID id, UUID companyId);

    /** Convite cujo link vigente tem este hash; links substituídos não são encontrados aqui. */
    Optional<CompanyInvitationEntity> findByTokenHash(String tokenHash);

    /**
     * Mesmo que {@link #findByTokenHash}, travando a linha: o aceite se enfileira com regenerações e revogações
     * do mesmo convite. Se uma regeneração vencer a corrida, o hash antigo deixa de corresponder à linha.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CompanyInvitationEntity i where i.tokenHash = :tokenHash")
    Optional<CompanyInvitationEntity> findByTokenHashForUpdate(String tokenHash);
}
