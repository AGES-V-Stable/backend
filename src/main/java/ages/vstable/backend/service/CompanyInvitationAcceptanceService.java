package ages.vstable.backend.service;

import ages.vstable.backend.configuration.TermsProperties;
import ages.vstable.backend.dto.membership.InvitationAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationAcceptanceMode;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptRequest;
import ages.vstable.backend.dto.membership.InvitationNewUserAcceptResponse;
import ages.vstable.backend.dto.membership.InvitationResolveResponse;
import ages.vstable.backend.dto.membership.MemberResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.CompanyInvitationEntity;
import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.InvitationStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.GoneException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.logging.OperationalLog;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.CompanyInvitationReplacedTokenRepository;
import ages.vstable.backend.repository.CompanyInvitationRepository;
import ages.vstable.backend.repository.CompanyMembershipRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.InvitationTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Consulta e aceite do convite de membro pelo token do link, por quem ainda não tem conta ou por quem já está
 * autenticado. Email, empresa e perfil vêm sempre do convite. O aceite trava a linha do convite e revalida o
 * estado, o link vigente e o vínculo do usuário na mesma transação que o consome, então aceites, regenerações
 * e revogações simultâneos se enfileiram e só um deles vale. Nenhuma operação cria empresa, conta financeira
 * ou KYC, nem chama a Avenia ou a BlindPay.
 */
@Service
public class CompanyInvitationAcceptanceService {

    static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";
    /** Mesma regra sem diferenciar maiúsculas (V14); qualquer uma das duas pode ser a violada primeiro. */
    static final String EMAIL_CASE_INSENSITIVE_UNIQUE_CONSTRAINT = "users_email_lower_key";
    static final String ACTIVE_MEMBERSHIP_UNIQUE_CONSTRAINT = "company_memberships_active_user_key";

    /** 32 bytes em base64url sem preenchimento, o formato gerado por {@link InvitationTokens}. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final CompanyInvitationRepository invitationRepository;
    private final CompanyInvitationReplacedTokenRepository replacedTokenRepository;
    private final CompanyMembershipRepository membershipRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;
    private final InvitationTokens invitationTokens;
    private final PasswordEncoder passwordEncoder;
    private final TermsProperties terms;
    private final Clock clock;

    @Autowired
    public CompanyInvitationAcceptanceService(
            CompanyInvitationRepository invitationRepository,
            CompanyInvitationReplacedTokenRepository replacedTokenRepository,
            CompanyMembershipRepository membershipRepository,
            CompanyRepository companyRepository,
            UserRepository userRepository,
            AdministratorRepository administratorRepository,
            InvitationTokens invitationTokens,
            PasswordEncoder passwordEncoder,
            TermsProperties terms) {
        this(invitationRepository, replacedTokenRepository, membershipRepository, companyRepository, userRepository,
                administratorRepository, invitationTokens, passwordEncoder, terms, Clock.systemUTC());
    }

    // Construtor visível para testes determinísticos (controle do instante do aceite)
    CompanyInvitationAcceptanceService(
            CompanyInvitationRepository invitationRepository,
            CompanyInvitationReplacedTokenRepository replacedTokenRepository,
            CompanyMembershipRepository membershipRepository,
            CompanyRepository companyRepository,
            UserRepository userRepository,
            AdministratorRepository administratorRepository,
            InvitationTokens invitationTokens,
            PasswordEncoder passwordEncoder,
            TermsProperties terms,
            Clock clock) {
        this.invitationRepository = invitationRepository;
        this.replacedTokenRepository = replacedTokenRepository;
        this.membershipRepository = membershipRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.administratorRepository = administratorRepository;
        this.invitationTokens = invitationTokens;
        this.passwordEncoder = passwordEncoder;
        this.terms = terms;
        this.clock = clock;
    }

    /** Dados do convite pendente para a tela de aceite. Não consome o convite nem altera nada. */
    @Transactional(readOnly = true)
    public InvitationResolveResponse resolve(String token) {
        String tokenHash = hashOf(token);
        CompanyInvitationEntity invitation = requirePending(invitationRepository.findByTokenHash(tokenHash), tokenHash);
        CompanyEntity company = companyRepository.findById(invitation.getCompanyId())
                .orElseThrow(CompanyInvitationAcceptanceService::invalid);
        String inviterName = userRepository.findById(invitation.getCreatedBy())
                .map(UserEntity::getFullName)
                .orElse(null);
        InvitationAcceptanceMode mode = identityExists(invitation.getEmail())
                ? InvitationAcceptanceMode.LOGIN
                : InvitationAcceptanceMode.REGISTER;
        return new InvitationResolveResponse(
                invitation.getId(),
                new InvitationResolveResponse.Company(company.getId(), companyName(company)),
                new InvitationResolveResponse.InvitedBy(inviterName),
                new InvitationResolveResponse.Recipient(invitation.getName(), invitation.getEmail()),
                invitation.getRole(),
                mode,
                new InvitationResolveResponse.Terms(terms.version(), terms.termsUrl(), terms.privacyUrl()));
    }

