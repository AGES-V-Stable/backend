package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.exception.InvalidDateRangeException;
import ages.vstable.backend.service.TransactionQueryService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private static RequestPostProcessor companyUser(UUID companyId, CompanyRole role) {
        return authenticated(TestCompanyUsers.member(companyId, role));
    }

    private static RequestPostProcessor authenticated(UserEntity user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void testClienteAcessandoRotaCliente_Success() throws Exception {
        UUID companyId = UUID.randomUUID();

        Page<TransactionHistoryResponseDTO> mockPage = new PageImpl<>(List.of());
        when(transactionQueryService.getHistory(eq(companyId), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/transferencias").with(companyUser(companyId, CompanyRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(transactionQueryService).getHistory(eq(companyId), any(), any());
    }

    @ParameterizedTest
    @EnumSource(CompanyRole.class)
    void testMembroAtivoDeQualquerPerfil_ConsultaOHistoricoDaPropriaEmpresa(CompanyRole role) throws Exception {
        UUID companyId = UUID.randomUUID();
        when(transactionQueryService.getHistory(eq(companyId), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/transferencias").with(companyUser(companyId, role)))
                .andExpect(status().isOk());

        verify(transactionQueryService).getHistory(eq(companyId), any(), any());
    }

    @Test
    void testUsuarioSemEmpresa_Returns403SemConsultarTransacoes() throws Exception {
        // Sem empresa, o filtro de escopo seria omitido e a consulta cobriria todas as empresas.
        UserEntity semEmpresa = UserEntity.builder().id(UUID.randomUUID()).build();

        mockMvc.perform(get("/api/transferencias").with(authenticated(semEmpresa)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    void testUsuarioComEmpresaMasSemVinculo_Returns403SemConsultarTransacoes() throws Exception {
        UserEntity semVinculo = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(get("/api/transferencias").with(authenticated(semVinculo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    void testVinculoRemovido_Returns403AcessoRevogadoSemConsultarTransacoes() throws Exception {
        UserEntity removido = TestCompanyUsers.removedMember(UUID.randomUUID(), CompanyRole.OWNER);

        mockMvc.perform(get("/api/transferencias").with(authenticated(removido)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void testAdminNaRotaCliente_Returns403SemConsultarTransacoes() throws Exception {
        mockMvc.perform(get("/api/transferencias"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    void testClienteComPeriodo_RepassaDatasAoFiltro() throws Exception {
        UUID companyId = UUID.randomUUID();
        when(transactionQueryService.getHistory(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/transferencias")
                        .param("startDate", "2026-01-01T00:00:00-03:00")
                        .param("endDate", "2026-01-31T23:59:59-03:00")
                        .with(companyUser(companyId, CompanyRole.OWNER)))
                .andExpect(status().isOk());

        ArgumentCaptor<TransactionFilterDTO> filter = ArgumentCaptor.forClass(TransactionFilterDTO.class);
        verify(transactionQueryService).getHistory(eq(companyId), filter.capture(), any());
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00-03:00"), filter.getValue().getStartDate());
        assertEquals(OffsetDateTime.parse("2026-01-31T23:59:59-03:00"), filter.getValue().getEndDate());
    }

    static Stream<Arguments> rotasComPeriodo() {
        return Stream.of(
                Arguments.of("/api/transferencias", companyUser(UUID.randomUUID(), CompanyRole.OWNER)),
                Arguments.of("/api/admin/transferencias", user("admin@teste.com").roles("ADMIN")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rotasComPeriodo")
    void testPeriodoInvalido_Returns400ComCodigo(String path, RequestPostProcessor principal) throws Exception {
        when(transactionQueryService.getHistory(any(), any(), any())).thenThrow(new InvalidDateRangeException());

        mockMvc.perform(get(path)
                        .param("startDate", "2026-02-01T00:00:00-03:00")
                        .param("endDate", "2026-01-01T00:00:00-03:00")
                        .with(principal))
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

        verifyNoInteractions(transactionQueryService);
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
    }

    @ParameterizedTest
    @WithAnonymousUser
    @ValueSource(strings = {"/api/transferencias", "/api/admin/transferencias"})
    void testAcessoSemAutenticacao_Returns401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionQueryService);
    }
}
