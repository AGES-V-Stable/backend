package ages.vstable.backend.service;

import ages.vstable.backend.dto.support.SupportChatRequestDTO;
import ages.vstable.backend.dto.support.SupportChatResponseDTO;
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
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

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
                RestClient.builder()
        );
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
}
