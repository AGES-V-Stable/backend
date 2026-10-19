package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.dto.blindpay.BlindPayComplianceStatusResponse;
import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.entity.BlindPayTosSessionEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import ages.vstable.backend.external.blindpay.BlindPayWebhookVerifier;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayUploadResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayWalletResponse;
import ages.vstable.backend.repository.BeneficiaryProviderAccountRepository;
import ages.vstable.backend.repository.BlindPayTosSessionRepository;
import ages.vstable.backend.repository.BlindPayWebhookEventRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import ages.vstable.backend.repository.ProviderWalletRepository;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Onboarding completo com os serviços reais e a BlindPay simulada: aceite dos termos,
 * webhook assinado, upload, KYB, aprovação por webhook e liberação das operações.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlindPayOnboardingFlowTest {

    private static final String CUSTOMER_ID = "re_000000000001";
    private static final String FILE = "https://files.blindpay.test/onboarding/document.pdf";

    @Mock private BlindPayGateway gateway;
    @Mock private CompanyRepository companyRepository;
    @Mock private ProviderAccountRepository providerAccountRepository;
    @Mock private BlindPayTosSessionRepository tosSessionRepository;
    @Mock private BlindPayWebhookEventRepository webhookEventRepository;
    @Mock private BeneficiaryProviderAccountRepository beneficiaryProviderAccountRepository;
    @Mock private ProviderWalletRepository providerWalletRepository;

    private final AtomicReference<ProviderAccountEntity> storedAccount = new AtomicReference<>();
    private final Map<UUID, BlindPayTosSessionEntity> sessions = new HashMap<>();
    private final Set<String> processedMessages = new HashSet<>();

    private BlindPayComplianceService complianceService;
    private BlindPayWebhookService webhookService;
    private BlindPayProvisioningService provisioningService;
    private CompanyEntity company;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        BlindPayProperties properties = new BlindPayProperties();
        properties.setInstanceId(BlindPayWebhookServiceTest.INSTANCE_ID);
        properties.setWebhookSecret(BlindPayWebhookServiceTest.SECRET);
        JsonMapper mapper = JsonMapper.builder().build();
        complianceService = new BlindPayComplianceService(gateway, properties, companyRepository,
                providerAccountRepository, tosSessionRepository, mapper);
        webhookService = new BlindPayWebhookService(new BlindPayWebhookVerifier(properties), properties,
                webhookEventRepository, providerAccountRepository, tosSessionRepository, mapper);
        provisioningService = new BlindPayProvisioningService(gateway, providerAccountRepository,
                beneficiaryProviderAccountRepository, providerWalletRepository);

        company = CompanyEntity.builder().id(UUID.randomUUID()).legalName("Acme Importadora LTDA")
                .cnpj("11222333000181").country("BR").zipCode("90000000").state("RS").build();
        user = UserEntity.builder().id(UUID.randomUUID()).companyId(company.getId()).build();
        when(companyRepository.findById(company.getId())).thenReturn(Optional.of(company));
        inMemoryRepositories();
    }

    @Test
    void onboarding_fromTermsToApproval_enablesBlindPayOperationsOnlyAfterKybAndAmlClear() {
        // 1. Aceite dos termos (fluxo hospedado)
        when(gateway.initiateTermsOfService(any()))
                .thenReturn(new BlindPayInitiateTosResponse("https://app.blindpay.com/e/terms?session_token=t"));
        assertThat(complianceService.initiateTermsOfService(user).url()).startsWith("https://app.blindpay.com/");
        ArgumentCaptor<BlindPayInitiateTosRequest> tosRequest = ArgumentCaptor.forClass(BlindPayInitiateTosRequest.class);
        verify(gateway).initiateTermsOfService(tosRequest.capture());
        assertThat(status().status()).isEqualTo(BlindPayOnboardingStatus.TOS_PENDING);

        assertThat(deliver("msg_tos", BlindPayWebhookServiceTest.tosAccept(tosRequest.getValue().idempotencyKey(), null)))
                .isEqualTo(BlindPayWebhookService.Result.PROCESSED);
        assertThat(status().canSubmit()).isTrue();

        // 2. Documentos e cadastro KYB
        when(gateway.uploadFile(any(), anyString(), anyString(), any())).thenReturn(new BlindPayUploadResponse(FILE));
        assertThat(complianceService.uploadDocument(user, BlindPayDocumentType.INCORPORATION,
                "%PDF-1.7".getBytes()).fileUrl()).isEqualTo(FILE);

        when(gateway.createCustomer(any(), anyString()))
                .thenReturn(new BlindPayCustomerCreatedResponse(CUSTOMER_ID, CUSTOMER_ID));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("verifying", null));
        BlindPayComplianceStatusResponse submitted = complianceService.submitKyb(
                user, BlindPayComplianceServiceTest.kybRequest());
        assertThat(submitted.status()).isEqualTo(BlindPayOnboardingStatus.VERIFYING);
        assertThat(submitted.enabled()).isFalse();

        // Cliente criado e em verificação ainda não opera na BlindPay.
        assertThatThrownBy(() -> provisioningService.ensureWallet(company, "polygon"))
                .isInstanceOf(BlindPayComplianceException.class);

        // 3. KYB aprovado, AML ainda pendente: continua bloqueado.
        deliver("msg_kyb", BlindPayWebhookServiceTest.customerUpdate(
                "approved", null, company.getId().toString(), BlindPayWebhookServiceTest.INSTANCE_ID));
        assertThat(storedAccount.get().getStatus()).isEqualTo(BlindPayOnboardingStatus.AML_PENDING.name());
        assertThatThrownBy(() -> provisioningService.ensureWallet(company, "polygon"))
                .isInstanceOf(BlindPayComplianceException.class);

        // 4. AML liberado: habilitado.
        deliver("msg_aml", BlindPayWebhookServiceTest.customerUpdate(
                "approved", "clear", company.getId().toString(), BlindPayWebhookServiceTest.INSTANCE_ID));
        when(gateway.getCustomer(CUSTOMER_ID)).thenReturn(customer("approved", "clear"));
        assertThat(status().enabled()).isTrue();

        when(providerWalletRepository.findByProviderAccountIdAndNetwork(any(), eq("polygon")))
                .thenReturn(Optional.empty());
        when(providerWalletRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.createWallet(eq(CUSTOMER_ID), any(), anyString())).thenReturn(new BlindPayWalletResponse(
                "bl_000000000001", "V-Stable polygon", company.getId() + ":polygon", "0xabc",
                BlindPayApi.BlockchainWallet.Network.polygon, OffsetDateTime.now()));
        assertThat(provisioningService.ensureWallet(company, "polygon")).isEqualTo("bl_000000000001");

        // 5. Retentativa do mesmo webhook não é reprocessada.
        assertThat(deliver("msg_aml", BlindPayWebhookServiceTest.customerUpdate(
                "rejected", "clear", company.getId().toString(), BlindPayWebhookServiceTest.INSTANCE_ID)))
                .isEqualTo(BlindPayWebhookService.Result.DUPLICATE);
        assertThat(storedAccount.get().getStatus()).isEqualTo(BlindPayOnboardingStatus.APPROVED.name());
    }

    private BlindPayComplianceStatusResponse status() {
        return complianceService.getStatus(user);
    }

    private BlindPayWebhookService.Result deliver(String messageId, String body) {
        long now = Instant.now().getEpochSecond();
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return webhookService.handle(messageId, Long.toString(now),
                BlindPayWebhookServiceTest.sign(messageId, now, raw), raw);
    }

    private BlindPayCustomerResponse customer(String kycStatus, String amlStatus) {
        return new BlindPayCustomerResponse(CUSTOMER_ID, "business", "standard", kycStatus, amlStatus,
                "finance@acme.test", "BR", null, null, "Acme Importadora LTDA", company.getId().toString(),
                BlindPayWebhookServiceTest.INSTANCE_ID, "to_000000000001", true, List.of(), List.of(),
                null, null, null);
    }

    private void inMemoryRepositories() {
        when(providerAccountRepository.save(any())).thenAnswer(invocation -> {
            ProviderAccountEntity account = invocation.getArgument(0);
            if (account.getId() == null) {
                account.setId(UUID.randomUUID());
            }
            storedAccount.set(account);
            return account;
        });
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenAnswer(invocation -> Optional.ofNullable(storedAccount.get()));
        when(providerAccountRepository.findForUpdate(company.getId(), IntegrationProvider.BLINDPAY))
                .thenAnswer(invocation -> Optional.ofNullable(storedAccount.get()));
        when(providerAccountRepository.findByProviderAndExternalCustomerId(eq(IntegrationProvider.BLINDPAY), any()))
                .thenAnswer(invocation -> Optional.ofNullable(storedAccount.get())
                        .filter(account -> invocation.getArgument(1).equals(account.getExternalCustomerId())));
        when(tosSessionRepository.save(any())).thenAnswer(invocation -> {
            BlindPayTosSessionEntity session = invocation.getArgument(0);
            sessions.put(session.getIdempotencyKey(), session);
            return session;
        });
        when(tosSessionRepository.findByIdempotencyKey(any()))
                .thenAnswer(invocation -> Optional.ofNullable(sessions.get(invocation.<UUID>getArgument(0))));
        when(webhookEventRepository.insertIfAbsent(anyString(), any(), any()))
                .thenAnswer(invocation -> processedMessages.add(invocation.getArgument(0)) ? 1 : 0);
    }
}
