package ages.vstable.backend.service;

import ages.vstable.backend.dto.support.SupportChatMessageDTO;
import ages.vstable.backend.dto.support.SupportChatRequestDTO;
import ages.vstable.backend.dto.support.SupportChatResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.external.ai.SupportChatClient;
import ages.vstable.backend.external.ai.SupportChatProperties;
import ages.vstable.backend.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportChatServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    private SupportChatService supportChatService;

    @BeforeEach
    void setUp() {
        SupportChatProperties properties = new SupportChatProperties();
        properties.setProvider("local");
        SupportChatClient client = new SupportChatClient(
                properties,
                new ObjectMapper(),
                RestClient.builder());
        supportChatService = new SupportChatService(client, companyRepository);
    }

    @Test
    @DisplayName("User with company tradeName and 2FA active should include enriched context")
    void chat_colombiaQuestion_returnsDomainReplyWithCompanyContext() {
        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .fullName("Rodrigo")
                .email("rodrigo@vstable.com")
                .twoFactorEnabled(true)
                .build();

        CompanyEntity company = CompanyEntity.builder()
                .id(companyId)
                .legalName("V-Stable Pagamentos LTDA")
                .tradeName("V-Stable Tech")
                .kybStatus(ComplianceStatus.APPROVED)
                .amlStatus(ComplianceStatus.APPROVED)
                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Por que não consigo fazer transações com a Colômbia? Por que está bloqueada?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("COP (Peso Colombiano)")
                .contains("Wallet Cripto")
                .contains("V-Stable Tech")
                .contains("APPROVED");
        assertThat(response.getProvider()).isEqualTo("vstable-assistant");
        assertThat(response.getSuggestedQuestions()).isNotEmpty();
    }

    @Test
    @DisplayName("When requests exceed per-minute rate limit, should throw LockedException")
    void chat_whenRateLimitExceeded_throwsLockedException() {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Teste Rate Limit")
                .email("ratelimit@vstable.com")
                .twoFactorEnabled(false)
                .build();

        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona o 2FA?")
                .build();

        for (int i = 0; i < SupportChatService.MAX_REQUESTS_PER_MINUTE; i++) {
            supportChatService.chat(auth, request);
        }

        assertThatThrownBy(() -> supportChatService.chat(auth, request))
                .isInstanceOf(LockedException.class)
                .hasMessageContaining("Limite de mensagens atingido");
    }

    @Test
    @DisplayName("Admin with 2FA enabled: includes admin context without leaking secrets")
    void chat_whenAdminAuthenticated_includesAdmin2faStatusAndNeverLeaksSecrets() {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Admin Segurança")
                .email("admin.sec@vstable.com")
                .twoFactorEnabled(true)
                .twoFactorSecret("JBSWY3DPEHPK3PXP")
                .passwordHash("$2a$10$secretHashValue")
                .build();

        Authentication auth = new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona a integração com Avenia e BlindPay?")
                .history(List.of(new SupportChatMessageDTO("user", "Olá")))
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("Avenia")
                .contains("BlindPay")
                .contains("Status da sua sessão (Administrador):")
                .contains("2FA = Ativo")
                .doesNotContain("JBSWY3DPEHPK3PXP")
                .doesNotContain("$2a$10$secretHashValue");
    }

    @Test
    @DisplayName("Admin with 2FA disabled: includes 2FA = Inativo")
    void chat_whenAdmin2faDisabled_includesInactiveStatus() {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Admin Sem 2FA")
                .email("admin.no2fa@vstable.com")
                .twoFactorEnabled(false)
                .build();

        Authentication auth = new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como ativar 2FA?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("Status da sua sessão (Administrador):")
                .contains("2FA = Inativo");
    }

    @Test
    @DisplayName("User without company link: includes status indicating company not linked")
    void chat_whenUserHasNoCompany_includesUnlinkedStatus() {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("User Sem Empresa")
                .email("no.company@vstable.com")
                .companyId(null)
                .twoFactorEnabled(false)
                .build();

        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona o onboarding?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("Empresa ainda não vinculada")
                .contains("Pendente de ativação");
    }

    @Test
    @DisplayName("User with companyId not found in repository: falls back to unlinked status")
    void chat_whenUserCompanyNotFound_includesUnlinkedStatus() {
        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("User Empresa Inexistente")
                .email("missing.company@vstable.com")
                .companyId(companyId)
                .twoFactorEnabled(true)
                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona o onboarding?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("Empresa ainda não vinculada")
                .contains("2FA = Ativo");
    }

    @Test
    @DisplayName("Company without tradeName: falls back to legalName in context summary")
    void chat_whenCompanyContextLoaded_usesLegalNameWhenTradeNameBlank() {
        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .fullName("Marina")
                .email("marina@vstable.com")
                .passwordHash("$2a$10$hashUser")
                .twoFactorEnabled(false)
                .twoFactorSecret("SECRETBASE32KEY")
                .build();

        CompanyEntity company = CompanyEntity.builder()
                .id(companyId)
                .legalName("Empresa Confidencial S.A.")
                .tradeName(null)
                .cnpj("12345678000199")
                .kybStatus(ComplianceStatus.PENDING)
                .amlStatus(ComplianceStatus.UNDER_REVIEW)
                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona o compliance KYB e AML?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("Empresa Confidencial S.A.")
                .contains("Pendente de ativação")
                .contains("PENDING")
                .contains("UNDER_REVIEW")
                .doesNotContain("12345678000199")
                .doesNotContain("$2a$10$hashUser")
                .doesNotContain("SECRETBASE32KEY");
    }

    @Test
    @DisplayName("Generic principal type: resolves rate limit key and builds empty summary")
    void chat_withCustomPrincipal_resolvesKey() {
        Authentication auth = new UsernamePasswordAuthenticationToken("system-service-account", null, List.of());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Quais são as moedas suportadas?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply()).contains("América do Norte e Europa");
        assertThat(response.getProvider()).isEqualTo("vstable-assistant");
    }

    @Test
    @DisplayName("Unauthenticated or null principal: throws BadCredentialsException")
    void chat_whenUnauthenticated_throwsBadCredentialsException() {
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Olá")
                .build();

        assertThatThrownBy(() -> supportChatService.chat(null, request))
                .isInstanceOf(BadCredentialsException.class);

        Authentication authWithNullPrincipal = new UsernamePasswordAuthenticationToken(null, null);
        assertThatThrownBy(() -> supportChatService.chat(authWithNullPrincipal, request))
                .isInstanceOf(BadCredentialsException.class);
    }
}
