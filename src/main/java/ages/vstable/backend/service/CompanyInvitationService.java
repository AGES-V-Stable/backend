package ages.vstable.backend.service;

import ages.vstable.backend.dto.membership.GeneratedInvitationResponse;
import ages.vstable.backend.dto.membership.InvitationCreateRequest;
import ages.vstable.backend.dto.membership.InvitationResponse;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyInvitationReplacedTokenEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.GoneException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.PreconditionFailedException;
import ages.vstable.backend.exception.TooManyRequestsException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyInvitationReplacedTokenRepository;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.InvitationTokenCipher;
import ages.vstable.backend.utils.InvitationTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Convites de membros compartilhados por link, sem expiração por tempo: consulta, criação, regeneração e
 * revogação. Nenhuma operação envia e-mail, cria usuário ou vínculo, nem chama a Avenia ou a BlindPay.
 */
@Service
public class CompanyInvitationService {

    private static final String INVITE_PATH = "/convites/empresa#token=";

    private final CompanyInvitationRepository invitationRepository;
    private final CompanyInvitationReplacedTokenRepository replacedTokenRepository;
    private final CompanyMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;
    private final InvitationTokenCipher tokenCipher;
    private final InvitationTokens invitationTokens;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String frontendOrigin;

    @Autowired
    public CompanyInvitationService(
            CompanyInvitationRepository invitationRepository,
            CompanyInvitationReplacedTokenRepository replacedTokenRepository,
            CompanyMembershipRepository membershipRepository,
            UserRepository userRepository,
            AdministratorRepository administratorRepository,
            InvitationTokenCipher tokenCipher,
            InvitationTokens invitationTokens,
            PlatformTransactionManager transactionManager,
            @Value("${app.invitation.frontend-origin}") String frontendOrigin) {
        this(invitationRepository, replacedTokenRepository, membershipRepository, userRepository,
                administratorRepository, tokenCipher, invitationTokens, transactionManager, Clock.systemUTC(),
                frontendOrigin);
    }

    // Construtor visível para testes determinísticos (controle do tempo dos limites)
    CompanyInvitationService(
            CompanyInvitationRepository invitationRepository,
            CompanyInvitationReplacedTokenRepository replacedTokenRepository,
            CompanyMembershipRepository membershipRepository,
            UserRepository userRepository,
            AdministratorRepository administratorRepository,
            InvitationTokenCipher tokenCipher,
            InvitationTokens invitationTokens,
            PlatformTransactionManager transactionManager,
            Clock clock,
            String frontendOrigin) {
        this.invitationRepository = invitationRepository;
        this.replacedTokenRepository = replacedTokenRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.administratorRepository = administratorRepository;
        this.tokenCipher = tokenCipher;
        this.invitationTokens = invitationTokens;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.frontendOrigin = frontendOrigin.endsWith("/")
                ? frontendOrigin.substring(0, frontendOrigin.length() - 1)
                : frontendOrigin;
    }

    /** Metadados do convite, sem nenhum segredo; qualquer estado. */
    @Transactional(readOnly = true)
    public InvitationResponse find(UUID companyId, UUID invitationId) {
        return toResponse(findInvitation(companyId, invitationId));
    }

    /**
     * Link vigente de um convite pendente, para o proprietário copiar de novo. A leitura é idempotente:
     * não gera token, não muda a versão e não invalida cópias já compartilhadas.
     */
    @Transactional(readOnly = true)
    public GeneratedInvitationResponse findLink(UUID companyId, UUID invitationId) {
        CompanyInvitationEntity invitation = findInvitation(companyId, invitationId);
        switch (invitation.getStatus()) {
            case ACCEPTED -> throw new ConflictException("INVITATION_ALREADY_USED", "Este convite já foi aceito.");
            case REVOKED -> throw new GoneException("INVITATION_REVOKED", "Este convite foi revogado.");
            case PENDING -> {
                // segue: só o convite pendente guarda a cópia recuperável do token
            }
        }
        String token = tokenCipher.decrypt(invitation.getTokenCiphertext(), invitation.getTokenKeyVersion());
        return new GeneratedInvitationResponse(toResponse(invitation), inviteUrl(token));
    }

