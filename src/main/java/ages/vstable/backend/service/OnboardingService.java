package ages.vstable.backend.service;

import ages.vstable.backend.logging.OperationalLog;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.onboarding.AccountRequestDTO;
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
import ages.vstable.backend.repository.CompanyRepository;
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

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;
    private final CompanyRepository companyRepository;
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
        validatePassword(request.getPassword(), request.getConfirmPassword());

        CompanyNormalizedData companyData = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(),
                request.getZipCode(), request.getCity(), request.getState());

        assertEmailAvailable(request.getEmail());
        companyService.assertCnpjAvailable(companyData.cnpj());

        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            CompanyEntity company = saveCompany(companyData, request.getTradeName(), now);
            NewUser created = saveUser(request.getFullName(), request.getEmail(), request.getPassword(),
                    request.getEnableTwoFactor(), company.getId(), now);
            companyMembershipService.assignOwner(created.user().getId(), company.getId(), now);
            AveniaKycVerificationEntity kyc = savePendingKyc(created.user().getId(), now);

            OperationalLog.milestone("onboarding.completed", "success");
            return OnboardingResponseDTO.builder()
                    .userId(created.user().getId())
                    .companyId(company.getId())
                    .kycVerificationId(kyc.getId())
                    .accessToken(jwtTokenUtils.generateToken(created.user()))
                    .twoFactorSetupRequired(created.otpauthUri() != null)
                    .otpauthUri(created.otpauthUri())
                    .build();
        } catch (DataIntegrityViolationException e) {
            assertEmailAvailable(request.getEmail());
            companyService.assertCnpjAvailable(companyData.cnpj());
            throw e;
        }
    }

    /**
     * Cria só a conta de acesso, sem empresa. Ela autentica, mas qualquer dado empresarial
     * continua negado até existir um vínculo (CompanyAccess); o próximo passo é cadastrar a empresa.
     */
    @Transactional
    public OnboardingResponseDTO createAccount(AccountRequestDTO request) {
        validatePassword(request.getPassword(), request.getConfirmPassword());
        assertEmailAvailable(request.getEmail());

        try {
            NewUser created = saveUser(request.getFullName(), request.getEmail(), request.getPassword(),
                    request.getEnableTwoFactor(), null, OffsetDateTime.now(ZoneOffset.UTC));

            OperationalLog.milestone("onboarding.account_created", "success");
            return OnboardingResponseDTO.builder()
                    .userId(created.user().getId())
                    .accessToken(jwtTokenUtils.generateToken(created.user()))
                    .twoFactorSetupRequired(created.otpauthUri() != null)
                    .otpauthUri(created.otpauthUri())
                    .build();
        } catch (DataIntegrityViolationException e) {
            // Cadastro concorrente com o mesmo e-mail: a restrição única do banco decide.
            throw new ConflictException("Email already registered");
        }
    }

    /**
     * Cadastra a empresa de um usuário que ainda não tem vínculo e o torna dono dela. Um CNPJ
     * já cadastrado é sempre conflito: conhecer o CNPJ nunca associa o usuário à empresa existente.
     */
    @Transactional
    public OnboardingResponseDTO registerCompany(UserEntity currentUser, CompanyRegistrationRequestDTO request) {
        if (CompanyMembershipService.currentCompanyId(currentUser) != null) {
            throw new ConflictException("User already belongs to a company");
        }

        CompanyNormalizedData companyData = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(),
                request.getZipCode(), request.getCity(), request.getState());
        companyService.assertCnpjAvailable(companyData.cnpj());

        UserEntity user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found"));

        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            CompanyEntity company = saveCompany(companyData, request.getTradeName(), now);
            // users.company_id continua espelhando a empresa do vínculo, como no cadastro em uma etapa.
            user.setCompanyId(company.getId());
            user.setUpdatedAt(now);
            userRepository.saveAndFlush(user);
            companyMembershipService.assignOwner(user.getId(), company.getId(), now);
            AveniaKycVerificationEntity kyc = savePendingKyc(user.getId(), now);

            OperationalLog.milestone("onboarding.company_registered", "success");
            return OnboardingResponseDTO.builder()
                    .userId(user.getId())
                    .companyId(company.getId())
                    .kycVerificationId(kyc.getId())
                    .build();
        } catch (DataIntegrityViolationException e) {
            // Requisições concorrentes: o CNPJ único ou o vínculo ativo único do banco decide.
            throw new ConflictException(isCnpjViolation(e)
                    ? "CNPJ already registered"
                    : "User already belongs to a company");
        }
    }

    private record NewUser(UserEntity user, String otpauthUri) {
    }

    private NewUser saveUser(String fullName, String email, String password, Boolean enableTwoFactor,
                             UUID companyId, OffsetDateTime now) {
        UserEntity user = new UserEntity();
        user.setCompanyId(companyId);
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPasswordSalt(BCrypt.gensalt());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setTwoFactorEnabled(false);

        String otpauthUri = null;
        if (Boolean.TRUE.equals(enableTwoFactor)) {
            String rawSecret = totpService.generateSecret();
            user.setTwoFactorSecret(rawSecret);
            otpauthUri = totpService.buildOtpAuthUri(email, rawSecret);
        }
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return new NewUser(userRepository.saveAndFlush(user), otpauthUri);
    }

    private CompanyEntity saveCompany(CompanyNormalizedData companyData, String tradeName, OffsetDateTime now) {
        return companyRepository.saveAndFlush(companyService.buildNewCompany(companyData, tradeName, now));
    }

    /** KYC do responsável que cadastrou a empresa; o fluxo de documento e liveness parte dele. */
    private AveniaKycVerificationEntity savePendingKyc(UUID userId, OffsetDateTime now) {
        AveniaKycVerificationEntity kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(userId);
        kyc.setStatus(ComplianceStatus.PENDING);
        kyc.setResponsePayload("{}");
        kyc.setCreatedAt(now);
        kyc.setUpdatedAt(now);
        return aveniaKycVerificationRepository.save(kyc);
    }

    private static boolean isCnpjViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains("companies_cnpj_key");
    }

    private void validatePassword(String password, String confirmPassword) {
        if (password == null || !password.equals(confirmPassword)) {
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

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private void assertEmailAvailable(String email) {
        // Admin e-mails are also reserved so that a login e-mail always maps to a single account.
        if (userRepository.existsByEmail(email) || administratorRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
    }
}
