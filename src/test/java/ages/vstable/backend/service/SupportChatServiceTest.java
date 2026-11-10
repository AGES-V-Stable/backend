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
        void chat_whenCompanyContextLoaded_neverExposesCnpjCpfOrTotpSecret() {
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
        void chat_whenUnauthenticated_throwsBadCredentialsException() {
                SupportChatRequestDTO request = SupportChatRequestDTO.builder()
                                .message("Olá")
                                .build();

                assertThatThrownBy(() -> supportChatService.chat(null, request))
                                .isInstanceOf(BadCredentialsException.class);
        }
}
