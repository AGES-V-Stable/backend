package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.*;
import ages.vstable.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindPayProvisioningServiceTest {

    @Mock private BlindPayGateway gateway;
    @Mock private ProviderAccountRepository providerAccountRepository;
    @Mock private BeneficiaryProviderAccountRepository beneficiaryProviderAccountRepository;
    @Mock private ProviderWalletRepository providerWalletRepository;

    private BlindPayProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new BlindPayProvisioningService(
                gateway, providerAccountRepository, beneficiaryProviderAccountRepository, providerWalletRepository);
    }

    @Test
    void ensureBankAccount_approvedCustomer_createsSwiftAccountWithoutCreatingCustomer() {
        // Arrange
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).legalName("Empresa Ltda")
                .cnpj("11222333000181").country("Brasil").zipCode("90000000").city("Porto Alegre")
                .state("RS").build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").accountHolderName("Fornecedor LLC")
                .identificationDocument("123").receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .paymentRail("international_swift").swiftBic("BOFAUS3N")
                .accountNumber("123456").country("Brasil").addressLine1("Rua A, 1").build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, "re_000000000001", BlindPayOnboardingStatus.APPROVED)));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("re_000000000001"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse(
                        "ba_1", "international_swift", "Fornecedor", "approved",
                        "Fornecedor LLC", "BR", "vendor_or_supplier", OffsetDateTime.now()));

        // Act
        String bankAccountId = service.ensureBankAccount(company, beneficiary);

        // Assert
        assertThat(bankAccountId).isEqualTo("ba_1");
        verify(gateway, never()).createCustomer(any(), any());
        ArgumentCaptor<BlindPayCreateBankAccountRequest> bank =
                ArgumentCaptor.forClass(BlindPayCreateBankAccountRequest.class);
        verify(gateway).createBankAccount(eq("re_000000000001"), bank.capture(),
                eq("beneficiary:" + beneficiary.getId() + ":blindpay"));
        assertThat(bank.getValue().swiftCodeBic()).isEqualTo("BOFAUS3N");
        assertThat(bank.getValue().swiftBeneficiaryCountry()).isEqualTo("BR");
    }

    @Test
    void ensureWallet_approvedCustomer_createsOneManagedWalletPerCompanyAndNetwork() {
        // Arrange
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).build();
        ProviderAccountEntity account = account(company, "customer-id", BlindPayOnboardingStatus.APPROVED);
        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(account.getId(), "polygon"))
                .thenReturn(Optional.empty());
        when(gateway.createWallet(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayWalletResponse(
                        "wallet-id", "V-Stable polygon", companyId + ":polygon",
                        "0x123", BlindPayApi.BlockchainWallet.Network.polygon, OffsetDateTime.now()));
        when(providerWalletRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        String walletId = service.ensureWallet(company, "polygon");

        // Assert
        assertThat(walletId).isEqualTo("wallet-id");
        ArgumentCaptor<BlindPayCreateWalletRequest> request =
                ArgumentCaptor.forClass(BlindPayCreateWalletRequest.class);
        verify(gateway).createWallet(eq("customer-id"), request.capture(),
                eq("company:" + companyId + ":blindpay:wallet:polygon"));
        assertThat(request.getValue().network()).isEqualTo(BlindPayApi.BlockchainWallet.Network.polygon);
        assertThat(request.getValue().externalId()).isEqualTo(companyId + ":polygon");
    }

    @Test
    void ensureBankAccount_withoutBlindPayAccount_isBlockedWithoutCallingBlindPay() {
        // Arrange
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(BlindPayComplianceException.class)
                .hasMessage("BlindPay compliance is not approved for this company");
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "VERIFYING", "PENDING_REVIEW", "RFI_REQUESTED", "AML_PENDING",
            "AML_HIT", "TOS_PENDING", "REJECTED"})
    void ensureWallet_customerNotApproved_isBlockedWithoutCallingBlindPay(String status) {
        // Arrange
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        ProviderAccountEntity account = account(company, "re_000000000001", null);
        account.setStatus(status);
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));

        // Act + Assert
        assertThatThrownBy(() -> service.ensureWallet(company, "polygon"))
                .isInstanceOf(BlindPayComplianceException.class);
        verifyNoInteractions(gateway, providerWalletRepository);
    }

    @Test
    void ensureBankAccount_approvedStatusWithoutCustomerId_isBlocked() {
        // Arrange
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, null, BlindPayOnboardingStatus.APPROVED)));

        // Act + Assert
        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(BlindPayComplianceException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void ensureBankAccount_responseWithoutId_isRejected() {
        // Arrange
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").build();
        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, "customer-id", BlindPayOnboardingStatus.APPROVED)));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse(
                        null, "pix", "Fornecedor", "approved", null, "BR", null, OffsetDateTime.now()));

        // Act + Assert
        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class)
                .hasMessage("BlindPay bank account response has no id");
    }

    private ProviderAccountEntity account(
            CompanyEntity company, String customerId, BlindPayOnboardingStatus status) {
        return ProviderAccountEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .provider(IntegrationProvider.BLINDPAY)
                .externalCustomerId(customerId)
                .status(status == null ? null : status.name())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
