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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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
                .contains("Perfil = Representante | 2FA = Ativo")
                .doesNotContain("OWNER")
                .doesNotContain("Papel =")
                .doesNotContain("Compliance KYB");
        assertThat(response.getProvider()).isEqualTo("vstable-assistant");
        assertThat(response.getSuggestedQuestions())
                .isNotEmpty()
                .allSatisfy(question -> assertThat(question)
                        .doesNotContainIgnoringCase("avenia")
                        .doesNotContainIgnoringCase("blindpay")
                        .doesNotContainIgnoringCase("owner")
                        .doesNotContainIgnoringCase("operator")
                        .doesNotContainIgnoringCase("viewer"));
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
                .hasMessageContaining("Limite de mensagens atingido. Aguarde ")
                .hasMessageContaining("segundos")
                .hasMessageMatching("Limite de mensagens atingido\\. Aguarde \\d+ segundos antes de enviar nova pergunta\\.");
    }

    @Test
    @DisplayName("When rate limit is exceeded with aged window start, should compute remaining retry-after seconds")
    @SuppressWarnings("unchecked")
    void chat_whenRateLimitExceededWithAgedTimestamp_computesRemainingSeconds() {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Teste Retry After")
                .email("retryafter@vstable.com")
                .twoFactorEnabled(true)
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona o 2FA?")
                .build();

        Map<String, Deque<Instant>> timestampsByUser = (Map<String, Deque<Instant>>) org.springframework.test.util.ReflectionTestUtils
                .getField(supportChatService, "requestTimestampsByUser");
        Deque<Instant> timestamps = new ArrayDeque<>();
        Instant now = Instant.now();
        timestamps.addLast(now.minusSeconds(55));
        for (int i = 1; i < SupportChatService.MAX_REQUESTS_PER_MINUTE; i++) {
            timestamps.addLast(now.minusSeconds(1));
        }
        timestampsByUser.put("user:retryafter@vstable.com", timestamps);

        assertThatThrownBy(() -> supportChatService.chat(auth, request))
                .isInstanceOf(LockedException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg)
                            .contains("Limite de mensagens atingido. Aguarde ")
                            .contains("segundos");
                    int seconds = Integer.parseInt(msg.replaceAll(".*Aguarde (\\d+) segundos.*", "$1"));
                    assertThat(seconds).isBetween(1, 10);
                });
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
                .contains("multi-provedor de liquidez")
                .contains("provedores parceiros regulados")
                .contains("Status da sua sessão (Administrador):")
                .contains("2FA = Ativo")
                .doesNotContainIgnoringCase("avenia")
                .doesNotContainIgnoringCase("blindpay")
                .doesNotContain("QuoteOrchestratorService")
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


                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                .message("Como funciona o compliance KYB e AML?")
                .build();

        SupportChatResponseDTO response = supportChatService.chat(auth, request);

        assertThat(response.getReply())
                .contains("Empresa Confidencial S.A.")
                .contains("Perfil = Membro com permissão para operar | 2FA = Pendente de ativação")
                .doesNotContain("OPERATOR")
                .doesNotContain("Papel =")
                .doesNotContain("Compliance KYB")
                .doesNotContain("AML =")
                .doesNotContain("12345678000199")
                .doesNotContain("$2a$10$hashUser")
                .doesNotContain("SECRETBASE32KEY");
    }

    @Test
    @DisplayName("Local assistant answers new platform topics using abstract user-friendly language without technical terms")
    void chat_localAssistant_answersNewPlatformTopics() {
        Authentication auth = new UsernamePasswordAuthenticationToken("service-user", null, List.of());

        SupportChatResponseDTO membersReply = supportChatService.chat(auth, SupportChatRequestDTO.builder()
                .message("Quais são os perfis de representante e membros que podem operar ou visualizar e como funcionam convites?")
                .build());
        assertThat(membersReply.getProvider()).isEqualTo("vstable-assistant");
        assertThat(membersReply.getReply())
                .contains("Representante")
                .contains("Membro com permissão para operar")
                .contains("Membro com acesso apenas de visualização")
                .contains("Termos de Uso e Política de Privacidade vigentes");
        assertNoForbiddenTechnicalTerms(membersReply.getReply());

        SupportChatResponseDTO dashboardReply = supportChatService.chat(auth, SupportChatRequestDTO.builder()
                .message("Como funciona o dashboard, economia estimada e comprovante em PDF?")
                .build());
        assertThat(dashboardReply.getProvider()).isEqualTo("vstable-assistant");
        assertThat(dashboardReply.getReply())
                .contains("BRL -> USDC")
                .contains("4%")
                .contains("comprovante em PDF")
                .contains("acompanhamento e atualização automática do status");
        assertNoForbiddenTechnicalTerms(dashboardReply.getReply());

        SupportChatResponseDTO kycReply = supportChatService.chat(auth, SupportChatRequestDTO.builder()
                .message("Como aceitar os termos e reiniciar meu KYC com documentos?")
                .build());
        assertThat(kycReply.getProvider()).isEqualTo("vstable-assistant");
        assertThat(kycReply.getReply())
                .contains("termos do provedor de pagamento")
                .contains("3 MB")
                .contains("reiniciar/refazer");
        assertNoForbiddenTechnicalTerms(kycReply.getReply());

        SupportChatResponseDTO totpReply = supportChatService.chat(auth, SupportChatRequestDTO.builder()
                .message("O código 2FA TOTP pode ser reutilizado?")
                .build());
        assertThat(totpReply.getProvider()).isEqualTo("vstable-assistant");
        assertThat(totpReply.getReply())
                .contains("uso único")
                .contains("janela de validade");
        assertNoForbiddenTechnicalTerms(totpReply.getReply());
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

    @Test
    @DisplayName("resolveRateLimitKey distinguishes users, admins, null emails, and generic principals")
    void resolveRateLimitKey_distinguishesPrincipalTypesAndNullEmails() {
        UserEntity userWithEmail = UserEntity.builder().email("Alice@VStable.com").build();
        UserEntity userWithoutEmail = UserEntity.builder().id(UUID.randomUUID()).email(null).build();
        AdministratorEntity adminWithEmail = AdministratorEntity.builder().email("Admin@VStable.com").build();
        AdministratorEntity adminWithoutEmail = AdministratorEntity.builder().id(UUID.randomUUID()).email(null).build();

        String userKey = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                supportChatService, "resolveRateLimitKey", userWithEmail);
        String userNullEmailKey = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                supportChatService, "resolveRateLimitKey", userWithoutEmail);
        String adminKey = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                supportChatService, "resolveRateLimitKey", adminWithEmail);
        String adminNullEmailKey = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                supportChatService, "resolveRateLimitKey", adminWithoutEmail);
        String genericKey = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                supportChatService, "resolveRateLimitKey", "service-account-1");

        assertThat(userKey).isEqualTo("user:alice@vstable.com");
        assertThat(userNullEmailKey).isEqualTo("principal:" + userWithoutEmail);
        assertThat(adminKey).isEqualTo("admin:admin@vstable.com");
        assertThat(adminNullEmailKey).isEqualTo("principal:" + adminWithoutEmail);
        assertThat(genericKey).isEqualTo("principal:service-account-1");
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
                    .contains("Perfil = Membro com permissão para operar | 2FA = Ativo")
                    .doesNotContain("OPERATOR")
                    .doesNotContain("KYB")
                    .doesNotContain("AML")
                    .doesNotContain(UNLINKED);
            assertThat(input.systemPrompt()).contains("Empresa Ativa Ltda");
            verify(companyRepository).findById(membershipCompanyId);
        }

        @Test
        @DisplayName("Active membership maps OWNER, OPERATOR, VIEWER and null role to abstract profiles without leaking PII or technical terms")
        void activeMembership_withSensitiveFields_includesAbstractRoleWithoutLeakingPiiOrTechnicalTerms() {
            UUID companyId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UserEntity user = UserEntity.builder()
                    .id(userId)
                    .companyId(companyId)
                    .fullName("Ana Carolina Silva")
                    .email("ana.silva@empresa.com.br")
                    .passwordHash("$2a$12$sensitivePasswordHashValue")
                    .passwordSalt("sensitiveSaltValue999")
                    .twoFactorSecret("SUPERSECRETTOTP32KEY")
                    .twoFactorEnabled(true)
                    .build();
            TestCompanyUsers.withMembership(user, companyId, CompanyRole.OWNER, MembershipStatus.ACTIVE);
            UUID membershipId = user.getMembership().getId();

            CompanyEntity company = CompanyEntity.builder()
                    .id(companyId)
                    .legalName("Empresa Segura S.A.")
                    .tradeName("Empresa Segura")
                    .cnpj("98765432000188")
                    .availableBalanceBrl(new BigDecimal("154320.99"))
                    .build();
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

            ProviderInput input = chatAndCapture(user);

            assertThat(input.userContextSummary())
                    .isEqualTo("**Status atual da sua conta (Empresa Segura):** Perfil = Representante | 2FA = Ativo.")
                    .doesNotContain("Ana Carolina Silva")
                    .doesNotContain("ana.silva@empresa.com.br")
                    .doesNotContain("98765432000188")
                    .doesNotContain("154320.99")
                    .doesNotContain("$2a$12$sensitivePasswordHashValue")
                    .doesNotContain("sensitiveSaltValue999")
                    .doesNotContain("SUPERSECRETTOTP32KEY")
                    .doesNotContain(userId.toString())
                    .doesNotContain(companyId.toString())
                    .doesNotContain(membershipId.toString());
            assertNoForbiddenTechnicalTerms(input.userContextSummary());

            assertThat(input.systemPrompt())
                    .contains("Perfil = Representante | 2FA = Ativo")
                    .contains("GESTÃO DE MEMBROS, PERFIS E CONVITES DA EMPRESA:")
                    .contains("Membro com permissão para operar")
                    .contains("Membro com acesso apenas de visualização")
                    .contains("Termos de Uso e Política de Privacidade vigentes")
                    .contains("CONTA FINANCEIRA EMPRESARIAL E SALDO EM BRL:")
                    .contains("ambiente de testes/simulação")
                    .contains("DASHBOARD, CÂMBIO DE REFERÊNCIA E ECONOMIA ESTIMADA:")
                    .contains("atualizada periodicamente no dashboard")
                    .contains("4%")
                    .contains("TERMOS DO PROVEDOR DE PAGAMENTO, KYC INDIVIDUAL E REABERTURA:")
                    .contains("3 MB")
                    .contains("COMPROVANTE EM PDF E ACOMPANHAMENTO DE STATUS:")
                    .contains("uso único dentro da sua janela de validade")
                    .doesNotContain("98765432000188")
                    .doesNotContain("154320.99")
                    .doesNotContain("SUPERSECRETTOTP32KEY")
                    .doesNotContain(userId.toString())
                    .doesNotContain(companyId.toString());
            assertNoForbiddenTechnicalTerms(input.systemPrompt());
        }

        @Test
        @DisplayName("Active membership maps VIEWER role to abstract read-only business label")
        void activeMembership_viewerRole_mapsToAbstractProfileLabel() {
            UUID companyId = UUID.randomUUID();
            CompanyEntity company = CompanyEntity.builder()
                    .id(companyId)
                    .legalName("Empresa Visualização Ltda")
                    .tradeName("Empresa Visualização")
                    .build();
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

            UserEntity viewer = user(companyId);
            TestCompanyUsers.withMembership(viewer, companyId, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
            ProviderInput viewerInput = chatAndCapture(viewer);
            assertThat(viewerInput.userContextSummary())
                    .isEqualTo("**Status atual da sua conta (Empresa Visualização):** Perfil = Membro com acesso apenas de visualização | 2FA = Ativo.");
            assertNoForbiddenTechnicalTerms(viewerInput.userContextSummary());
        }

        @Test
        @DisplayName("Active membership maps null role to abstract default Membro label")
        void activeMembership_nullRole_mapsToDefaultMemberLabel() {
            UUID companyId = UUID.randomUUID();
            CompanyEntity company = CompanyEntity.builder()
                    .id(companyId)
                    .legalName("Empresa Visualização Ltda")
                    .tradeName("Empresa Visualização")
                    .build();
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

            UserEntity nullRoleMember = user(companyId);
            TestCompanyUsers.withMembership(nullRoleMember, companyId, null, MembershipStatus.ACTIVE);
            ProviderInput nullRoleInput = chatAndCapture(nullRoleMember);
            assertThat(nullRoleInput.userContextSummary())
                    .isEqualTo("**Status atual da sua conta (Empresa Visualização):** Perfil = Membro | 2FA = Ativo.");
            assertNoForbiddenTechnicalTerms(nullRoleInput.userContextSummary());
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


                    .build();
        }
    }

    private static void assertNoForbiddenTechnicalTerms(String text) {
        assertThat(text)
                .doesNotContainIgnoringCase("OWNER")
                .doesNotContainIgnoringCase("OPERATOR")
                .doesNotContainIgnoringCase("VIEWER")
                .doesNotContainIgnoringCase("QuoteOrchestratorService")
                .doesNotContainIgnoringCase("BeneficiaryValidator")
                .doesNotContainIgnoringCase("TERMS_VERSION")
                .doesNotContainIgnoringCase("Idempotency-Key")
                .doesNotContainIgnoringCase("basePrice")
                .doesNotContainIgnoringCase("TTL")
                .doesNotContainIgnoringCase("sandbox")
                .doesNotContainIgnoringCase("homologação")
                .doesNotContainIgnoringCase("tickets Avenia")
                .doesNotContainIgnoringCase("controle atômico")
                .doesNotContainIgnoringCase("CRYPTO_WALLET")
                .doesNotContainIgnoringCase("Avenia")
                .doesNotContainIgnoringCase("BlindPay")
                .doesNotContainIgnoringCase("Bitso")
                .doesNotContainIgnoringCase("webhook")
                .doesNotContain("Compliance KYB")
                .doesNotContain("AML =")
                .doesNotMatch("(?s).*\\bToS\\b.*");
    }

    private record ProviderInput(String systemPrompt, String userContextSummary) {
    }
}
