package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.service.BalanceService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;

import java.util.UUID;

import java.math.BigDecimal;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BalanceController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class BalanceControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private BalanceService balanceService;

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
    void getBalance_withoutAuthenticationReturns401AndDoesNotReadTheBalance() throws Exception {
        mockMvc.perform(get("/v1/balance"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(balanceService);
    }

    @Test
    void getBalance_withAuthenticatedCompanyUserReturnsTheBalance() throws Exception {
        UUID companyId = UUID.randomUUID();
        UserEntity user = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        when(balanceService.getBalance(companyId)).thenReturn(new BalanceResponse("12.75"));

        mockMvc.perform(get("/v1/balance").with(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalanceBrl").value("12.75"));
    }

    @ParameterizedTest
    @EnumSource(CompanyRole.class)
    void getBalance_withActiveMemberOfAnyRoleReturnsTheBalance(CompanyRole role) throws Exception {
        UUID companyId = UUID.randomUUID();
        when(balanceService.getBalance(companyId)).thenReturn(new BalanceResponse("12.75"));

        mockMvc.perform(get("/v1/balance").with(authenticated(TestCompanyUsers.member(companyId, role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalanceBrl").value("12.75"));
    }

    @Test
    void getBalance_withRemovedMembershipReturns403AccessRevokedAndDoesNotReadTheBalance() throws Exception {
        UserEntity user = TestCompanyUsers.removedMember(UUID.randomUUID(), CompanyRole.OWNER);

        mockMvc.perform(get("/v1/balance").with(authenticated(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(balanceService);
    }

    @Test
    void getBalance_withUserWithoutMembershipReturns403AccessDeniedAndDoesNotReadTheBalance() throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(get("/v1/balance").with(authenticated(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(balanceService);
    }

    private static RequestPostProcessor authenticated(UserEntity user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void addSimulatedBalance_withoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(post("/v1/balance/simulated-deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "20.50"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(balanceService);
    }

    @Test
    void addSimulatedBalance_withAuthenticatedCompanyUserReturnsUpdatedBalance() throws Exception {
        UserEntity user = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER);

        when(balanceService.addBalance(user, new BigDecimal("20.50")))
                .thenReturn(new BalanceResponse("120.50"));

        mockMvc.perform(post("/v1/balance/simulated-deposits")
                        .with(authenticated(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "20.50"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalanceBrl").value("120.50"));
    }

}