    /**
     * Cria a conta do convidado com nome e e-mail do convite, o vínculo com a empresa e consome o convite, tudo
     * na mesma transação. Não emite JWT: o acesso começa pelo login normal. Repetir o pedido depois do commit
     * responde {@code INVITATION_ALREADY_USED}, e uma conta que já exista nunca tem a senha alterada.
     */
    @Transactional
    public InvitationNewUserAcceptResponse acceptNewUser(InvitationNewUserAcceptRequest request) {
        String tokenHash = hashOf(request.token());
        CompanyInvitationEntity invitation = requirePending(
                invitationRepository.findByTokenHashForUpdate(tokenHash), tokenHash);
        if (identityExists(invitation.getEmail())) {
            throw userAlreadyExists();
        }
        validatePassword(request.password(), request.confirmPassword());
        requireTerms(request.termsAccepted(), request.termsVersion());

        OffsetDateTime now = OffsetDateTime.now(clock);
        UserEntity user = saveNewUser(invitation, request.password(), now);
        CompanyMembershipEntity membership = saveMembership(invitation, user.getId(), now);
        markAccepted(invitation, user.getId(), membership.getId(), request.termsVersion(), now);

        OperationalLog.milestone("company.invitation.accepted_new_user", "success");
        return new InvitationNewUserAcceptResponse(user.getId(), MembershipResponseMapper.member(membership, user),
                InvitationNewUserAcceptResponse.NEXT_ACTION_LOGIN);
    }

    /**
     * Aceite por quem já tem conta e está autenticado (depois do login e do 2FA, quando exigido). O e-mail da
     * conta precisa ser o do convite. Não altera nome, senha, 2FA nem outros dados da conta. Repetir o aceite
     * com o mesmo token, pela mesma pessoa e com o vínculo ainda ativo devolve o mesmo vínculo sem efeito
     * colateral; um vínculo já removido nunca é reativado pelo link.
     */
    @Transactional
    public MemberResponse accept(UserEntity principal, InvitationAcceptRequest request) {
        String tokenHash = hashOf(request.token());
        CompanyInvitationEntity invitation = requireUsable(
                invitationRepository.findByTokenHashForUpdate(tokenHash), tokenHash);
        UserEntity user = userRepository.findById(principal.getId())
                .orElseThrow(CompanyInvitationAcceptanceService::identityMismatch);
        if (!normalizedEmail(user.getEmail()).equals(normalizedEmail(invitation.getEmail()))) {
            throw identityMismatch();
        }
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            return repeatedAcceptance(invitation, user);
        }
        requireTerms(request.termsAccepted(), request.termsVersion());
        rejectExistingCompany(user, invitation.getCompanyId());

        OffsetDateTime now = OffsetDateTime.now(clock);
        CompanyMembershipEntity membership = saveMembership(invitation, user.getId(), now);
        // users.company_id espelha a empresa do vínculo, como no cadastro da empresa; a autorização vem do vínculo.
        user.setCompanyId(invitation.getCompanyId());
        user.setUpdatedAt(now);
        userRepository.saveAndFlush(user);
        markAccepted(invitation, user.getId(), membership.getId(), request.termsVersion(), now);

