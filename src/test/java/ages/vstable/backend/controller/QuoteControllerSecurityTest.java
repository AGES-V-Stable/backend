package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.quote.QuoteOfferResponse;
import ages.vstable.backend.dto.quote.QuoteResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.quote.QuoteOrchestratorService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuoteController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class QuoteControllerSecurityTest {

    private static final String REQUEST_BODY = """
            {
              "beneficiaryId": "%s",
              "direction": "PAYOUT",
              "sourceCurrency": "USDC",
              "targetCurrency": "BRL",
              "sourcePaymentMethod": "BLOCKCHAIN",
              "targetPaymentMethod": "pix",
              "amount": 100.00,
              "amountSide": "SOURCE",
              "token": "USDC",
              "blockchainNetwork": "polygon",
              "coverFees": false,
              "description": "Invoice"
            }
            """;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private QuoteOrchestratorService quoteOrchestratorService;

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
    void quote_authenticatedUser_returnsBackendContract() throws Exception {
        UUID beneficiaryId = UUID.randomUUID();
        UUID quoteRequestId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        UserEntity principal = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(UUID.randomUUID())
                .email("user@example.com")
                .passwordHash("hash")
                .passwordSalt("salt")
                .build();
        when(quoteOrchestratorService.quote(same(principal), any())).thenReturn(new QuoteResponse(
                quoteRequestId,
                new QuoteOfferResponse(
                        offerId,
                        new BigDecimal("100.00"),
                        new BigDecimal("520.00"),
                        new BigDecimal("5.2000000000"),
                        new BigDecimal("1.50"),
                        OffsetDateTime.parse("2026-10-08T15:30:00Z"))));

        mockMvc.perform(post("/v1/quotes")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.formatted(beneficiaryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteRequestId").value(quoteRequestId.toString()))
                .andExpect(jsonPath("$.offer.offerId").value(offerId.toString()))
                .andExpect(jsonPath("$.offer.sourceAmount").value(100.00))
                .andExpect(jsonPath("$.offer.targetAmount").value(520.00));

        verify(quoteOrchestratorService).quote(same(principal), any());
    }

    @Test
    void quote_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(quoteOrchestratorService);
    }

    @Test
    void quote_administratorPrincipal_returns403() throws Exception {
        var administrator = User.withUsername("admin@example.com")
                .password("password")
                .roles("ADMIN")
                .build();

        mockMvc.perform(post("/v1/quotes")
                        .with(user(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(quoteOrchestratorService);
    }
}
