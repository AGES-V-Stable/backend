package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferExecutionResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.exception.QuotePriceChangedException;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.transfer.TransferExecutionService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferExecutionController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class TransferExecutionControllerSecurityTest {

    private static final String CONFIRMATION = """
            {"sourceAmount": 1000.00, "targetAmount": 990.00}
            """;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private TransferExecutionService executionService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    private UserEntity principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        principal = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(UUID.randomUUID())
                .email("user@example.com")
                .passwordHash("hash")
                .passwordSalt("salt")
                .build();
    }

    @Test
    void confirm_authenticatedUser_returnsTransferWithPixInstructions() throws Exception {
        UUID offerId = UUID.randomUUID();
        TransferExecutionResponse response = response(offerId, TransferExecutionStatus.AWAITING_FUNDS, "pix-code");
        when(executionService.confirm(same(principal), eq(offerId), any())).thenReturn(response);

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", offerId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(response.executionId().toString()))
                .andExpect(jsonPath("$.provider").value("BLINDPAY"))
                .andExpect(jsonPath("$.status").value("AWAITING_FUNDS"))
                .andExpect(jsonPath("$.fundingInstructions").value("pix-code"));
    }

    @Test
    void confirm_withoutConfirmedAmounts_returns400() throws Exception {
        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAmount\": 1000.00}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(executionService);
    }

    @Test
    void confirm_priceChanged_returns409() throws Exception {
        when(executionService.confirm(any(), any(), any()))
                .thenThrow(new QuotePriceChangedException("The price changed"));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isConflict());
    }

    @Test
    void confirm_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(executionService);
    }

    @Test
    void confirm_administratorPrincipal_returns403() throws Exception {
        var administrator = User.withUsername("admin@example.com").password("password").roles("ADMIN").build();

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden());

        verifyNoInteractions(executionService);
    }

    @Test
    void find_authenticatedUser_returnsStatus() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionService.find(same(principal), eq(executionId)))
                .thenReturn(response(UUID.randomUUID(), TransferExecutionStatus.COMPLETED, null));

        mockMvc.perform(get("/v1/transfer-executions/{id}", executionId).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void find_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/v1/transfer-executions/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(executionService);
    }

    @Test
    void confirmRequote_administratorPrincipal_returns403() throws Exception {
        var administrator = User.withUsername("admin@example.com").password("password").roles("ADMIN").build();

        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden());

        verifyNoInteractions(executionService);
    }

    @Test
    void confirmRequote_authenticatedUser_returnsTransfer() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionService.confirmRequote(same(principal), eq(executionId), any()))
                .thenReturn(response(UUID.randomUUID(), TransferExecutionStatus.PAYOUT_PROCESSING, null));

        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", executionId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYOUT_PROCESSING"));
    }

    private static TransferExecutionResponse response(
            UUID offerId, TransferExecutionStatus status, String fundingInstructions) {
        return new TransferExecutionResponse(UUID.randomUUID(), offerId, UUID.randomUUID(), "BLINDPAY", status,
                new MoneyAmount(new BigDecimal("1000.00"), "BRL"), new MoneyAmount(new BigDecimal("990.00"), "USD"),
                new BigDecimal("2.50"), fundingInstructions, null, null,
                OffsetDateTime.parse("2026-10-28T12:00:00Z"), OffsetDateTime.parse("2026-10-28T12:00:00Z"));
    }
}
