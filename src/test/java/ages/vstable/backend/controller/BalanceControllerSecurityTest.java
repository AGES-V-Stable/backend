package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.BalanceService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();
        when(balanceService.getBalance(companyId)).thenReturn(new BalanceResponse("12.75"));

        mockMvc.perform(get("/v1/balance")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalanceBrl").value("12.75"));
    }
}
