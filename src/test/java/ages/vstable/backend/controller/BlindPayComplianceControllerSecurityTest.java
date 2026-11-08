package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.blindpay.BlindPayComplianceStatusResponse;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentUploadResponse;
import ages.vstable.backend.dto.blindpay.BlindPayTermsResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.blindpay.BlindPayComplianceService;
import ages.vstable.backend.service.blindpay.BlindPayWebhookService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({BlindPayComplianceController.class, BlindPayWebhookController.class})
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class BlindPayComplianceControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean private BlindPayComplianceService complianceService;
    @MockitoBean private BlindPayWebhookService webhookService;
    @MockitoBean private UserService userService;
    @MockitoBean private JwtTokenUtils jwtTokenUtils;
    @MockitoBean private AuthenticationProvider authenticationProvider;

    private static final String VALID_KYB = """
            {"email":"finance@acme.test","legalName":"Acme Importadora LTDA","addressLine1":"Av. Ipiranga, 6681",
            "city":"Porto Alegre","stateProvinceRegion":"RS","country":"BR","postalCode":"90619900",
            "formationDate":"2020-04-23","businessType":"corporation","businessDescription":"Importação",
            "businessIndustry":"423690","estimatedAnnualRevenue":"1000000_9999999",
            "sourceOfWealth":"business_dividends_or_profits","accountPurpose":"business_expenses",
            "incorporationDocFile":"https://files.blindpay.test/doc.pdf",
            "proofOfOwnershipDocFile":"https://files.blindpay.test/doc.pdf",
            "owners":[{"role":"beneficial_controlling","firstName":"Ana","lastName":"Souza",
            "dateOfBirth":"1985-03-10","taxId":"12345678909","addressLine1":"Rua A, 10","city":"Porto Alegre",
            "stateProvinceRegion":"RS","country":"BR","postalCode":"90000000","idDocCountry":"BR",
            "idDocType":"PASSPORT","idDocFrontFile":"https://files.blindpay.test/doc.pdf"}]}
            """;

    private UserEntity principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        principal = memberOf(UUID.randomUUID(), CompanyRole.OWNER, MembershipStatus.ACTIVE);
    }

    private UserEntity memberOf(UUID companyId, CompanyRole role, MembershipStatus membershipStatus) {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .email("user@example.com")
                .passwordHash("hash")
                .passwordSalt("salt")
                .build();
        return TestCompanyUsers.withMembership(user, companyId, role, membershipStatus);
    }

    private UserEntity member(CompanyRole role) {
        return memberOf(UUID.randomUUID(), role, MembershipStatus.ACTIVE);
    }

    @Test
    void status_authenticatedUser_returnsStatusOfOwnCompany() throws Exception {
        // Arrange
        when(complianceService.getStatus(same(principal))).thenReturn(new BlindPayComplianceStatusResponse(
                BlindPayOnboardingStatus.APPROVED, true, true, true, false, true, "approved", "clear",
                List.of(), List.of(), null, null, false));

        // Act + Assert
        mockMvc.perform(get("/v1/blindpay/compliance").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.enabled").value(true));
        verify(complianceService).getStatus(same(principal));
    }

    @Test
    void endpoints_withoutAuthentication_return401() throws Exception {
        mockMvc.perform(get("/v1/blindpay/compliance")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/blindpay/compliance/terms")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/blindpay/compliance/kyb").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/blindpay/compliance/rfi")).andExpect(status().isUnauthorized());

        verifyNoInteractions(complianceService);
    }

    @Test
    void endpoints_administratorPrincipal_return403() throws Exception {
        var administrator = User.withUsername("admin@example.com").password("password").roles("ADMIN").build();

        mockMvc.perform(get("/v1/blindpay/compliance").with(user(administrator))).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/blindpay/compliance/terms").with(user(administrator)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(complianceService);
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"OPERATOR", "VIEWER"})
    void readEndpoints_nonOwnerMember_areAllowed(CompanyRole role) throws Exception {
        UserEntity member = member(role);

        mockMvc.perform(get("/v1/blindpay/compliance").with(user(member))).andExpect(status().isOk());
        mockMvc.perform(get("/v1/blindpay/compliance/rfi").with(user(member))).andExpect(status().isOk());

        verify(complianceService).getStatus(same(member));
        verify(complianceService).getRfi(same(member));
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"OPERATOR", "VIEWER"})
    void ownerOnlyEndpoints_nonOwnerMember_return403OwnerRequiredWithoutCallingService(CompanyRole role)
            throws Exception {
        UserEntity member = member(role);

        mockMvc.perform(post("/v1/blindpay/compliance/terms").with(user(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mockMvc.perform(multipart("/v1/blindpay/compliance/documents")
                        .file(new MockMultipartFile("file", "contrato.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                        .param("documentType", "INCORPORATION")
                        .with(user(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mockMvc.perform(post("/v1/blindpay/compliance/kyb")
                        .with(user(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_KYB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mockMvc.perform(put("/v1/blindpay/compliance/kyb")
                        .with(user(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_KYB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        mockMvc.perform(post("/v1/blindpay/compliance/rfi")
                        .with(user(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responses\":{\"note\":\"Revenue from exports\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));

        verifyNoInteractions(complianceService);
    }

    @Test
    void submitKyb_owner_forwardsRequestToTheService() throws Exception {
        when(complianceService.submitKyb(same(principal), any())).thenReturn(new BlindPayComplianceStatusResponse(
                BlindPayOnboardingStatus.VERIFYING, false, true, true, false, true, "verifying", null,
                List.of(), List.of(), null, null, false));

        mockMvc.perform(post("/v1/blindpay/compliance/kyb")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_KYB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"));
        verify(complianceService).submitKyb(same(principal), any());
    }

    @Test
    void endpoints_removedMember_return403AccessRevokedWithoutCallingService() throws Exception {
        UserEntity removed = memberOf(UUID.randomUUID(), CompanyRole.OWNER, MembershipStatus.REMOVED);

        mockMvc.perform(get("/v1/blindpay/compliance").with(user(removed)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(get("/v1/blindpay/compliance/rfi").with(user(removed)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));
        mockMvc.perform(post("/v1/blindpay/compliance/terms").with(user(removed)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(complianceService);
    }

    @Test
    void endpoints_userWithoutMembership_return403AccessDeniedWithoutCallingService() throws Exception {
        UserEntity withoutMembership = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(get("/v1/blindpay/compliance").with(user(withoutMembership)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));
        mockMvc.perform(post("/v1/blindpay/compliance/terms").with(user(withoutMembership)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(complianceService);
    }

    @Test
    void initiateTerms_authenticatedUser_returnsHostedUrl() throws Exception {
        when(complianceService.initiateTermsOfService(same(principal)))
                .thenReturn(new BlindPayTermsResponse("https://app.blindpay.com/e/terms?session_token=t"));

        mockMvc.perform(post("/v1/blindpay/compliance/terms").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://app.blindpay.com/e/terms?session_token=t"));
    }

    @Test
    void uploadDocument_multipart_forwardsBytesAndType() throws Exception {
        byte[] content = "%PDF-1.7".getBytes();
        when(complianceService.uploadDocument(same(principal), eq(BlindPayDocumentType.INCORPORATION), eq(content)))
                .thenReturn(new BlindPayDocumentUploadResponse("https://files.blindpay.test/doc.pdf"));

        mockMvc.perform(multipart("/v1/blindpay/compliance/documents")
                        .file(new MockMultipartFile("file", "contrato.pdf", "application/pdf", content))
                        .param("documentType", "INCORPORATION")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileUrl").value("https://files.blindpay.test/doc.pdf"));
    }

    @Test
    void uploadDocument_withoutFile_returns400() throws Exception {
        mockMvc.perform(multipart("/v1/blindpay/compliance/documents")
                        .param("documentType", "INCORPORATION")
                        .with(user(principal)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(complianceService);
    }

    @Test
    void submitKyb_missingRequiredFields_returns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/v1/blindpay/compliance/kyb")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"finance@acme.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(complianceService);
    }

    @Test
    void submitRfi_authenticatedUser_forwardsResponses() throws Exception {
        when(complianceService.submitRfi(same(principal), any())).thenReturn(new BlindPayComplianceStatusResponse(
                BlindPayOnboardingStatus.VERIFYING, false, true, true, false, true, "verifying", null,
                List.of(), List.of(), null, null, false));

        mockMvc.perform(post("/v1/blindpay/compliance/rfi")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responses\":{\"note\":\"Revenue from exports\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFYING"));
        verify(complianceService).submitRfi(same(principal), eq(Map.of("note", "Revenue from exports")));
    }

    @Test
    void webhook_isPublicAndForwardsRawBodyAndSvixHeaders() throws Exception {
        String body = "{\"webhook_event\":\"customer.update\"}";
        when(webhookService.handle(eq("msg_1"), eq("1760000000"), eq("v1,sig"), any()))
                .thenReturn(BlindPayWebhookService.Result.PROCESSED);

        mockMvc.perform(post("/v1/webhooks/blindpay")
                        .header("svix-id", "msg_1")
                        .header("svix-timestamp", "1760000000")
                        .header("svix-signature", "v1,sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
        verify(webhookService).handle(eq("msg_1"), eq("1760000000"), eq("v1,sig"), eq(body.getBytes()));
    }

    @Test
    void webhook_invalidSignature_returns401() throws Exception {
        when(webhookService.handle(any(), any(), any(), any()))
                .thenReturn(BlindPayWebhookService.Result.INVALID_SIGNATURE);

        mockMvc.perform(post("/v1/webhooks/blindpay")
                        .header("svix-id", "msg_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhook_otherMethodsRemainProtected() throws Exception {
        mockMvc.perform(get("/v1/webhooks/blindpay")).andExpect(status().isUnauthorized());
        verifyNoInteractions(webhookService);
    }
}
