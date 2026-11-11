package ages.vstable.backend.controller;

import ages.vstable.backend.dto.support.SupportChatResponseDTO;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.service.SupportChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.LockedException;
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
    @DisplayName("When authenticated user sends valid message, returns 200 with response payload")
    void chat_whenAuthenticatedUserAndValid_returns200() throws Exception {
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
    @DisplayName("When authenticated admin sends message, returns 200")
    void chat_whenAuthenticatedAdmin_returns200() throws Exception {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Admin")
                .email("admin@vstable.com")
                .build();
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(admin, null, List.of());

        when(supportChatService.chat(any(), any())).thenReturn(
                SupportChatResponseDTO.builder()
                        .reply("Olá Administrador.")
                        .provider("vstable-assistant")
                        .timestamp(OffsetDateTime.now())
                        .build()
        );

        mockMvc.perform(post("/v1/support/chat")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Quais são os status de compliance?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Olá Administrador."))
                .andExpect(jsonPath("$.provider").value("vstable-assistant"));
    }

    @Test
    @DisplayName("When request payload is invalid or message is blank, returns 400 Bad Request")
    void chat_whenInvalidPayload_returns400() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("u@v.com").build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());

        mockMvc.perform(post("/v1/support/chat")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("When rate limit is exceeded, returns 423 Locked")
    void chat_whenRateLimitExceeded_returns423() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("rate@v.com").build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());

        when(supportChatService.chat(any(), any()))
                .thenThrow(new LockedException("Limite de mensagens atingido."));

        mockMvc.perform(post("/v1/support/chat")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Mensagem repetida"
                                }
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value("Limite de mensagens atingido."));
    }

    @Test
    @DisplayName("When unauthenticated, returns 401 Unauthorized")
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
