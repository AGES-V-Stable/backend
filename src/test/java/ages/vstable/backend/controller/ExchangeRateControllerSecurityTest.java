package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.exchangerate.ExchangeRateResponse;
import ages.vstable.backend.dto.exchangerate.ExchangeRatesResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.exchangerate.ExchangeRateService;
import ages.vstable.backend.service.exchangerate.ExchangeRateStatus;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExchangeRateController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class ExchangeRateControllerSecurityTest {

    private static final ExchangeRatesResponse RATES = new ExchangeRatesResponse(List.of(
            new ExchangeRateResponse("BRL", "USDC", "5.47000000",
                    OffsetDateTime.parse("2026-11-12T22:45:00Z"), ExchangeRateStatus.CURRENT),
            new ExchangeRateResponse("BRL", "USDT", "5.40000000",
                    OffsetDateTime.parse("2026-11-12T17:00:00Z"), ExchangeRateStatus.STALE),
            new ExchangeRateResponse("BRL", "BRLA", null, null, ExchangeRateStatus.UNAVAILABLE)), 7200);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

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

    private static RequestPostProcessor authenticated(UserEntity user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/v1/exchange-rates")).andExpect(status().isUnauthorized());

        verifyNoInteractions(exchangeRateService);
    }

    @Test
    void companyUser_getsTheRatesWithDecimalsAsStrings() throws Exception {
        // Arrange
        when(exchangeRateService.currentRates()).thenReturn(RATES);

        // Act + Assert
        mockMvc.perform(get("/v1/exchange-rates")
                        .with(authenticated(TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlSeconds").value(7200))
                .andExpect(jsonPath("$.rates.length()").value(3))
                .andExpect(jsonPath("$.rates[0].baseCurrency").value("BRL"))
                .andExpect(jsonPath("$.rates[0].quoteCurrency").value("USDC"))
                .andExpect(jsonPath("$.rates[0].rate").value("5.47000000"))
                .andExpect(jsonPath("$.rates[0].fetchedAt").value("2026-11-12T22:45:00Z"))
                .andExpect(jsonPath("$.rates[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.rates[1].status").value("STALE"))
                .andExpect(jsonPath("$.rates[2].rate").doesNotExist())
                .andExpect(jsonPath("$.rates[2].fetchedAt").doesNotExist())
                .andExpect(jsonPath("$.rates[2].status").value("UNAVAILABLE"));
    }

    @Test
    void userWithoutCompany_alsoGetsTheRates() throws Exception {
        // Arrange: as cotações são globais e não dependem de vínculo com empresa.
        when(exchangeRateService.currentRates()).thenReturn(RATES);
        UserEntity withoutCompany = UserEntity.builder().id(UUID.randomUUID()).build();

        // Act + Assert
        mockMvc.perform(get("/v1/exchange-rates").with(authenticated(withoutCompany)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.length()").value(3));
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void administrator_getsTheSameRates() throws Exception {
        // Arrange
        when(exchangeRateService.currentRates()).thenReturn(RATES);

        // Act + Assert
        mockMvc.perform(get("/v1/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].rate").value("5.47000000"));
    }
}
