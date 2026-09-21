package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern STRONG_PASSWORD_PATTERN = Pattern.compile("^(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,}$");

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final CompanyDataValidator companyDataValidator;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenUtils jwtTokenUtils;

    @Transactional
    public OnboardingResponseDTO performOnboarding(OnboardingRequestDTO request) {
        validatePayload(request);

        CompanyNormalizedData companyData = companyDataValidator.normalize(
                request.getLegalName(), request.getCnpj(), request.getCountry(),
                request.getZipCode(), request.getCity(), request.getState());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("E-mail já cadastrado");
        }
        if (companyRepository.existsByCnpj(companyData.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        try {
            return createCompanyUserAndKyc(request, companyData);
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new ConflictException("E-mail já cadastrado");
            }
            if (companyRepository.existsByCnpj(companyData.cnpj())) {
                throw new ConflictException("CNPJ já cadastrado");
            }
            throw e;
        }
    }

    private OnboardingResponseDTO createCompanyUserAndKyc(OnboardingRequestDTO request, CompanyNormalizedData companyData) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        CompanyEntity company = new CompanyEntity();
        company.setLegalName(companyData.legalName());
        company.setTradeName(normalizeOptional(request.getTradeName()));
        company.setCnpj(companyData.cnpj());
        company.setCountry(companyData.country());
        company.setZipCode(companyData.zipCode());
        company.setCity(companyData.city());
        company.setState(companyData.state());
        company.setKybStatus(ComplianceStatus.PENDING);
        company.setAmlStatus(ComplianceStatus.PENDING);
        company.setAvailableBalanceBrl(BigDecimal.ZERO);
        company.setCreatedAt(now);
        company.setUpdatedAt(now);
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
        if (!EMAIL_PATTERN.matcher(request.getEmail()).matches()) {
            throw new UnprocessableEntityException("E-mail em formato inválido");
        }
        if (request.getPassword() == null || !request.getPassword().equals(request.getConfirmPassword())) {
            throw new UnprocessableEntityException("Senha e confirmação não coincidem");
        }
        if (!STRONG_PASSWORD_PATTERN.matcher(request.getPassword()).matches()) {
            throw new UnprocessableEntityException("Senha deve ter ao menos 8 caracteres, incluindo número e caractere especial");
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
