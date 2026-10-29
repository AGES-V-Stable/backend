package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.dto.blindpay.BlindPayComplianceStatusResponse;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.dto.blindpay.BlindPayKybRequest;
import ages.vstable.backend.dto.blindpay.BlindPayRfiDetailsResponse;
import ages.vstable.backend.entity.BlindPayTosSessionEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiSubmitResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayUpdateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayUploadResponse;
import ages.vstable.backend.repository.BlindPayTosSessionRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlindPayComplianceServiceTest {

    static final String INSTANCE_ID = "in_000000000001";
    static final String CUSTOMER_ID = "re_000000000001";
    static final String TOS_ID = "to_000000000001";
    static final String FILE = "https://files.blindpay.test/onboarding/doc.pdf";
    static final byte[] PDF = "%PDF-1.7 content".getBytes();
    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    @Mock private BlindPayGateway gateway;
    @Mock private CompanyRepository companyRepository;
    @Mock private ProviderAccountRepository providerAccountRepository;
    @Mock private BlindPayTosSessionRepository tosSessionRepository;

    private BlindPayComplianceService service;
    private CompanyEntity company;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        BlindPayProperties properties = new BlindPayProperties();
        properties.setInstanceId(INSTANCE_ID);
        properties.setTosRedirectUrl("https://app.vstable.test/blindpay");
        service = new BlindPayComplianceService(gateway, properties, companyRepository,
                providerAccountRepository, tosSessionRepository, JsonMapper.builder().build());

        company = CompanyEntity.builder().id(UUID.randomUUID()).legalName("Acme Importadora LTDA")
                .cnpj("11.222.333/0001-81").country("BR").zipCode("90000000").state("RS")
                .kybStatus(ComplianceStatus.PENDING).amlStatus(ComplianceStatus.PENDING).build();
        user = UserEntity.builder().id(UUID.randomUUID()).companyId(company.getId()).email("u@acme.test").build();
        when(companyRepository.findById(company.getId())).thenReturn(Optional.of(company));
        when(providerAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tosSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------------
    // Terms of service
    // ---------------------------------------------------------------------

    @Test
    void initiateTerms_newCompany_createsAccountPersistsSessionBeforeCallingBlindPayAndReturnsUrl() {
        // Arrange
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(gateway.initiateTermsOfService(any()))
                .thenReturn(new BlindPayInitiateTosResponse("https://app.blindpay.com/e/terms?session_token=x"));

        // Act
        String url = service.initiateTermsOfService(user).url();

        // Assert
        assertThat(url).isEqualTo("https://app.blindpay.com/e/terms?session_token=x");
        ArgumentCaptor<ProviderAccountEntity> account = ArgumentCaptor.forClass(ProviderAccountEntity.class);
        verify(providerAccountRepository).save(account.capture());
        assertThat(account.getValue().getStatus()).isEqualTo("TOS_PENDING");

        ArgumentCaptor<BlindPayTosSessionEntity> session = ArgumentCaptor.forClass(BlindPayTosSessionEntity.class);
        verify(tosSessionRepository).save(session.capture());
        ArgumentCaptor<BlindPayInitiateTosRequest> request = ArgumentCaptor.forClass(BlindPayInitiateTosRequest.class);
        verify(gateway).initiateTermsOfService(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(session.getValue().getIdempotencyKey());
        assertThat(request.getValue().customerId()).isNull();
        assertThat(request.getValue().redirectUrl()).isEqualTo("https://app.vstable.test/blindpay");
        assertThat(session.getValue().getStatus()).isEqualTo("INITIATED");
        assertThat(session.getValue().getRequestedBy()).isEqualTo(user.getId());
    }

    @Test
    void initiateTerms_alreadyAccepted_isRejectedWithoutCallingBlindPay() {
        // Arrange
        givenAccount(UnaryOperator.identity());

        // Act + Assert
        assertThatThrownBy(() -> service.initiateTermsOfService(user)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void initiateTerms_responseWithoutHttpsUrl_isInvalidProviderResponse() {
        // Arrange
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(gateway.initiateTermsOfService(any())).thenReturn(new BlindPayInitiateTosResponse("http://insecure"));

        // Act + Assert
        assertThatThrownBy(() -> service.initiateTermsOfService(user))
                .isInstanceOf(BlindPayIntegrationException.class);
    }

    // ---------------------------------------------------------------------
    // Documents
    // ---------------------------------------------------------------------

    @Test
    void uploadDocument_pdfAfterTerms_uploadsWithDetectedTypeAndGenericName() {
        // Arrange
        givenAccount(UnaryOperator.identity());
        when(gateway.uploadFile(any(), anyString(), anyString(), any())).thenReturn(new BlindPayUploadResponse(FILE));

        // Act
        String fileUrl = service.uploadDocument(user, BlindPayDocumentType.INCORPORATION, PDF).fileUrl();

        // Assert
        assertThat(fileUrl).isEqualTo(FILE);
        verify(gateway).uploadFile(BlindPayApi.Upload.Bucket.onboarding, "document.pdf", "application/pdf", PDF);
    }

    @Test
    void uploadDocument_identityDocumentAsPdf_isRejected() {
        givenAccount(UnaryOperator.identity());

        assertThatThrownBy(() -> service.uploadDocument(user, BlindPayDocumentType.ID_DOCUMENT_FRONT, PDF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file must be a JPEG or PNG image");
        verifyNoInteractions(gateway);
    }

    @Test
    void uploadDocument_identityDocumentAsPng_isAccepted() {
        givenAccount(UnaryOperator.identity());
        when(gateway.uploadFile(any(), anyString(), anyString(), any())).thenReturn(new BlindPayUploadResponse(FILE));

        service.uploadDocument(user, BlindPayDocumentType.ID_DOCUMENT_FRONT, PNG);

        verify(gateway).uploadFile(BlindPayApi.Upload.Bucket.onboarding, "document.png", "image/png", PNG);
    }

    @Test
    void uploadDocument_contentThatIsNotAnAcceptedFormat_isRejected() {
        givenAccount(UnaryOperator.identity());

        assertThatThrownBy(() -> service.uploadDocument(user, BlindPayDocumentType.INCORPORATION,
                "<html>pdf</html>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file must be a PDF, JPEG or PNG");
        verifyNoInteractions(gateway);
    }

    @Test
    void uploadDocument_largerThanThreeMegabytes_isRejected() {
        byte[] large = new byte[BlindPayComplianceService.MAX_DOCUMENT_BYTES + 1];
        System.arraycopy(PDF, 0, large, 0, PDF.length);

        assertThatThrownBy(() -> service.uploadDocument(user, BlindPayDocumentType.INCORPORATION, large))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file must have at most 3MB");
        verifyNoInteractions(gateway);
    }

    @Test
    void uploadDocument_beforeTermsAccepted_isBlocked() {
        givenAccount(account -> { account.setTosAcceptedAt(null); return account; });

        assertThatThrownBy(() -> service.uploadDocument(user, BlindPayDocumentType.INCORPORATION, PDF))
                .isInstanceOf(UnprocessableEntityException.class);
        verifyNoInteractions(gateway);
    }

    // ---------------------------------------------------------------------
    // KYB submission
    // ---------------------------------------------------------------------

    @Test
    void submitKyb_sendsBusinessStandardCustomerWithServerSideIdentifiersAndSynchronizes() {
        // Arrange
        ProviderAccountEntity account = givenLockedAccount(UnaryOperator.identity());
        when(gateway.createCustomer(any(), anyString()))
                .thenReturn(new BlindPayCustomerCreatedResponse(CUSTOMER_ID, CUSTOMER_ID));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("verifying", null));

        // Act
        BlindPayComplianceStatusResponse response = service.submitKyb(user, kybRequest());

        // Assert
        ArgumentCaptor<BlindPayCreateCustomerRequest> request =
                ArgumentCaptor.forClass(BlindPayCreateCustomerRequest.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(gateway).createCustomer(request.capture(), key.capture());
        assertThat(request.getValue().type()).isEqualTo(BlindPayApi.Customer.Type.business);
        assertThat(request.getValue().kycType()).isEqualTo(BlindPayApi.Customer.KycType.standard);
        assertThat(request.getValue().taxId()).isEqualTo("11222333000181");
        assertThat(request.getValue().externalId()).isEqualTo(company.getId().toString());
        assertThat(request.getValue().tosId()).isEqualTo(TOS_ID);
        assertThat(request.getValue().owners()).singleElement().satisfies(owner -> {
            assertThat(owner.role()).isEqualTo(BlindPayApi.Customer.OwnerRole.beneficial_controlling);
            assertThat(owner.id()).isNull();
        });
        assertThat(key.getValue()).startsWith("company:" + company.getId() + ":blindpay:kyb:").hasSizeLessThan(256);

        assertThat(account.getExternalCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.VERIFYING);
        assertThat(response.submitted()).isTrue();
        assertThat(response.enabled()).isFalse();
        // O compliance global da empresa (Avenia) não é alterado.
        assertThat(company.getKybStatus()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(company.getAmlStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void submitKyb_sameDataTwice_usesSameIdempotencyKey() {
        // Arrange
        givenLockedAccount(UnaryOperator.identity());
        when(gateway.createCustomer(any(), anyString()))
                .thenThrow(BlindPayIntegrationException.communication("timeout", null));

        // Act
        List<String> keys = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.submitKyb(user, kybRequest()))
                    .isInstanceOf(BlindPayIntegrationException.class);
        }

        // Assert
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.times(2)).createCustomer(any(), key.capture());
        keys.addAll(key.getAllValues());
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    @Test
    void submitKyb_providerFailure_persistsFailedSubmissionAndRethrows() {
        // Arrange
        ProviderAccountEntity account = givenLockedAccount(UnaryOperator.identity());
        when(gateway.createCustomer(any(), anyString())).thenThrow(BlindPayIntegrationException.response(
                "missing fields", 400, "VALIDATION_MISSING_REQUIRED_FIELDS", "trace", null));

        // Act + Assert
        assertThatThrownBy(() -> service.submitKyb(user, kybRequest()))
                .isInstanceOf(BlindPayIntegrationException.class);
        assertThat(account.getStatus()).isEqualTo("SUBMISSION_FAILED");
        assertThat(account.getLastErrorCode()).isEqualTo("VALIDATION_MISSING_REQUIRED_FIELDS");
        assertThat(account.getExternalCustomerId()).isNull();
    }

    @Test
    void submitKyb_synchronizationFailureAfterCreation_keepsCreatedCustomer() {
        // Arrange
        ProviderAccountEntity account = givenLockedAccount(UnaryOperator.identity());
        when(gateway.createCustomer(any(), anyString()))
                .thenReturn(new BlindPayCustomerCreatedResponse(CUSTOMER_ID, null));
        when(gateway.getCustomer(CUSTOMER_ID)).thenThrow(BlindPayIntegrationException.communication("down", null));

        // Act
        BlindPayComplianceStatusResponse response = service.submitKyb(user, kybRequest());

        // Assert
        assertThat(account.getExternalCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.VERIFYING);
    }

    @Test
    void submitKyb_withoutAcceptedTerms_isBlocked() {
        givenLockedAccount(account -> { account.setTosId(null); return account; });

        assertThatThrownBy(() -> service.submitKyb(user, kybRequest()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("terms of service");
        verifyNoInteractions(gateway);
    }

    @Test
    void submitKyb_alreadySubmitted_isConflict() {
        givenLockedAccount(account -> { account.setExternalCustomerId(CUSTOMER_ID); return account; });

        assertThatThrownBy(() -> service.submitKyb(user, kybRequest())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void submitKyb_withoutControllingPerson_isRejected() {
        givenLockedAccount(UnaryOperator.identity());
        BlindPayKybRequest request = kybRequest(owner(BlindPayApi.Customer.OwnerRole.beneficial_owner, 60, null));

        assertThatThrownBy(() -> service.submitKyb(user, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owners: at least one controlling person must be provided");
        verifyNoInteractions(gateway);
    }

    @Test
    void submitKyb_beneficialOwnerBelowTwentyFivePercent_isRejected() {
        givenLockedAccount(UnaryOperator.identity());
        BlindPayKybRequest request = kybRequest(
                owner(BlindPayApi.Customer.OwnerRole.controlling_person, null, null),
                owner(BlindPayApi.Customer.OwnerRole.beneficial_owner, 10, null));

        assertThatThrownBy(() -> service.submitKyb(user, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owners[1].ownershipPercentage: beneficial owners must hold at least 25%");
    }

    @Test
    void submitKyb_ownershipAboveOneHundredPercent_isRejected() {
        givenLockedAccount(UnaryOperator.identity());
        BlindPayKybRequest request = kybRequest(
                owner(BlindPayApi.Customer.OwnerRole.beneficial_controlling, 60, null),
                owner(BlindPayApi.Customer.OwnerRole.beneficial_owner, 50, null));

        assertThatThrownBy(() -> service.submitKyb(user, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owners: ownership percentages must not exceed 100%");
    }

    @Test
    void submitKyb_accountPurposeOtherWithoutDescription_isRejected() {
        givenLockedAccount(UnaryOperator.identity());
        BlindPayKybRequest base = kybRequest();
        BlindPayKybRequest request = new BlindPayKybRequest(base.email(), base.legalName(), null,
                base.addressLine1(), null, base.city(), base.stateProvinceRegion(), base.country(),
                base.postalCode(), null, base.formationDate(), null, base.businessType(),
                base.businessDescription(), base.businessIndustry(), base.estimatedAnnualRevenue(),
                base.sourceOfWealth(), "other", null, null, null, null, base.incorporationDocFile(),
                base.proofOfOwnershipDocFile(), null, null, null, null, base.owners());

        assertThatThrownBy(() -> service.submitKyb(user, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("accountPurposeOther");
    }

    @Test
    void submitKyb_ownerWithIdOnFirstSubmission_isRejected() {
        givenLockedAccount(UnaryOperator.identity());
        BlindPayKybRequest request = kybRequest(
                owner(BlindPayApi.Customer.OwnerRole.beneficial_controlling, 100, "ub_000000000001"));

        assertThatThrownBy(() -> service.submitKyb(user, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("owners[0].id");
    }

    // ---------------------------------------------------------------------
    // KYB update
    // ---------------------------------------------------------------------

    @Test
    void updateKyb_submittedCustomer_sendsPutWithOwnerIds() {
        // Arrange
        givenLockedAccount(account -> submitted(account, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("verifying", null));
        BlindPayKybRequest request = kybRequest(
                owner(BlindPayApi.Customer.OwnerRole.beneficial_controlling, 100, "ub_000000000001"));

        // Act
        BlindPayComplianceStatusResponse response = service.updateKyb(user, request);

        // Assert
        ArgumentCaptor<BlindPayUpdateCustomerRequest> update =
                ArgumentCaptor.forClass(BlindPayUpdateCustomerRequest.class);
        verify(gateway).updateCustomer(eq(CUSTOMER_ID), update.capture(), anyString());
        assertThat(update.getValue().taxId()).isEqualTo("11222333000181");
        assertThat(update.getValue().owners()).singleElement()
                .satisfies(owner -> assertThat(owner.id()).isEqualTo("ub_000000000001"));
        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.VERIFYING);
    }

    @Test
    void updateKyb_rejectedCustomer_isBlockedBecauseUpdateDoesNotRestartVerification() {
        givenLockedAccount(account -> submitted(account, BlindPayOnboardingStatus.REJECTED));
        BlindPayKybRequest request = kybRequest(
                owner(BlindPayApi.Customer.OwnerRole.beneficial_controlling, 100, "ub_000000000001"));

        assertThatThrownBy(() -> service.updateKyb(user, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not restart the verification");
        verifyNoInteractions(gateway);
    }

    @Test
    void updateKyb_ownerWithoutId_isRejected() {
        givenLockedAccount(account -> submitted(account, BlindPayOnboardingStatus.VERIFYING));

        assertThatThrownBy(() -> service.updateKyb(user, kybRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owners[0].id: must be provided to update an owner");
        verifyNoInteractions(gateway);
    }

    @Test
    void updateKyb_beforeSubmission_isBlocked() {
        givenLockedAccount(UnaryOperator.identity());

        assertThatThrownBy(() -> service.updateKyb(user, kybRequest()))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    // ---------------------------------------------------------------------
    // Status synchronization
    // ---------------------------------------------------------------------

    @Test
    void getStatus_approvedWithClearAml_isEnabled() {
        // Arrange
        ProviderAccountEntity account = givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("approved", "clear"));

        // Act
        BlindPayComplianceStatusResponse response = service.getStatus(user);

        // Assert
        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.APPROVED);
        assertThat(response.enabled()).isTrue();
        assertThat(response.kycWarningCodes()).containsExactly("ADDRESS_MISMATCH");
        assertThat(response.owners()).extracting(BlindPayComplianceStatusResponse.OwnerReference::id)
                .containsExactly("ub_000000000001");
        assertThat(account.getStatus()).isEqualTo("APPROVED");
        assertThat(account.getKycStatus()).isEqualTo("approved");
        assertThat(account.getAmlStatus()).isEqualTo("clear");
        assertThat(company.getKybStatus()).isEqualTo(ComplianceStatus.PENDING);
    }

    @Test
    void getStatus_approvedKybWithoutAmlResult_isNotEnabled() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("approved", null));

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.AML_PENDING);
        assertThat(response.enabled()).isFalse();
    }

    @Test
    void getStatus_amlHit_isNotEnabled() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("approved", "hit"));

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.AML_HIT);
        assertThat(response.enabled()).isFalse();
    }

    @Test
    void getStatus_pendingReview_isNotEnabled() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("pending_review", "clear"));

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.PENDING_REVIEW);
        assertThat(response.enabled()).isFalse();
        assertThat(response.canUpdate()).isTrue();
    }

    @Test
    void getStatus_rejected_cannotBeUpdatedOrResubmitted() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("rejected", "clear"));

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.REJECTED);
        assertThat(response.enabled()).isFalse();
        assertThat(response.canUpdate()).isFalse();
        assertThat(response.canSubmit()).isFalse();
    }

    @Test
    void getStatus_blindPayUnavailable_returnsPersistedStatusFlaggedAsStale() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.PENDING_REVIEW));
        when(gateway.getCustomer(CUSTOMER_ID)).thenThrow(BlindPayIntegrationException.communication("down", null));

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.PENDING_REVIEW);
        assertThat(response.synchronizationFailed()).isTrue();
    }

    @Test
    void getStatus_customerOfAnotherCompany_isNotApplied() {
        ProviderAccountEntity account = givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        BlindPayCustomerResponse other = new BlindPayCustomerResponse(CUSTOMER_ID, "business", "standard",
                "approved", "clear", null, "BR", null, null, null, UUID.randomUUID().toString(), INSTANCE_ID,
                TOS_ID, true, null, null, null, null, null);
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(other);

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.enabled()).isFalse();
        assertThat(response.synchronizationFailed()).isTrue();
        assertThat(account.getStatus()).isEqualTo("VERIFYING");
    }

    @Test
    void getStatus_withoutAccount_isNotStarted() {
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());

        BlindPayComplianceStatusResponse response = service.getStatus(user);

        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.NOT_STARTED);
        assertThat(response.enabled()).isFalse();
        verifyNoInteractions(gateway);
    }

    // ---------------------------------------------------------------------
    // RFI
    // ---------------------------------------------------------------------

    @Test
    void getRfi_pendingRequest_returnsRequestedFields() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(rfi());

        BlindPayRfiDetailsResponse response = service.getRfi(user);

        assertThat(response.open()).isTrue();
        assertThat(response.sections()).singleElement().satisfies(section ->
                assertThat(section.fields()).extracting(BlindPayRfiDetailsResponse.Field::key)
                        .containsExactly("sof_doc", "sof_kind"));
    }

    @Test
    void getRfi_noPendingRequest_returnsClosed() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(null);

        assertThat(service.getRfi(user).open()).isFalse();
    }

    @Test
    void submitRfi_validResponses_areSentAndStatusIsSynchronized() {
        // Arrange
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(rfi());
        when(gateway.submitCustomerRfi(eq(CUSTOMER_ID), any(), anyString()))
                .thenReturn(new BlindPayRfiSubmitResponse(true, null));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("verifying", null));

        // Act
        BlindPayComplianceStatusResponse response = service.submitRfi(user,
                Map.of("sof_doc", List.of(FILE), "sof_kind", "exports"));

        // Assert
        verify(gateway).submitCustomerRfi(eq(CUSTOMER_ID),
                eq(Map.of("sof_doc", List.of(FILE), "sof_kind", "exports")), startsWithRfiKey());
        assertThat(response.status()).isEqualTo(BlindPayOnboardingStatus.VERIFYING);
    }

    @Test
    void submitRfi_missingRequiredField_isRejected() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(rfi());

        assertThatThrownBy(() -> service.submitRfi(user, Map.of("sof_kind", "exports")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("responses.sof_doc: must be provided");
        verify(gateway, never()).submitCustomerRfi(any(), any(), any());
    }

    @Test
    void submitRfi_fieldNotRequested_isRejected() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(rfi());

        assertThatThrownBy(() -> service.submitRfi(user, Map.of("sof_doc", List.of(FILE), "extra", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("responses.extra: is not requested by BlindPay");
    }

    @Test
    void submitRfi_invalidOptionOrNonHttpsFile_isRejected() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(rfi());

        assertThatThrownBy(() -> service.submitRfi(user, Map.of("sof_doc", List.of(FILE), "sof_kind", "lottery")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("responses.sof_kind: is not an accepted option");
        assertThatThrownBy(() -> service.submitRfi(user, Map.of("sof_doc", List.of("http://files/a.pdf"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("responses.sof_doc: files must be https URLs");
    }

    @Test
    void submitRfi_withoutPendingRequest_isConflict() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.VERIFYING));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.submitRfi(user, Map.of("sof_doc", List.of(FILE))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void submitRfi_blindPayReportsAlreadyResolved_isConflictWithReason() {
        givenAccount(a -> submitted(a, BlindPayOnboardingStatus.RFI_REQUESTED));
        when(gateway.getCustomerRfi(CUSTOMER_ID)).thenReturn(rfi());
        when(gateway.submitCustomerRfi(eq(CUSTOMER_ID), any(), anyString()))
                .thenReturn(new BlindPayRfiSubmitResponse(false, "already_resolved"));

        assertThatThrownBy(() -> service.submitRfi(user, Map.of("sof_doc", List.of(FILE))))
                .isInstanceOf(ConflictException.class)
                .hasMessageEndingWith("already_resolved");
    }

    @Test
    void getRfi_beforeSubmission_isBlocked() {
        givenAccount(UnaryOperator.identity());

        assertThatThrownBy(() -> service.getRfi(user)).isInstanceOf(UnprocessableEntityException.class);
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private static String startsWithRfiKey() {
        return org.mockito.ArgumentMatchers.startsWith("rfi:rf_000000000001:");
    }

    /** Conta com termos aceitos e sem cliente, ajustável por cenário. */
    private ProviderAccountEntity baseAccount(UnaryOperator<ProviderAccountEntity> customizer) {
        return customizer.apply(ProviderAccountEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .provider(IntegrationProvider.BLINDPAY)
                .status(BlindPayOnboardingStatus.TOS_ACCEPTED.name())
                .tosId(TOS_ID)
                .tosAcceptedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());
    }

    private ProviderAccountEntity givenAccount(UnaryOperator<ProviderAccountEntity> customizer) {
        ProviderAccountEntity account = baseAccount(customizer);
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        return account;
    }

    private ProviderAccountEntity givenLockedAccount(UnaryOperator<ProviderAccountEntity> customizer) {
        ProviderAccountEntity account = givenAccount(customizer);
        when(providerAccountRepository.findForUpdate(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        return account;
    }

    private ProviderAccountEntity submitted(ProviderAccountEntity account, BlindPayOnboardingStatus status) {
        account.setExternalCustomerId(CUSTOMER_ID);
        account.setStatus(status.name());
        return account;
    }

    private BlindPayCustomerResponse customer(String kycStatus, String amlStatus) {
        return new BlindPayCustomerResponse(CUSTOMER_ID, "business", "standard", kycStatus, amlStatus,
                "finance@acme.test", "BR", null, null, "Acme Importadora LTDA", company.getId().toString(),
                INSTANCE_ID, TOS_ID, true,
                List.of(new BlindPayCustomerResponse.KycWarning("ADDRESS_MISMATCH", "UNRESOLVED")),
                List.of(new BlindPayCustomerResponse.Owner("ub_000000000001", "beneficial_controlling", "Ana", "Souza")),
                null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private BlindPayRfiResponse rfi() {
        return new BlindPayRfiResponse("rf_000000000001", CUSTOMER_ID, INSTANCE_ID, "pending",
                List.of(new BlindPayRfiResponse.Section("Source of funds", "Explain", null, List.of(
                        new BlindPayRfiResponse.Field("sof_doc", "Document", null, true, null, true,
                                "SOURCE_OF_FUNDS_DOCUMENT"),
                        new BlindPayRfiResponse.Field("sof_kind", "Kind", null, false,
                                List.of(new BlindPayRfiResponse.Item("Exports", "exports")), false, null)))),
                OffsetDateTime.now().plusDays(7), null, OffsetDateTime.now());
    }

    static BlindPayKybRequest kybRequest() {
        return kybRequest(owner(BlindPayApi.Customer.OwnerRole.beneficial_controlling, 100, null));
    }

    static BlindPayKybRequest kybRequest(BlindPayKybRequest.Owner... owners) {
        return new BlindPayKybRequest(
                "finance@acme.test", "Acme Importadora LTDA", null, "Av. Ipiranga, 6681", null,
                "Porto Alegre", "RS", "BR", "90619900", "+5551999999999", LocalDate.of(2020, 4, 23),
                "https://acme.test", BlindPayApi.Customer.BusinessType.corporation, "Importação de eletrônicos",
                "423690", "1000000_9999999", "business_dividends_or_profits", "business_expenses", null,
                "business_transactions", null, false, FILE, FILE, null, null, null, null, List.of(owners));
    }

    static BlindPayKybRequest.Owner owner(BlindPayApi.Customer.OwnerRole role, Integer ownership, String id) {
        return new BlindPayKybRequest.Owner(id, role, "Ana", "Souza", LocalDate.of(1985, 3, 10), "12345678909",
                null, "Rua A, 10", null, "Porto Alegre", "RS", "BR", "90000000", "BR",
                BlindPayApi.Customer.IdDocType.PASSPORT, FILE, null, null, null, ownership, "CEO");
    }
}