        OperationalLog.milestone("company.invitation.accepted_existing_user", "success");
        return MembershipResponseMapper.member(membership, user);
    }

    /** O mesmo aceite repetido: só devolve o vínculo se for da mesma pessoa e continuar ativo. */
    private MemberResponse repeatedAcceptance(CompanyInvitationEntity invitation, UserEntity user) {
        return Optional.ofNullable(invitation.getMembershipId())
                .filter(membershipId -> user.getId().equals(invitation.getAcceptedBy()))
                .flatMap(membershipRepository::findById)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .map(membership -> MembershipResponseMapper.member(membership, user))
                .orElseThrow(CompanyInvitationAcceptanceService::alreadyUsed);
    }

    /**
     * Sem reassociação automática nem várias empresas: um vínculo ativo, ou uma conta antiga com empresa e
     * sem vínculo algum, impede o aceite. Um vínculo removido não impede.
     */
    private void rejectExistingCompany(UserEntity user, UUID invitedCompanyId) {
        Optional<UUID> currentCompany = membershipRepository
                .findFirstByUserIdAndStatusOrderByRemovedAtDesc(user.getId(), MembershipStatus.ACTIVE)
                .map(CompanyMembershipEntity::getCompanyId)
                .or(() -> membershipRepository.existsByUserId(user.getId())
                        ? Optional.empty()
                        : Optional.ofNullable(user.getCompanyId()));
        if (currentCompany.isEmpty()) {
            return;
        }
        if (currentCompany.get().equals(invitedCompanyId)) {
            throw new ConflictException("MEMBER_ALREADY_EXISTS", "Você já é membro desta empresa.");
        }
        throw userInOtherCompany();
    }

    private UserEntity saveNewUser(CompanyInvitationEntity invitation, String password, OffsetDateTime now) {
        UserEntity user = new UserEntity();
        // users.company_id espelha a empresa do vínculo; a autorização vem do vínculo.
        user.setCompanyId(invitation.getCompanyId());
        user.setFullName(invitation.getName());
        user.setEmail(normalizedEmail(invitation.getEmail()));
        user.setPasswordSalt(BCrypt.gensalt());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setTwoFactorEnabled(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Outro cadastro com o mesmo e-mail venceu a corrida; a restrição única do banco decide.
            throw conflictOn(e, userAlreadyExists(),
                    EMAIL_UNIQUE_CONSTRAINT, EMAIL_CASE_INSENSITIVE_UNIQUE_CONSTRAINT);
        }
    }

    private CompanyMembershipEntity saveMembership(CompanyInvitationEntity invitation, UUID userId,
                                                   OffsetDateTime now) {
        try {
            return membershipRepository.saveAndFlush(CompanyMembershipEntity.builder()
                    .companyId(invitation.getCompanyId())
                    .userId(userId)
                    .role(invitation.getRole())
                    .status(MembershipStatus.ACTIVE)
                    .joinedAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Outro pedido do mesmo usuário criou um vínculo ativo primeiro (outro convite ou cadastro de empresa).
            throw conflictOn(e, userInOtherCompany(), ACTIVE_MEMBERSHIP_UNIQUE_CONSTRAINT);
        }
    }

    /** Consome o convite: o link deixa de valer e a cópia recuperável do token é eliminada. */
    private void markAccepted(CompanyInvitationEntity invitation, UUID userId, UUID membershipId,
                              String termsVersion, OffsetDateTime now) {
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(now);
        invitation.setAcceptedBy(userId);
        invitation.setMembershipId(membershipId);
        invitation.setTermsVersion(termsVersion);
        invitation.setTermsAcceptedAt(now);
        invitation.setTokenCiphertext(null);
        invitation.setTokenKeyVersion(null);
        invitation.setVersion(invitation.getVersion() + 1);
        invitation.setUpdatedAt(now);
        invitationRepository.saveAndFlush(invitation);
    }

    /** Convite que ainda pode ser aceito; um já aceito é {@code INVITATION_ALREADY_USED}. */
    private CompanyInvitationEntity requirePending(Optional<CompanyInvitationEntity> found, String tokenHash) {
        CompanyInvitationEntity invitation = requireUsable(found, tokenHash);
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw alreadyUsed();
        }
        return invitation;
    }

    /**
     * Convite do link vigente, pendente ou aceito. Um link substituído por regeneração é reconhecido pelo hash
     * guardado no histórico; um revogado continua no convite. Qualquer outro token é inválido.
     */
    private CompanyInvitationEntity requireUsable(Optional<CompanyInvitationEntity> found, String tokenHash) {
        if (found.isEmpty()) {
            if (replacedTokenRepository.existsByTokenHash(tokenHash)) {
                throw new GoneException("INVITATION_SUPERSEDED",
                        "Este link foi substituído por um novo. Peça o link atualizado a quem convidou você.");
            }
            throw invalid();
        }
        CompanyInvitationEntity invitation = found.get();
        if (invitation.getStatus() == InvitationStatus.REVOKED) {
            throw new GoneException("INVITATION_REVOKED", "Este convite foi revogado. Peça um novo convite.");
        }
        return invitation;
    }

    /** Hash do token; um token fora do formato nem chega ao banco e é tratado como inexistente. */
    private String hashOf(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalid();
        }
        return invitationTokens.hash(token);
    }

    private void validatePassword(String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            throw new UnprocessableEntityException("PASSWORD_CONFIRMATION_MISMATCH",
                    "A confirmação não corresponde à senha.");
        }
        if (!PasswordPolicy.isStrong(password)) {
            throw new UnprocessableEntityException("PASSWORD_TOO_WEAK",
                    "A senha deve ter ao menos 8 caracteres, um número e um caractere especial, sem quebras de "
                            + "linha e com até 72 bytes.");
        }
    }

    private void requireTerms(Boolean termsAccepted, String termsVersion) {
        if (!Boolean.TRUE.equals(termsAccepted)) {
            throw new UnprocessableEntityException("TERMS_NOT_ACCEPTED", "Aceite os termos para continuar.");
        }
        if (!terms.version().equals(termsVersion)) {
            throw new ConflictException("TERMS_VERSION_MISMATCH",
                    "Os termos foram atualizados. Recarregue o convite e aceite a versão vigente.");
        }
    }

    /** Conta de empresa ou de administrador com o e-mail: o mesmo e-mail nunca vira duas identidades. */
    private boolean identityExists(String email) {
        return userRepository.findFirstByEmailIgnoreCase(email).isPresent()
                || administratorRepository.existsByEmailIgnoreCase(email);
    }

    private static String companyName(CompanyEntity company) {
        String tradeName = company.getTradeName();
        return tradeName == null || tradeName.isBlank() ? company.getLegalName() : tradeName;
    }

    private static String normalizedEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Só a restrição única esperada vira conflito; qualquer outra falha de integridade é um erro real e segue
     * adiante. Nada é consultado depois da falha, porque o PostgreSQL já abortou a transação.
     */
    private static RuntimeException conflictOn(DataIntegrityViolationException e, ConflictException conflict,
                                               String... constraints) {
        String cause = e.getMostSpecificCause().getMessage();
        return cause != null && Arrays.stream(constraints).anyMatch(cause::contains) ? conflict : e;
    }

    private static NotFoundException invalid() {
        return new NotFoundException("INVITATION_INVALID", "Este link de convite é inválido.");
    }

    private static ConflictException alreadyUsed() {
        return new ConflictException("INVITATION_ALREADY_USED",
                "Este convite já foi utilizado. Se foi você quem o aceitou, entre com a sua conta.");
    }

    private static ConflictException userAlreadyExists() {
        return new ConflictException("USER_ALREADY_EXISTS",
                "Já existe uma conta com este e-mail. Entre com ela para aceitar o convite.");
    }

    private static ConflictException userInOtherCompany() {
        return new ConflictException("USER_IN_OTHER_COMPANY",
                "Sua conta já está vinculada a outra empresa e não pode aceitar este convite.");
    }

    private static ForbiddenException identityMismatch() {
        return new ForbiddenException("INVITATION_IDENTITY_MISMATCH",
                "Este convite foi enviado para outro e-mail. Entre com a conta do e-mail convidado.");
    }
}
