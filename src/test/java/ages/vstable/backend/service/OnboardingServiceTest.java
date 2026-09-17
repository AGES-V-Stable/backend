package ages.vstable.backend.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AveniaKycVerificationRepository aveniaKycVerificationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        onboardingService = new OnboardingService(
                userRepository, companyRepository, aveniaKycVerificationRepository,
                new CompanyDataValidator(), passwordEncoder);
    }

    @Test
    void performOnboarding_createsCompanyUserAndPendingKyc() {
        OnboardingRequestDTO request = validRequest();
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID kycId = UUID.randomUUID();

        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity company = invocation.getArgument(0);
            company.setId(companyId);
            return company;
        });
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });
        when(aveniaKycVerificationRepository.save(any())).thenAnswer(invocation -> {
            AveniaKycVerificationEntity kyc = invocation.getArgument(0);
            kyc.setId(kycId);
            return kyc;
        });
        when(passwordEncoder.encode("Senha@123")).thenReturn("hash-bcrypt");

        OnboardingResponseDTO response = onboardingService.performOnboarding(request);

        assertThat(response.getCompanyId()).isEqualTo(companyId);
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getKycVerificationId()).isEqualTo(kycId);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        org.mockito.Mockito.verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity savedUser = userCaptor.getValue();
        assertThat(savedUser.getFullName()).isEqualTo("Joao da Silva");
        assertThat(savedUser.getCompanyId()).isEqualTo(companyId);
        assertThat(savedUser.getPasswordHash()).isEqualTo("hash-bcrypt");
        assertThat(savedUser.getPasswordSalt()).isNotBlank();

        ArgumentCaptor<AveniaKycVerificationEntity> kycCaptor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        org.mockito.Mockito.verify(aveniaKycVerificationRepository).save(kycCaptor.capture());
        assertThat(kycCaptor.getValue().getUserId()).isEqualTo(userId);
        assertThat(kycCaptor.getValue().getStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void performOnboarding_emailAlreadyRegistered_throwsConflict() {
        when(userRepository.existsByEmail("joao@example.com")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void performOnboarding_cnpjAlreadyRegistered_throwsConflict() {
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void performOnboarding_emailConstraintRaceCondition_returnsConflict() {
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        when(userRepository.existsByEmail("joao@example.com")).thenReturn(false, true);

        assertThatThrownBy(() -> onboardingService.performOnboarding(validRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void performOnboarding_passwordAndConfirmationMismatch_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setConfirmPassword("Outra@123");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void performOnboarding_weakPassword_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setPassword("fraca");
        request.setConfirmPassword("fraca");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void performOnboarding_invalidEmail_throwsUnprocessable() {
        OnboardingRequestDTO request = validRequest();
        request.setEmail("email-invalido");

        assertThatThrownBy(() -> onboardingService.performOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    private OnboardingRequestDTO validRequest() {
        OnboardingRequestDTO request = new OnboardingRequestDTO();
        request.setFullName("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setPassword("Senha@123");
        request.setConfirmPassword("Senha@123");
        request.setLegalName("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }
}
