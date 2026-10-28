package ages.vstable.backend.controller;

import ages.vstable.backend.dto.support.SupportChatResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.service.SupportChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class SupportChatControllerTest {

    @Mock
    private SupportChatService supportChatService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new SupportChatController(supportChatService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void chat_whenAuthenticatedAndValid_returns200() throws Exception {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Rodrigo")
                .email("rodrigo@vstable.com")
                .build();
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());

        when(supportChatService.chat(any(), any())).thenReturn(
                SupportChatResponseDTO.builder()
                        .reply("Transações em COP via SWIFT não estão disponíveis; utilize Wallet Cripto ou USD.")
                        .provider("gemini")
                        .suggestedQuestions(List.of("Como funciona o 2FA?"))
                        .timestamp(OffsetDateTime.now())
                        .build()
        );

        mockMvc.perform(post("/v1/support/chat")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Por que não consigo fazer transações com a Colômbia?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Transações em COP via SWIFT não estão disponíveis; utilize Wallet Cripto ou USD."))
                .andExpect(jsonPath("$.provider").value("gemini"))
                .andExpect(jsonPath("$.suggestedQuestions[0]").value("Como funciona o 2FA?"));
    }

    @Test
    void chat_whenUnauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/support/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Olá"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }
}
