package ages.vstable.backend.service;

import ages.vstable.backend.dto.support.SupportChatMessageDTO;
import ages.vstable.backend.dto.support.SupportChatRequestDTO;
import ages.vstable.backend.dto.support.SupportChatResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.external.ai.SupportChatClient;
import ages.vstable.backend.external.ai.SupportChatGateway;
import ages.vstable.backend.external.ai.SupportChatProperties;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        TestCompanyUsers.withMembership(user, companyId, CompanyRole.OWNER, MembershipStatus.ACTIVE);

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
    @DisplayName("Active membership whose company is not found in repository: falls back to unlinked status")
    void chat_whenUserCompanyNotFound_includesUnlinkedStatus() {
        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("User Empresa Inexistente")
                .email("missing.company@vstable.com")
                .companyId(companyId)
                .twoFactorEnabled(true)
                .build();
        TestCompanyUsers.withMembership(user, companyId, CompanyRole.VIEWER, MembershipStatus.ACTIVE);

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
        TestCompanyUsers.withMembership(user, companyId, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);

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

    /** Verifica o contexto efetivamente entregue ao provedor de IA, conforme o vínculo carregado na requisição. */
    @Nested
    class CompanyContextSentToProvider {

        private static final String UNLINKED = "Empresa ainda não vinculada";

        @Mock
        private SupportChatGateway gateway;

        private SupportChatService service;

        @BeforeEach
        void setUp() {
            service = new SupportChatService(gateway, companyRepository);
            when(gateway.generateReply(anyString(), anyList(), anyString(), anyString()))
                    .thenReturn(new SupportChatGateway.SupportChatGatewayResult("ok", "test"));
        }

        @Test
        @DisplayName("Active membership: company data of the membership goes to the provider")
        void activeMembership_sendsCompanyContextOfTheMembershipCompany() {
            // Arrange: a empresa legada da conta difere da empresa do vínculo ativo.
            UUID legacyCompanyId = UUID.randomUUID();
            UUID membershipCompanyId = UUID.randomUUID();
            UserEntity user = user(legacyCompanyId);
            TestCompanyUsers.withMembership(user, membershipCompanyId, CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
            when(companyRepository.findById(membershipCompanyId)).thenReturn(Optional.of(company(membershipCompanyId)));

            // Act
            ProviderInput input = chatAndCapture(user);

            // Assert
            assertThat(input.userContextSummary())
                    .contains("Empresa Ativa Ltda")
                    .contains("KYB = `APPROVED`")
                    .contains("AML = `UNDER_REVIEW`")
                    .doesNotContain(UNLINKED);
            assertThat(input.systemPrompt()).contains("Empresa Ativa Ltda");
            verify(companyRepository).findById(membershipCompanyId);
        }

        @Test
        @DisplayName("Removed membership: no company data is loaded nor sent to the provider")
        void removedMembership_neverLoadsNorSendsCompanyData() {
            // Arrange: vínculo removido e a mesma empresa ainda gravada na conta.
            UUID companyId = UUID.randomUUID();
            UserEntity user = user(companyId);
            TestCompanyUsers.withMembership(user, companyId, CompanyRole.OWNER, MembershipStatus.REMOVED);

            // Act
            ProviderInput input = chatAndCapture(user);

            // Assert
            assertNoCompanyContext(input);
        }

        @Test
        @DisplayName("No membership but legacy companyId: legacy company is not used as fallback")
        void legacyCompanyIdWithoutMembership_isNotUsedAsFallback() {
            // Arrange
            UserEntity user = user(UUID.randomUUID());

            // Act
            ProviderInput input = chatAndCapture(user);

            // Assert
            assertNoCompanyContext(input);
        }

        @Test
        @DisplayName("User without any company: unlinked status is sent to the provider")
        void userWithoutCompany_sendsUnlinkedStatus() {
            // Arrange
            UserEntity user = user(null);

            // Act
            ProviderInput input = chatAndCapture(user);

            // Assert
            assertNoCompanyContext(input);
        }

        private void assertNoCompanyContext(ProviderInput input) {
            assertThat(input.userContextSummary())
                    .contains(UNLINKED)
                    .contains("2FA = Ativo")
                    .doesNotContain("Compliance KYB")
                    .doesNotContain("AML");
            assertThat(input.systemPrompt()).contains(UNLINKED);
            verifyNoInteractions(companyRepository);
        }

        private ProviderInput chatAndCapture(UserEntity user) {
            Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            service.chat(auth, SupportChatRequestDTO.builder().message("Qual o status do meu KYB?").build());

            ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
            verify(gateway).generateReply(systemPrompt.capture(), any(), eq("Qual o status do meu KYB?"),
                    summary.capture());
            return new ProviderInput(systemPrompt.getValue(), summary.getValue());
        }

        private UserEntity user(UUID legacyCompanyId) {
            return UserEntity.builder()
                    .id(UUID.randomUUID())
                    .companyId(legacyCompanyId)
                    .fullName("Usuário Chat")
                    .email(UUID.randomUUID() + "@vstable.com")
                    .twoFactorEnabled(true)
                    .build();
        }

        private CompanyEntity company(UUID id) {
            return CompanyEntity.builder()
                    .id(id)
                    .legalName("Empresa Ativa Ltda")
                    .kybStatus(ComplianceStatus.APPROVED)
                    .amlStatus(ComplianceStatus.UNDER_REVIEW)
                    .build();
        }
    }

    private record ProviderInput(String systemPrompt, String userContextSummary) {
    }
}