    /**
     * Cria um convite pendente para o e-mail e devolve o link. Não cria usuário, vínculo nem recurso financeiro,
     * e não altera uma conta que já exista com esse e-mail. Dois pedidos simultâneos para o mesmo e-mail
     * terminam em um convite e um conflito, porque o índice único dos pendentes decide a corrida.
     */
    public GeneratedInvitationResponse create(UUID companyId, UserEntity owner, InvitationCreateRequest request) {
        try {
            return transactions.execute(status -> createPending(companyId, owner, request));
        } catch (DataIntegrityViolationException ex) {
            throw conflictAfterRace(companyId, request.email(), ex);
        }
    }

    /**
     * Troca o link de um convite pendente por outro, que não expira, e invalida o anterior na mesma transação.
     * A linha do convite fica travada, então regenerações e revogações concorrentes se enfileiram e só a
     * primeira vale para a versão informada.
     */
    @Transactional
    public GeneratedInvitationResponse regenerate(UUID companyId, UUID invitationId, long expectedVersion) {
        CompanyInvitationEntity invitation = lockInvitation(companyId, invitationId);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ConflictException("INVITATION_NOT_REGENERABLE",
                    "Este convite não pode gerar um novo link. Crie outro convite.");
        }
        requireVersion(invitation, expectedVersion);

        OffsetDateTime now = OffsetDateTime.now(clock);
        long retryAfterSeconds = InvitationRegenerationLimit.retryAfterSeconds(
                generationsSince(invitation, now.minus(InvitationRegenerationLimit.WINDOW)), now);
        if (retryAfterSeconds > 0) {
            throw new TooManyRequestsException("INVITATION_RATE_LIMITED",
                    "Aguarde para gerar um novo link deste convite.", retryAfterSeconds);
        }

