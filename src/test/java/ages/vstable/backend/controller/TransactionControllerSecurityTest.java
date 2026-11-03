package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.InvalidDateRangeException;
import ages.vstable.backend.service.TransactionQueryService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @WithMockUser(username = "cliente@teste.com")
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
    @WithMockUser(username = "sem-empresa@teste.com")
    void testUsuarioSemEmpresa_Returns403SemConsultarTransacoes() throws Exception {
        // Sem empresa, o filtro de escopo seria omitido e a consulta cobriria todas as empresas.
        when(userService.getByEmail("sem-empresa@teste.com")).thenReturn(new UserEntity());

        mockMvc.perform(get("/api/transferencias"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    @WithMockUser(username = "cliente@teste.com")
    void testClienteComPeriodo_RepassaDatasAoFiltro() throws Exception {
        UserEntity mockUser = new UserEntity();
        mockUser.setCompanyId(UUID.randomUUID());
        when(userService.getByEmail("cliente@teste.com")).thenReturn(mockUser);
        when(transactionQueryService.getHistory(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/transferencias")
                        .param("startDate", "2026-01-01T00:00:00-03:00")
                        .param("endDate", "2026-01-31T23:59:59-03:00"))
                .andExpect(status().isOk());

        ArgumentCaptor<TransactionFilterDTO> filter = ArgumentCaptor.forClass(TransactionFilterDTO.class);
        verify(transactionQueryService).getHistory(eq(mockUser.getCompanyId()), filter.capture(), any());
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00-03:00"), filter.getValue().getStartDate());
        assertEquals(OffsetDateTime.parse("2026-01-31T23:59:59-03:00"), filter.getValue().getEndDate());
    }

    @ParameterizedTest
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    @ValueSource(strings = {"/api/transferencias", "/api/admin/transferencias"})
    void testPeriodoInvalido_Returns400ComCodigo(String path) throws Exception {
        UserEntity mockUser = new UserEntity();
        mockUser.setCompanyId(UUID.randomUUID());
        when(userService.getByEmail("admin@teste.com")).thenReturn(mockUser);
        when(transactionQueryService.getHistory(any(), any(), any())).thenThrow(new InvalidDateRangeException());

        mockMvc.perform(get(path)
                        .param("startDate", "2026-02-01T00:00:00-03:00")
                        .param("endDate", "2026-01-01T00:00:00-03:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
                .andExpect(jsonPath("$.message").value("startDate must not be after endDate"));
    }

    @Test
    @WithMockUser(username = "cliente@teste.com")
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
    @WithAnonymousUser
    @ValueSource(strings = {"/api/transferencias", "/api/admin/transferencias"})
    void testAcessoSemAutenticacao_Returns401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionQueryService, userService);
    }
}
