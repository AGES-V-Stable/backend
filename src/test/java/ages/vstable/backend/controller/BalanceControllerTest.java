package ages.vstable.backend.controller;

import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.service.BalanceService;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import java.math.BigDecimal;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BalanceControllerTest {

    @Mock
    private BalanceService service;

    @InjectMocks
    private BalanceController controller;

    private MockMvc mockMvc;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Authentication authenticated(Object principal) {
        return new UsernamePasswordAuthenticationToken(principal, null);
    }

    private UserEntity companyUser(UUID userCompanyId) {
        return TestCompanyUsers.member(userCompanyId, CompanyRole.OWNER);
    }

    @Test
    void getBalance_returnsTheBalanceOfTheAuthenticatedUsersCompany() throws Exception {
        when(service.getBalance(companyId)).thenReturn(new BalanceResponse("485200.00"));

        mockMvc.perform(get("/v1/balance").principal(authenticated(companyUser(companyId))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.availableBalanceBrl").value("485200.00"));
    }

    @Test
    void getBalance_isForbiddenForAdministrators() throws Exception {
        Authentication admin = authenticated(new AdministratorEntity());

        mockMvc.perform(get("/v1/balance").principal(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void getBalance_isForbiddenForUsersWithoutCompany() throws Exception {
        UserEntity withoutCompany = UserEntity.builder().id(UUID.randomUUID()).build();

        mockMvc.perform(get("/v1/balance").principal(authenticated(withoutCompany)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void getBalance_isAllowedForViewers() throws Exception {
        when(service.getBalance(companyId)).thenReturn(new BalanceResponse("485200.00"));

        mockMvc.perform(get("/v1/balance")
                        .principal(authenticated(TestCompanyUsers.member(companyId, CompanyRole.VIEWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalanceBrl").value("485200.00"));
    }

    @Test
    void getBalance_isForbiddenWhenTheMembershipWasRemoved() throws Exception {
        mockMvc.perform(get("/v1/balance")
                        .principal(authenticated(TestCompanyUsers.removedMember(companyId, CompanyRole.OPERATOR))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(service);
    }

    @Test
    void getBalance_isForbiddenForUsersWithCompanyButNoMembership() throws Exception {
        UserEntity withoutMembership = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();

        mockMvc.perform(get("/v1/balance").principal(authenticated(withoutMembership)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void getBalance_returnsNotFoundWhenCompanyDoesNotExist() throws Exception {
        when(service.getBalance(companyId)).thenThrow(new NotFoundException("Company not found"));

        mockMvc.perform(get("/v1/balance").principal(authenticated(companyUser(companyId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void addMockTopUp_usesExistingServiceAndReturnsCreditAndUpdatedBalance() throws Exception {
        UserEntity user = companyUser(companyId);
        when(service.addBalance(user, new BigDecimal("10.25")))
                .thenReturn(new BalanceResponse("12.75"));

        mockMvc.perform(post("/v1/balance/mock-top-ups")
                        .principal(authenticated(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountBrl": "10.25"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditedAmountBrl").value("10.25"))
                .andExpect(jsonPath("$.availableBalanceBrl").value("12.75"));
    }

    @Test
    void addMockTopUp_doesNotAcceptLegacyAmountField() throws Exception {
        mockMvc.perform(post("/v1/balance/mock-top-ups")
                        .principal(authenticated(companyUser(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "10.25"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void addMockTopUp_doesNotCreditForInvalidPrecision() throws Exception {
        mockMvc.perform(post("/v1/balance/mock-top-ups")
                        .principal(authenticated(companyUser(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountBrl": "10.251"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void addMockTopUp_doesNotCreditZeroOrNegativeAmounts() throws Exception {
        for (String amount : new String[] {"0.00", "-1.00"}) {
            mockMvc.perform(post("/v1/balance/mock-top-ups")
                            .principal(authenticated(companyUser(companyId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"amountBrl\\":\\"" + amount + "\\"}"))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(service);
    }

    @Test
    void addMockTopUp_doesNotCreditWhenNumericCapacityIsExceeded() throws Exception {
        mockMvc.perform(post("/v1/balance/mock-top-ups")
                        .principal(authenticated(companyUser(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountBrl": "10000000000000.00"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void addSimulatedBalance_rejectsZeroBeforeCallingService() throws Exception {
        mockMvc.perform(post("/v1/balance/simulated-deposits")
                        .principal(authenticated(companyUser(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "0.00"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void addSimulatedBalance_returnsUpdatedBalanceForAuthenticatedUser() throws Exception {
        UserEntity user = companyUser(companyId);

        when(service.addBalance(user, new BigDecimal("20.50")))
            .thenReturn(new BalanceResponse("120.50"));

        mockMvc.perform(post("/v1/balance/simulated-deposits")
                        .principal(authenticated(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "20.50"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalanceBrl").value("120.50"));
    }

}
