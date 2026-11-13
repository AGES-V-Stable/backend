package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transfer.ConfirmOfferRequest;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferExecutionResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.QuotePriceChangedException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.transfer.TransferExecutionService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
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
            {"sourceAmount": 1000.00, "targetAmount": 990.00, "totpCode": "123456"}
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
        principal = member(CompanyRole.OWNER, MembershipStatus.ACTIVE);
    }

    private UserEntity member(CompanyRole role, MembershipStatus membershipStatus) {
        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .email("user@example.com")
                .passwordHash("hash")
                .passwordSalt("salt")
                .build();
        return TestCompanyUsers.withMembership(user, companyId, role, membershipStatus);
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
    void confirm_withoutTotpCode_returns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAmount\": 1000.00, \"targetAmount\": 990.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("totpCode: TOTP code is required"));

        verifyNoInteractions(executionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "1234567", "12a456", " 123456", "１２３４５６"})
    void confirm_malformedTotpCode_returns400WithoutCallingTheService(String totpCode) throws Exception {
        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationWith(totpCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(executionService);
    }

    @Test
    void confirm_incorrectOrReusedTotp_returns401() throws Exception {
        when(executionService.confirm(any(), any(), any()))
                .thenThrow(new BadCredentialsException("TOTP code has already been used"));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void confirm_totpVerificationLocked_returns423() throws Exception {
        when(executionService.confirm(any(), any(), any()))
                .thenThrow(new LockedException("Too many failed 2FA attempts. Try again later."));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void confirm_accountWithoutTwoFactor_returns422() throws Exception {
        when(executionService.confirm(any(), any(), any()))
                .thenThrow(new UnprocessableEntityException("Usuário não possui 2FA ativo"));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void confirm_offerAlreadyConfirmedByAnotherMember_returns409() throws Exception {
        when(executionService.confirm(any(), any(), any()))
                .thenThrow(new ConflictException("OFFER_ALREADY_CONFIRMED", "The offer was already confirmed by another member"));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_ALREADY_CONFIRMED"));
    }

    @Test
    void confirm_passesTheTotpCodeToTheService() throws Exception {
        UUID offerId = UUID.randomUUID();
        when(executionService.confirm(same(principal), eq(offerId), any()))
                .thenReturn(response(offerId, TransferExecutionStatus.AWAITING_FUNDS, "pix-code"));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", offerId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isOk());

        ArgumentCaptor<ConfirmOfferRequest> request = ArgumentCaptor.forClass(ConfirmOfferRequest.class);
        verify(executionService).confirm(same(principal), eq(offerId), request.capture());
        assertThat(request.getValue().totpCode()).isEqualTo("123456");
    }

    @Test
    void confirmRequote_withoutTotpCode_returns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAmount\": 1000.00, \"targetAmount\": 990.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("totpCode: TOTP code is required"));

        verifyNoInteractions(executionService);
    }

    @Test
    void confirmRequote_malformedTotpCode_returns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationWith("12345")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(executionService);
    }

    @Test
    void confirmRequote_incorrectTotp_returns401() throws Exception {
        when(executionService.confirmRequote(any(), any(), any()))
                .thenThrow(new BadCredentialsException("Invalid 2FA verification code"));

        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmRequote_userWithoutMembership_returns403AccessDeniedWithoutExecuting() throws Exception {
        UserEntity withoutMembership = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(withoutMembership))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(executionService);
    }

    private static String confirmationWith(String totpCode) {
        return """
                {"sourceAmount": 1000.00, "targetAmount": 990.00, "totpCode": "%s"}
                """.formatted(totpCode);
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

    @Test
    void confirm_operator_isAllowed() throws Exception {
        UserEntity operator = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        UUID offerId = UUID.randomUUID();
        when(executionService.confirm(same(operator), eq(offerId), any()))
                .thenReturn(response(offerId, TransferExecutionStatus.AWAITING_FUNDS, "pix-code"));

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", offerId)
                        .with(user(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_FUNDS"));
    }

    @Test
    void confirm_viewer_returns403PermissionDeniedWithoutExecuting() throws Exception {
        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(member(CompanyRole.VIEWER, MembershipStatus.ACTIVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(executionService);
    }

    @Test
    void confirm_removedMember_returns403AccessRevokedWithoutExecuting() throws Exception {
        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(member(CompanyRole.OWNER, MembershipStatus.REMOVED)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(executionService);
    }

    @Test
    void confirm_userWithoutMembership_returns403AccessDeniedWithoutExecuting() throws Exception {
        UserEntity withoutMembership = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(post("/v1/quotes/offers/{offerId}/confirm", UUID.randomUUID())
                        .with(user(withoutMembership))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(executionService);
    }

    @Test
    void find_viewer_isAllowed() throws Exception {
        UserEntity viewer = member(CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        UUID executionId = UUID.randomUUID();
        when(executionService.find(same(viewer), eq(executionId)))
                .thenReturn(response(UUID.randomUUID(), TransferExecutionStatus.COMPLETED, null));

        mockMvc.perform(get("/v1/transfer-executions/{id}", executionId).with(user(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void find_removedMember_returns403AccessRevokedWithoutReadingTheTransfer() throws Exception {
        mockMvc.perform(get("/v1/transfer-executions/{id}", UUID.randomUUID())
                        .with(user(member(CompanyRole.VIEWER, MembershipStatus.REMOVED))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(executionService);
    }

    @Test
    void find_userWithoutMembership_returns403AccessDeniedWithoutReadingTheTransfer() throws Exception {
        UserEntity withoutMembership = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(get("/v1/transfer-executions/{id}", UUID.randomUUID()).with(user(withoutMembership)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(executionService);
    }

    @Test
    void confirmRequote_operator_isAllowed() throws Exception {
        UserEntity operator = member(CompanyRole.OPERATOR, MembershipStatus.ACTIVE);
        UUID executionId = UUID.randomUUID();
        when(executionService.confirmRequote(same(operator), eq(executionId), any()))
                .thenReturn(response(UUID.randomUUID(), TransferExecutionStatus.PAYOUT_PROCESSING, null));

        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", executionId)
                        .with(user(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYOUT_PROCESSING"));
    }

    @Test
    void confirmRequote_viewer_returns403PermissionDeniedWithoutExecuting() throws Exception {
        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(member(CompanyRole.VIEWER, MembershipStatus.ACTIVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(executionService);
    }

    @Test
    void confirmRequote_removedMember_returns403AccessRevokedWithoutExecuting() throws Exception {
        mockMvc.perform(post("/v1/transfer-executions/{id}/requote/confirm", UUID.randomUUID())
                        .with(user(member(CompanyRole.OPERATOR, MembershipStatus.REMOVED)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(executionService);
    }

    private static TransferExecutionResponse response(
            UUID offerId, TransferExecutionStatus status, String fundingInstructions) {
        return new TransferExecutionResponse(UUID.randomUUID(), offerId, UUID.randomUUID(), "BLINDPAY", status,
                new MoneyAmount(new BigDecimal("1000.00"), "BRL"), new MoneyAmount(new BigDecimal("990.00"), "USD"),
                new BigDecimal("2.50"), fundingInstructions, null, null,
                OffsetDateTime.parse("2026-10-28T12:00:00Z"), OffsetDateTime.parse("2026-10-28T12:00:00Z"));
    }
}
