package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.dashboard.DashboardSeriesResponse;
import ages.vstable.backend.dto.dashboard.DashboardSettlementResponse;
import ages.vstable.backend.dto.dashboard.DashboardSummaryResponse;
import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.service.TransactionVolumeService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.dashboard.DashboardPeriod;
import ages.vstable.backend.service.dashboard.DashboardService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DashboardController.class, AdminDashboardController.class})
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class DashboardControllerSecurityTest {

    private static final DashboardPeriod ALL_TIME = new DashboardPeriod(null, null);
    private static final DashboardSeriesResponse EMPTY_SERIES =
            new DashboardSeriesResponse("BRL", "MONTH", null, null, "0.00", List.of(), 0);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private TransactionVolumeService transactionVolumeService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private static RequestPostProcessor companyUser(UUID userCompanyId) {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(userCompanyId).build();
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/dashboard/summary", "/v1/dashboard/remittance-volume", "/v1/dashboard/savings-history",
            "/v1/dashboard/settlements", "/v1/admin/dashboard/summary", "/v1/admin/dashboard/remittance-volume",
            "/v1/admin/dashboard/savings-history", "/v1/admin/dashboard/settlements",
            "/v1/admin/dashboard/transaction-volume"})
    void withoutAuthentication_returns401(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());

        verifyNoInteractions(dashboardService, transactionVolumeService);
    }

    @Test
    void companyUser_getsTheSummaryOfTheirOwnCompany() throws Exception {
        when(dashboardService.summary(companyId, new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))))
                .thenReturn(new DashboardSummaryResponse("BRL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                        "31450.00", "4.00", "0.45", false, 0));

        mockMvc.perform(get("/v1/dashboard/summary")
                        .param("startDate", "2026-01-01").param("endDate", "2026-06-30")
                        .with(companyUser(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.startDate").value("2026-01-01"))
                .andExpect(jsonPath("$.amount").value("31450.00"))
                .andExpect(jsonPath("$.referenceSpreadPercentage").value("4.00"))
                .andExpect(jsonPath("$.averageEffectiveSpreadPercentage").value("0.45"))
                .andExpect(jsonPath("$.incomplete").value(false))
                .andExpect(jsonPath("$.incompleteOperations").value(0));
    }

    @Test
    void companyUser_cannotChooseAnotherCompanyThroughTheQueryString() throws Exception {
        UUID otherCompany = UUID.randomUUID();
        when(dashboardService.remittanceVolume(companyId, ALL_TIME)).thenReturn(EMPTY_SERIES);

        mockMvc.perform(get("/v1/dashboard/remittance-volume")
                        .param("companyId", otherCompany.toString())
                        .with(companyUser(companyId)))
                .andExpect(status().isOk());

        verify(dashboardService).remittanceVolume(companyId, ALL_TIME);
    }

    @Test
    void companyUser_getsTheSeriesContract() throws Exception {
        when(dashboardService.savingsHistory(companyId, ALL_TIME)).thenReturn(new DashboardSeriesResponse(
                "BRL", "MONTH", null, null, "7.20", List.of(new DashboardSeriesResponse.Point("2026-08", "7.20")), 1));

        mockMvc.perform(get("/v1/dashboard/savings-history").with(companyUser(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("MONTH"))
                .andExpect(jsonPath("$.total").value("7.20"))
                .andExpect(jsonPath("$.points[0].month").value("2026-08"))
                .andExpect(jsonPath("$.points[0].amount").value("7.20"))
                .andExpect(jsonPath("$.incompleteOperations").value(1));
    }

    @Test
    void companyUser_getsThePagedSettlementsContract() throws Exception {
        UUID id = UUID.randomUUID();
        DashboardSettlementResponse settlement = new DashboardSettlementResponse(
                id, companyId, "Empresa A", OffsetDateTime.parse("2026-08-23T17:41:00-03:00"),
                OffsetDateTime.parse("2026-08-23T17:00:00-03:00"), TransferDirection.PAYMENT, "Shenzhen AeroCrop",
                "CN • Agricultural Bank of China", "10000.00", "USD", "54200.00", "5.420000", "120.00", "1996.80",
                TransactionStatus.SETTLED);
        when(dashboardService.settlements(companyId, ALL_TIME, 1, 10))
                .thenReturn(new PageImpl<>(List.of(settlement), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/v1/dashboard/settlements").param("page", "1").param("size", "10")
                        .with(companyUser(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].direction").value("PAYMENT"))
                .andExpect(jsonPath("$.content[0].counterparty").value("Shenzhen AeroCrop"))
                .andExpect(jsonPath("$.content[0].amount").value("10000.00"))
                .andExpect(jsonPath("$.content[0].currency").value("USD"))
                .andExpect(jsonPath("$.content[0].status").value("SETTLED"))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void companyUser_withoutCompany_returns403WithoutReadingMetrics() throws Exception {
        mockMvc.perform(get("/v1/dashboard/summary").with(companyUser(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void companyUser_onAdministrativeRoute_returns403() throws Exception {
        mockMvc.perform(get("/v1/admin/dashboard/summary").with(companyUser(companyId)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(dashboardService, transactionVolumeService);
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_onCompanyRoute_returns403() throws Exception {
        mockMvc.perform(get("/v1/dashboard/summary")).andExpect(status().isForbidden());

        verifyNoInteractions(dashboardService);
    }

    @Test
    void startDateAfterEndDate_returns400WithoutReadingMetrics() throws Exception {
        mockMvc.perform(get("/v1/dashboard/settlements")
                        .param("startDate", "2026-02-01").param("endDate", "2026-01-01")
                        .with(companyUser(companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void dateBeyondTheAcceptedRange_returns400InsteadOfFailing() throws Exception {
        mockMvc.perform(get("/v1/dashboard/savings-history")
                        .param("startDate", "2000-01-01").param("endDate", "+999999999-12-31")
                        .with(companyUser(companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void malformedDate_returns400() throws Exception {
        mockMvc.perform(get("/v1/dashboard/summary").param("startDate", "01/01/2026").with(companyUser(companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_withoutCompany_getsTheConsolidatedView() throws Exception {
        when(dashboardService.savingsHistory(null, ALL_TIME)).thenReturn(EMPTY_SERIES);

        mockMvc.perform(get("/v1/admin/dashboard/savings-history")).andExpect(status().isOk());

        verify(dashboardService).requireCompany(null);
        verify(dashboardService).savingsHistory(null, ALL_TIME);
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_withCompany_getsThatCompany() throws Exception {
        when(dashboardService.settlements(companyId, ALL_TIME, 0, 5)).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/v1/admin/dashboard/settlements").param("companyId", companyId.toString()))
                .andExpect(status().isOk());

        verify(dashboardService).requireCompany(companyId);
        verify(dashboardService).settlements(companyId, ALL_TIME, 0, 5);
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_getsTheSummaryAndVolumeSeriesOfTheChosenCompany() throws Exception {
        DashboardPeriod june = new DashboardPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        when(dashboardService.summary(companyId, june)).thenReturn(new DashboardSummaryResponse(
                "BRL", june.startDate(), june.endDate(), "2825.60", "4.00", null, false, 0));
        when(dashboardService.remittanceVolume(companyId, june)).thenReturn(EMPTY_SERIES);

        mockMvc.perform(get("/v1/admin/dashboard/summary").param("companyId", companyId.toString())
                        .param("startDate", "2026-06-01").param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("2825.60"));
        mockMvc.perform(get("/v1/admin/dashboard/remittance-volume").param("companyId", companyId.toString())
                        .param("startDate", "2026-06-01").param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isEmpty());

        verify(dashboardService, org.mockito.Mockito.times(2)).requireCompany(companyId);
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_withUnknownCompany_returns404() throws Exception {
        doThrow(new NotFoundException("Company not found")).when(dashboardService).requireCompany(companyId);

        mockMvc.perform(get("/v1/admin/dashboard/summary").param("companyId", companyId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_getsTheTransactionVolumeOfTheChosenScope() throws Exception {
        when(transactionVolumeService.getSettledVolume(isNull(), eq(LocalDate.of(2026, 1, 1)), any()))
                .thenReturn(new TransactionVolumeResponse("2840000.00"));

        mockMvc.perform(get("/v1/admin/dashboard/transaction-volume").param("startDate", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactedVolumeBrl").value("2840000.00"));
    }
}
