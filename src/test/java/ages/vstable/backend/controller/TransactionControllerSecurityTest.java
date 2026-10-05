package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.TransactionQueryService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TransactionController.class, AdminTransactionController.class})
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class TransactionControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private TransactionQueryService transactionQueryService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = "cliente@teste.com", roles = {"USUARIO"})
    void testClienteAcessandoRotaCliente_Success() throws Exception {
        UserEntity mockUser = new UserEntity();
        mockUser.setCompanyId(UUID.randomUUID());
        
        when(userService.getByEmail("cliente@teste.com")).thenReturn(mockUser);
        
        Page<TransactionHistoryResponseDTO> mockPage = new PageImpl<>(List.of());
        when(transactionQueryService.getHistory(eq(mockUser.getCompanyId()), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/transferencias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(transactionQueryService).getHistory(eq(mockUser.getCompanyId()), any(), any());
    }

    @Test
    @WithMockUser(username = "cliente@teste.com", roles = {"USUARIO"})
    void testClienteAcessandoRotaAdmin_Returns403() throws Exception {
        // Usuário normal tentando acessar rota de admin (falta ROLE_ADMIN)
        mockMvc.perform(get("/api/admin/transferencias"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(transactionQueryService, userService);
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void testAdminAcessandoRotaAdmin_Success() throws Exception {
        Page<TransactionHistoryResponseDTO> mockPage = new PageImpl<>(List.of());
        when(transactionQueryService.getHistory(isNull(), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/admin/transferencias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(transactionQueryService).getHistory(isNull(), any(), any());
        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/transferencias", "/api/admin/transferencias"})
    void testAcessoSemAutenticacao_Returns401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionQueryService, userService);
    }
}
