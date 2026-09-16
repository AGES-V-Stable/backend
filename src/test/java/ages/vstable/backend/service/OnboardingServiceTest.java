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
    void realizarOnboarding_criaEmpresaUsuarioEKycPendente() {
        OnboardingRequestDTO request = requestValido();
        UUID empresaId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID kycId = UUID.randomUUID();

        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity empresa = invocation.getArgument(0);
            empresa.setId(empresaId);
            return empresa;
        });
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserEntity usuario = invocation.getArgument(0);
            usuario.setId(usuarioId);
            return usuario;
        });
        when(aveniaKycVerificationRepository.save(any())).thenAnswer(invocation -> {
            AveniaKycVerificationEntity kyc = invocation.getArgument(0);
            kyc.setId(kycId);
            return kyc;
        });
        when(passwordEncoder.encode("Senha@123")).thenReturn("hash-bcrypt");

        OnboardingResponseDTO response = onboardingService.realizarOnboarding(request);

        assertThat(response.getEmpresaId()).isEqualTo(empresaId);
        assertThat(response.getUsuarioId()).isEqualTo(usuarioId);
        assertThat(response.getVerificacaoKycId()).isEqualTo(kycId);

        ArgumentCaptor<UserEntity> usuarioCaptor = ArgumentCaptor.forClass(UserEntity.class);
        org.mockito.Mockito.verify(userRepository).saveAndFlush(usuarioCaptor.capture());
        UserEntity usuarioSalvo = usuarioCaptor.getValue();
        assertThat(usuarioSalvo.getFullName()).isEqualTo("Joao da Silva");
        assertThat(usuarioSalvo.getCompanyId()).isEqualTo(empresaId);
        assertThat(usuarioSalvo.getPasswordHash()).isEqualTo("hash-bcrypt");
        assertThat(usuarioSalvo.getPasswordSalt()).isNotBlank();

        ArgumentCaptor<AveniaKycVerificationEntity> kycCaptor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        org.mockito.Mockito.verify(aveniaKycVerificationRepository).save(kycCaptor.capture());
        assertThat(kycCaptor.getValue().getUserId()).isEqualTo(usuarioId);
        assertThat(kycCaptor.getValue().getStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void realizarOnboarding_emailJaCadastrado_lancaConflict() {
        when(userRepository.existsByEmail("joao@example.com")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(requestValido()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void realizarOnboarding_cnpjJaCadastrado_lancaConflict() {
        when(companyRepository.existsByCnpj("11222333000181")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(requestValido()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void realizarOnboarding_corridaNaConstraintDeEmail_retornaConflict() {
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        when(userRepository.existsByEmail("joao@example.com")).thenReturn(false, true);

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(requestValido()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void realizarOnboarding_senhaEConfirmacaoDivergentes_lancaUnprocessable() {
        OnboardingRequestDTO request = requestValido();
        request.setConfirmarSenha("Outra@123");

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void realizarOnboarding_senhaFraca_lancaUnprocessable() {
        OnboardingRequestDTO request = requestValido();
        request.setSenha("fraca");
        request.setConfirmarSenha("fraca");

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void realizarOnboarding_emailInvalido_lancaUnprocessable() {
        OnboardingRequestDTO request = requestValido();
        request.setEmail("email-invalido");

        assertThatThrownBy(() -> onboardingService.realizarOnboarding(request))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    private OnboardingRequestDTO requestValido() {
        OnboardingRequestDTO request = new OnboardingRequestDTO();
        request.setNomeCompleto("Joao da Silva");
        request.setEmail("joao@example.com");
        request.setSenha("Senha@123");
        request.setConfirmarSenha("Senha@123");
        request.setRazaoSocial("Empresa Exemplo Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setPais("Brasil");
        request.setCep("90000-000");
        request.setCidade("Porto Alegre");
        request.setEstado("RS");
        return request;
    }
}
