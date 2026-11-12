package ages.vstable.backend.service;

import ages.vstable.backend.logging.OperationalLog;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.onboarding.AccountRequestDTO;
import ages.vstable.backend.dto.onboarding.CompanyRegistrationData;
import ages.vstable.backend.dto.onboarding.CompanyRegistrationRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.dto.onboarding.OnboardingStatusResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.utils.JwtTokenUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Cadastro de representantes e empresas, em uma etapa ({@link #performOnboarding}) ou em duas: primeiro a
 * conta ({@link #createAccount}) e depois, autenticado, a empresa ({@link #registerCompany}). Os três fluxos
 * compartilham os mesmos passos de validação e persistência.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";
    static final String ACTIVE_MEMBERSHIP_UNIQUE_CONSTRAINT = "company_memberships_active_user_key";

    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final CompanyDataValidator companyDataValidator;
    private final PasswordEncoder passwordEncoder;
    private final CompanyService companyService;
    private final JwtTokenUtils jwtTokenUtils;
    private final TotpService totpService;
    private final CompanyMembershipService companyMembershipService;

    /** Cadastro em uma etapa: conta, empresa, vínculo de dono e KYC pendente na mesma transação. */
    @Transactional
    public OnboardingResponseDTO performOnboarding(OnboardingRequestDTO request) {
        validatePassword(request);
        CompanyNormalizedData companyData = normalize(request);
        assertEmailAvailable(request.getEmail());
        companyService.assertCnpjAvailable(companyData.cnpj());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompanyEntity company = companyService.saveNewCompany(companyData, request.getTradeName(), now);
        NewAccount account = saveAccount(request, company.getId(), now);
        AveniaKycVerificationEntity kyc = assignOwnerWithPendingKyc(account.user().getId(), company.getId(), now);

        OperationalLog.milestone("onboarding.completed", "success");
        return authenticatedResponse(account)
                .companyId(company.getId())
                .kycVerificationId(kyc.getId())
                .build();
    }

    /**
     * Cria só a conta de acesso, sem empresa. Ela autentica, mas qualquer dado empresarial continua negado
     * até existir um vínculo ativo (CompanyAccess); o próximo passo é cadastrar a empresa.
     */
    @Transactional
    public OnboardingResponseDTO createAccount(AccountRequestDTO request) {
        validatePassword(request);
        assertEmailAvailable(request.getEmail());

        NewAccount account = saveAccount(request, null, OffsetDateTime.now(ZoneOffset.UTC));

        OperationalLog.milestone("onboarding.account_created", "success");
        return authenticatedResponse(account).build();
    }

    /**
     * Cadastra a empresa de um usuário sem vínculo ativo e o torna proprietário dela. Um CNPJ já cadastrado
     * é sempre conflito: conhecer o CNPJ nunca associa o usuário à empresa existente. Requisições
     * simultâneas são decididas pelas restrições únicas do banco (CNPJ e vínculo ativo por usuário).
     */
    @Transactional
    public OnboardingResponseDTO registerCompany(UserEntity currentUser, CompanyRegistrationRequestDTO request) {
        // Vínculo ativo, ou conta legada com empresa e sem vínculo: o usuário já tem empresa.
        if (CompanyMembershipService.currentCompanyId(currentUser) != null) {
            throw alreadyInCompany();
        }
        CompanyNormalizedData companyData = normalize(request);
        companyService.assertCnpjAvailable(companyData.cnpj());

        UserEntity user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompanyEntity company = companyService.saveNewCompany(companyData, request.getTradeName(), now);
        // users.company_id espelha a empresa do vínculo, como no cadastro em uma etapa; a autorização
        // continua vindo só do vínculo.
        user.setCompanyId(company.getId());
        user.setUpdatedAt(now);
        userRepository.saveAndFlush(user);
        AveniaKycVerificationEntity kyc = assignOwnerWithPendingKyc(user.getId(), company.getId(), now);

        OperationalLog.milestone("onboarding.company_registered", "success");
        return OnboardingResponseDTO.builder()
                .userId(user.getId())
                .companyId(company.getId())
                .kycVerificationId(kyc.getId())
                .build();
    }

    @Transactional(readOnly = true)
    public OnboardingStatusResponse getCurrentOnboarding(UserEntity user) {
        AveniaKycVerificationEntity kyc = aveniaKycVerificationRepository
                .findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new NotFoundException("No KYC verification found for the current user"));

        return OnboardingStatusResponse.builder()
                .kycVerificationId(kyc.getId())
                .companyId(CompanyMembershipService.currentCompanyId(user))
                .status(kyc.getStatus())
                .documentSubmitted(isPresent(kyc.getDocumentId()))
                .livenessSubmitted(isPresent(kyc.getLivenessId()))
                .build();
    }

    private record NewAccount(UserEntity user, String otpauthUri) {
    }

    private NewAccount saveAccount(AccountRequestDTO request, UUID companyId, OffsetDateTime now) {
        UserEntity user = new UserEntity();
        user.setCompanyId(companyId);
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPasswordSalt(BCrypt.gensalt());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setTwoFactorEnabled(false);

        String otpauthUri = null;
        if (Boolean.TRUE.equals(request.getEnableTwoFactor())) {
            String rawSecret = totpService.generateSecret();
            user.setTwoFactorSecret(rawSecret);
            otpauthUri = totpService.buildOtpAuthUri(user.getEmail(), rawSecret);
        }
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            return new NewAccount(userRepository.saveAndFlush(user), otpauthUri);
        } catch (DataIntegrityViolationException e) {
            // Cadastro simultâneo com o mesmo e-mail: a restrição única do banco decide.
            throw conflictOn(e, EMAIL_UNIQUE_CONSTRAINT, new ConflictException("Email already registered"));
        }
    }

    /** Torna o usuário proprietário da empresa e abre o KYC do responsável, ponto de partida do documento e liveness. */
    private AveniaKycVerificationEntity assignOwnerWithPendingKyc(UUID userId, UUID companyId, OffsetDateTime now) {
        try {
            companyMembershipService.assignOwner(userId, companyId, now);
        } catch (DataIntegrityViolationException e) {
            // Outra requisição do mesmo usuário criou o vínculo ativo primeiro.
            throw conflictOn(e, ACTIVE_MEMBERSHIP_UNIQUE_CONSTRAINT, alreadyInCompany());
        }

        AveniaKycVerificationEntity kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(ComplianceStatus.PENDING);
        kyc.setResponsePayload("{}");
        kyc.setCreatedAt(now);
        kyc.setUpdatedAt(now);
        return aveniaKycVerificationRepository.save(kyc);
    }

    /** Resposta de quem acabou de criar a conta: o token já autentica e, se pedido, o 2FA aguarda ativação. */
    private OnboardingResponseDTO.OnboardingResponseDTOBuilder authenticatedResponse(NewAccount account) {
        return OnboardingResponseDTO.builder()
                .userId(account.user().getId())
                .accessToken(jwtTokenUtils.generateToken(account.user()))
                .twoFactorSetupRequired(account.otpauthUri() != null)
                .otpauthUri(account.otpauthUri());
    }

    private CompanyNormalizedData normalize(CompanyRegistrationData company) {
        return companyDataValidator.normalize(company.getLegalName(), company.getCnpj(), company.getCountry(),
                company.getZipCode(), company.getCity(), company.getState());
    }

    private void validatePassword(AccountRequestDTO request) {
        String password = request.getPassword();
        if (password == null || !password.equals(request.getConfirmPassword())) {
            throw new UnprocessableEntityException("Password and confirmation do not match");
        }
        if (!isStrongPassword(password)) {
            throw new UnprocessableEntityException("Password must be at least 8 characters long and include a number and a special character");
        }
    }

    private static boolean isStrongPassword(String password) {
        if (password.length() < 8) {
            return false;
        }

        boolean hasNumber = false;
        boolean hasSpecialCharacter = false;
        for (int i = 0; i < password.length(); i++) {
            char character = password.charAt(i);
            if (character == '\n' || character == '\r' || character == '\u0085'
                    || character == '\u2028' || character == '\u2029') {
                return false;
            }
            hasNumber |= character >= '0' && character <= '9';
            hasSpecialCharacter |= !((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9'));
        }
        return hasNumber && hasSpecialCharacter;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private void assertEmailAvailable(String email) {
        // Admin e-mails are also reserved so that a login e-mail always maps to a single account.
        if (userRepository.existsByEmail(email) || administratorRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
    }

    private static ConflictException alreadyInCompany() {
        return new ConflictException("USER_ALREADY_IN_COMPANY", "User already belongs to a company");
    }

    /**
     * Só a restrição única esperada vira conflito; qualquer outra falha de integridade é um erro real e
     * segue adiante. Nada é consultado depois da falha, porque o PostgreSQL já abortou a transação.
     */
    private static RuntimeException conflictOn(DataIntegrityViolationException e, String constraint,
                                               ConflictException conflict) {
        String cause = e.getMostSpecificCause().getMessage();
        return cause != null && cause.contains(constraint) ? conflict : e;
    }
}