        replacedTokenRepository.save(CompanyInvitationReplacedTokenEntity.builder()
                .invitationId(invitation.getId())
                .tokenHash(invitation.getTokenHash())
                .replacedAt(now)
                .build());
        String token = invitationTokens.generate();
        invitation.setTokenHash(invitationTokens.hash(token));
        invitation.setTokenCiphertext(tokenCipher.encrypt(token));
        invitation.setTokenKeyVersion(tokenCipher.keyVersion());
        invitation.setVersion(invitation.getVersion() + 1);
        invitation.setUpdatedAt(now);
        invitationRepository.saveAndFlush(invitation);
        return new GeneratedInvitationResponse(toResponse(invitation), inviteUrl(token));
    }

    /**
     * Revoga um convite pendente: o link deixa de valer, a cópia recuperável do token é eliminada e a conta
     * pessoal do convidado, se existir, não é tocada. Repetir uma revogação já feita não muda nada.
     */
    @Transactional
    public void revoke(UUID companyId, UUID invitationId, long expectedVersion) {
        CompanyInvitationEntity invitation = lockInvitation(companyId, invitationId);
        if (invitation.getStatus() == InvitationStatus.REVOKED) {
            return;
        }
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new ConflictException("INVITATION_ALREADY_USED",
                    "Este convite já foi aceito e não pode ser revogado.");
        }
        requireVersion(invitation, expectedVersion);

        OffsetDateTime now = OffsetDateTime.now(clock);
        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setRevokedAt(now);
        invitation.setTokenCiphertext(null);
        invitation.setTokenKeyVersion(null);
        invitation.setVersion(invitation.getVersion() + 1);
        invitation.setUpdatedAt(now);
        invitationRepository.saveAndFlush(invitation);
    }

    private GeneratedInvitationResponse createPending(UUID companyId, UserEntity owner,
                                                      InvitationCreateRequest request) {
        String email = request.email();
        if (administratorRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("EMAIL_RESERVED", "Este e-mail não pode receber um convite.");
        }
        Optional<UUID> recipientCompany = userRepository.findFirstByEmailIgnoreCase(email)
                .flatMap(user -> membershipRepository
                        .findFirstByUserIdAndStatusOrderByRemovedAtDesc(user.getId(), MembershipStatus.ACTIVE))
                .map(CompanyMembershipEntity::getCompanyId);
        if (recipientCompany.filter(companyId::equals).isPresent()) {
            throw new ConflictException("MEMBER_ALREADY_EXISTS", "Esta pessoa já é membro desta empresa.");
        }
        Optional<CompanyInvitationEntity> pending = invitationRepository
                .findFirstByCompanyIdAndStatusAndEmailIgnoreCase(companyId, InvitationStatus.PENDING, email);
        if (pending.isPresent()) {
            throw alreadyInvited(pending.get());
        }
        if (recipientCompany.isPresent()) {
            throw new ConflictException("USER_IN_OTHER_COMPANY",
                    "Este e-mail já está vinculado a outra empresa e não pode receber o convite.");
        }

        String token = invitationTokens.generate();
        OffsetDateTime now = OffsetDateTime.now(clock);
        CompanyInvitationEntity invitation = invitationRepository.saveAndFlush(CompanyInvitationEntity.builder()
                .companyId(companyId)
                .name(request.name())
                .email(email)
                .role(request.role().toCompanyRole())
                .status(InvitationStatus.PENDING)
                .tokenHash(invitationTokens.hash(token))
                .tokenCiphertext(tokenCipher.encrypt(token))
                .tokenKeyVersion(tokenCipher.keyVersion())
                .createdBy(owner.getId())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return new GeneratedInvitationResponse(MembershipResponseMapper.invitation(invitation, owner), inviteUrl(token));
    }

    /** O pedido perdeu a corrida para outro do mesmo e-mail: devolve o conflito com o convite que ganhou. */
    private RuntimeException conflictAfterRace(UUID companyId, String email, DataIntegrityViolationException ex) {
        CompanyInvitationEntity winner = transactions.execute(status -> invitationRepository
                .findFirstByCompanyIdAndStatusAndEmailIgnoreCase(companyId, InvitationStatus.PENDING, email)
                .orElse(null));
        if (winner == null) {
            return ex;
        }
        return alreadyInvited(winner);
    }

    private static ConflictException alreadyInvited(CompanyInvitationEntity existing) {
        return new ConflictException("INVITATION_ALREADY_EXISTS", "Já existe um convite para este e-mail nesta empresa.",
                Map.of("invitationId", existing.getId()));
    }

    /** Gerações do link (a criação e cada regeneração) depois do instante dado. */
    private List<OffsetDateTime> generationsSince(CompanyInvitationEntity invitation, OffsetDateTime cutoff) {
        List<OffsetDateTime> generations = new ArrayList<>();
        if (invitation.getCreatedAt().isAfter(cutoff)) {
            generations.add(invitation.getCreatedAt());
        }
        replacedTokenRepository.findByInvitationIdAndReplacedAtAfter(invitation.getId(), cutoff)
                .forEach(replaced -> generations.add(replaced.getReplacedAt()));
        return generations;
    }

    private static void requireVersion(CompanyInvitationEntity invitation, long expectedVersion) {
        if (invitation.getVersion() != expectedVersion) {
            throw new PreconditionFailedException("RESOURCE_VERSION_MISMATCH",
                    "O convite foi alterado por outra ação. Recarregue e confirme de novo.");
        }
    }

    /** Link compartilhável do token, com a origem pública do frontend configurada para o ambiente. */
    private String inviteUrl(String token) {
        return frontendOrigin + INVITE_PATH + token;
    }

    private CompanyInvitationEntity findInvitation(UUID companyId, UUID invitationId) {
        return invitationRepository.findByIdAndCompanyId(invitationId, companyId)
                .orElseThrow(CompanyInvitationService::invitationNotFound);
    }

    private CompanyInvitationEntity lockInvitation(UUID companyId, UUID invitationId) {
        return invitationRepository.findForUpdate(invitationId, companyId)
                .orElseThrow(CompanyInvitationService::invitationNotFound);
    }

    private static NotFoundException invitationNotFound() {
        return new NotFoundException("INVITATION_NOT_FOUND", "Convite não encontrado.");
    }

    private InvitationResponse toResponse(CompanyInvitationEntity invitation) {
        UserEntity creator = userRepository.findById(invitation.getCreatedBy()).orElseThrow();
        return MembershipResponseMapper.invitation(invitation, creator);
    }
}
