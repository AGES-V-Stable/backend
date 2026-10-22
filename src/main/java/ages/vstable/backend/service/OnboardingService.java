package ages.vstable.backend.service;

import ages.vstable.backend.logging.OperationalLog;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
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

    @Transactional
    public OnboardingResponseDTO performOnboarding(OnboardingRequestDTO request) {
        validatePayload(request);

        CompanyNormalizedData companyData = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(),
                request.getZipCode(), request.getCity(), request.getState());

        assertEmailAvailable(request.getEmail());
        companyService.assertCnpjAvailable(companyData.cnpj());

        try {
            OnboardingResponseDTO response = createCompanyUserAndKyc(request, companyData);
            OperationalLog.milestone("onboarding.completed", "success");
            return response;
        } catch (DataIntegrityViolationException e) {
            assertEmailAvailable(request.getEmail());
            companyService.assertCnpjAvailable(companyData.cnpj());
            throw e;
        }
    }

    private OnboardingResponseDTO createCompanyUserAndKyc(OnboardingRequestDTO request, CompanyNormalizedData companyData) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        CompanyEntity company = companyService.buildNewCompany(companyData, request.getTradeName(), now);
        company = companyRepository.saveAndFlush(company);

        UserEntity user = new UserEntity();
        user.setCompanyId(company.getId());
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPasswordSalt(BCrypt.gensalt());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        AveniaKycVerificationEntity kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(user.getId());
        kyc.setStatus(ComplianceStatus.PENDING);
        kyc.setResponsePayload("{}");
        kyc.setCreatedAt(now);
        kyc.setUpdatedAt(now);
        kyc = aveniaKycVerificationRepository.save(kyc);

        return OnboardingResponseDTO.builder()
                .userId(user.getId())
                .companyId(company.getId())
                .kycVerificationId(kyc.getId())
                .accessToken(jwtTokenUtils.generateToken(user))
                .build();
    }

    private void validatePayload(OnboardingRequestDTO request) {
        if (request.getPassword() == null || !request.getPassword().equals(request.getConfirmPassword())) {
            throw new UnprocessableEntityException("Password and confirmation do not match");
        }
        if (!isStrongPassword(request.getPassword())) {
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
                .companyId(user.getCompanyId())
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
