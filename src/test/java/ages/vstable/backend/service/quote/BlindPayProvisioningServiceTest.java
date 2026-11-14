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
                .accountClass("business").accountType(BankAccountType.checking)
                .accountNumber("123456").country("Brasil").addressLine1("Rua A, 1").build();
        BeneficiaryProviderAccountEntity unlinked = BeneficiaryProviderAccountEntity.builder()
                .beneficiary(beneficiary).provider(IntegrationProvider.BLINDPAY)
                .status("FAILED").lastErrorCode("PREV_ERR").updatedAt(OffsetDateTime.now().minusDays(1)).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, "re_000000000001", BlindPayOnboardingStatus.APPROVED)));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.of(unlinked));
        when(gateway.createBankAccount(eq("re_000000000001"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse(
                        "ba_1", "international_swift", "Fornecedor", "approved",
                        "Fornecedor LLC", "BR", "vendor_or_supplier", OffsetDateTime.now()));

        // Act
        String bankAccountId = service.ensureBankAccount(company, beneficiary);

        // Assert
        assertThat(bankAccountId).isEqualTo("ba_1");
        assertThat(unlinked.getExternalBankAccountId()).isEqualTo("ba_1");
        assertThat(unlinked.getStatus()).isEqualTo("APPROVED");
        assertThat(unlinked.getLastErrorCode()).isNull();
        assertThat(unlinked.getSynchronizedAt()).isNotNull();
        assertThat(unlinked.getUpdatedAt()).isEqualTo(unlinked.getSynchronizedAt());
        verify(gateway, never()).createCustomer(any(), any());
        ArgumentCaptor<BlindPayCreateBankAccountRequest> bank =
                ArgumentCaptor.forClass(BlindPayCreateBankAccountRequest.class);
        verify(gateway).createBankAccount(eq("re_000000000001"), bank.capture(),
                eq("beneficiary:" + beneficiary.getId() + ":blindpay"));
        assertThat(bank.getValue().swiftCodeBic()).isEqualTo("BOFAUS3N");
        assertThat(bank.getValue().swiftBeneficiaryCountry()).isEqualTo("BR");
        assertThat(bank.getValue().accountClass()).isEqualTo(BlindPayApi.BankAccount.AccountClass.business);
        assertThat(bank.getValue().accountType()).isEqualTo(BlindPayApi.BankAccount.AccountType.checking);
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifying", "rejected", "deprecated"})
    void ensureBankAccount_pendingOrRejectedBankAccount_isNotUsedForQuote(String status) {
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, "customer-id", BlindPayOnboardingStatus.APPROVED)));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse(
                        "ba_1", "pix", "Fornecedor", status, null, "BR", null, OffsetDateTime.now()));

        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(BlindPayComplianceException.class)
                .hasMessageContaining("not approved");
        verify(beneficiaryProviderAccountRepository).save(any());
    }

    @Test
    void ensureBankAccount_existingAccount_checksProviderStatusBeforeQuote() {
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        BeneficiaryProviderAccountEntity linked = BeneficiaryProviderAccountEntity.builder()
                .beneficiary(beneficiary).provider(IntegrationProvider.BLINDPAY)
                .externalBankAccountId("ba_1").status("APPROVED").updatedAt(past).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, "customer-id", BlindPayOnboardingStatus.APPROVED)));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.of(linked));
        when(gateway.getBankAccount("customer-id", "ba_1"))
                .thenReturn(new BlindPayBankAccountResponse("ba_1", "pix", "Fornecedor", "verifying",
                        null, "BR", null, OffsetDateTime.now()))
                .thenReturn(new BlindPayBankAccountResponse("ba_1", "pix", "Fornecedor", "approved",
                        null, "BR", null, OffsetDateTime.now()));

        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(BlindPayComplianceException.class);
        assertThat(linked.getStatus()).isEqualTo("VERIFYING");
        assertThat(linked.getSynchronizedAt()).isNotNull();
        assertThat(linked.getUpdatedAt()).isEqualTo(linked.getSynchronizedAt()).isAfter(past);
        verify(gateway, never()).createBankAccount(any(), any(), anyString());

        assertThat(service.ensureBankAccount(company, beneficiary)).isEqualTo("ba_1");
    }

    @Test
    void ensureWallet_approvedCustomer_createsOneManagedWalletPerCompanyAndNetwork() {
        // Arrange
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).build();
        ProviderAccountEntity account = account(company, "customer-id", BlindPayOnboardingStatus.APPROVED);
        ProviderWalletEntity existingUnlinked = ProviderWalletEntity.builder()
                .providerAccount(account).network("polygon").status("FAILED").lastErrorCode("PREV_ERR")
                .updatedAt(OffsetDateTime.now().minusDays(1)).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(account.getId(), "polygon"))
                .thenReturn(Optional.of(existingUnlinked));
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
        ArgumentCaptor<ProviderWalletEntity> saved = ArgumentCaptor.forClass(ProviderWalletEntity.class);
        verify(providerWalletRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalWalletId()).isEqualTo("wallet-id");
        assertThat(saved.getValue().getAddress()).isEqualTo("0x123");
        assertThat(saved.getValue().getStatus()).isEqualTo("CREATED");
        assertThat(saved.getValue().getLastErrorCode()).isNull();
        assertThat(saved.getValue().getSynchronizedAt()).isNotNull();
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(saved.getValue().getSynchronizedAt());
    }

    @Test
    void ensureManagedWallet_storedAddress_isReturnedWithoutCallingBlindPay() {
        // Arrange
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        ProviderAccountEntity account = account(company, "customer-id", BlindPayOnboardingStatus.APPROVED);
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(account.getId(), "base_sepolia"))
                .thenReturn(Optional.of(wallet(account, "bl_1", "0xstored")));

        // Act
        BlindPayProvisioningService.ManagedWallet wallet = service.ensureManagedWallet(company, "base_sepolia");

        // Assert
        assertThat(wallet).isEqualTo(new BlindPayProvisioningService.ManagedWallet("bl_1", "0xstored"));
        verifyNoInteractions(gateway);
    }

    @Test
    void ensureManagedWallet_walletWithoutAddress_fetchesAndStoresAddress() {
        // Arrange
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        ProviderAccountEntity account = account(company, "customer-id", BlindPayOnboardingStatus.APPROVED);
        ProviderWalletEntity stored = wallet(account, "bl_1", null);
        stored.setUpdatedAt(past);
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(account.getId(), "base_sepolia"))
                .thenReturn(Optional.of(stored));
        when(gateway.getWallet("customer-id", "bl_1")).thenReturn(new BlindPayWalletResponse(
                "bl_1", "V-Stable", null, "0xfetched", BlindPayApi.BlockchainWallet.Network.base_sepolia,
                OffsetDateTime.now()));

        // Act
        BlindPayProvisioningService.ManagedWallet wallet = service.ensureManagedWallet(company, "base_sepolia");

        // Assert
        assertThat(wallet.address()).isEqualTo("0xfetched");
        assertThat(stored.getAddress()).isEqualTo("0xfetched");
        assertThat(stored.getSynchronizedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getSynchronizedAt()).isAfter(past);
        verify(providerWalletRepository).save(stored);
    }

    @Test
    void ensureManagedWallet_providerWithoutAddress_isRejected() {
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        ProviderAccountEntity account = account(company, "customer-id", BlindPayOnboardingStatus.APPROVED);
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(account.getId(), "base_sepolia"))
                .thenReturn(Optional.of(wallet(account, "bl_1", null)));
        when(gateway.getWallet("customer-id", "bl_1")).thenReturn(new BlindPayWalletResponse(
                "bl_1", "V-Stable", null, null, BlindPayApi.BlockchainWallet.Network.base_sepolia,
                OffsetDateTime.now()));

        assertThatThrownBy(() -> service.ensureManagedWallet(company, "base_sepolia"))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class)
                .hasMessage("BlindPay wallet response has no address");
    }

    private ProviderWalletEntity wallet(ProviderAccountEntity account, String walletId, String address) {
        return ProviderWalletEntity.builder().id(UUID.randomUUID()).providerAccount(account)
                .network("base_sepolia").externalWalletId(walletId).address(address).status("CREATED")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
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
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").build();
        BeneficiaryProviderAccountEntity link = BeneficiaryProviderAccountEntity.builder()
                .beneficiary(beneficiary).provider(IntegrationProvider.BLINDPAY).updatedAt(past).build();
        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account(company, "customer-id", BlindPayOnboardingStatus.APPROVED)));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.of(link));
        when(gateway.createBankAccount(eq("customer-id"), any(), anyString()))
                .thenThrow(ages.vstable.backend.exception.BlindPayIntegrationException.response(
                        "bad bank", 400, "BANK_ERR", "t", null));

        // Act + Assert
        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class);
        assertThat(link.getStatus()).isEqualTo("FAILED");
        assertThat(link.getLastErrorCode()).isEqualTo("BANK_ERR");
        assertThat(link.getUpdatedAt()).isAfter(past);
    }

    @Test
    void ensureBankAccountAndWallet_handleConcurrencyCountryNormalizationNullStatusAndFailures() {
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        ProviderAccountEntity acc = account(company, "customer-id", BlindPayOnboardingStatus.APPROVED);
        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(acc));

        // 1. Null bank account status sets CREATED before throwing compliance exception; countryCode("us") -> "US", "Brazil" -> "BR", null -> null
        BeneficiaryEntity benUs = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com")
                .countryCode("us").accountType(BankAccountType.savings).build();
        BeneficiaryProviderAccountEntity linkNullStatus = BeneficiaryProviderAccountEntity.builder()
                .beneficiary(benUs).provider(IntegrationProvider.BLINDPAY).build();
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(benUs.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(linkNullStatus));
        when(gateway.createBankAccount(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse("ba_null_st", "pix", "US", null, null, "US", null, OffsetDateTime.now()));

        assertThatThrownBy(() -> service.ensureBankAccount(company, benUs))
                .isInstanceOf(BlindPayComplianceException.class);
        assertThat(linkNullStatus.getStatus()).isEqualTo("CREATED");
        ArgumentCaptor<BlindPayCreateBankAccountRequest> reqCaptor = ArgumentCaptor.forClass(BlindPayCreateBankAccountRequest.class);
        verify(gateway).createBankAccount(eq("customer-id"), reqCaptor.capture(), anyString());
        assertThat(reqCaptor.getValue().country()).isEqualTo("US");
        assertThat(reqCaptor.getValue().accountType()).isEqualTo(BlindPayApi.BankAccount.AccountType.saving);

        BeneficiaryEntity benBrazil = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").country("Brazil").build();
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(benBrazil.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("customer-id"), any(), eq("beneficiary:" + benBrazil.getId() + ":blindpay")))
                .thenReturn(new BlindPayBankAccountResponse("ba_br", "pix", "BR", "approved", null, "BR", null, OffsetDateTime.now()));
        assertThat(service.ensureBankAccount(company, benBrazil)).isEqualTo("ba_br");
        verify(gateway, atLeastOnce()).createBankAccount(eq("customer-id"), reqCaptor.capture(), anyString());
        assertThat(reqCaptor.getValue().country()).isEqualTo("BR");

        BeneficiaryEntity benNullCountry = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").country(null).countryCode(null).build();
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(benNullCountry.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("customer-id"), any(), eq("beneficiary:" + benNullCountry.getId() + ":blindpay")))
                .thenReturn(new BlindPayBankAccountResponse("ba_null_c", "pix", "NC", "approved", null, "BR", null, OffsetDateTime.now()));
        assertThat(service.ensureBankAccount(company, benNullCountry)).isEqualTo("ba_null_c");
        verify(gateway, atLeastOnce()).createBankAccount(eq("customer-id"), reqCaptor.capture(), anyString());
        assertThat(reqCaptor.getValue().country()).isNull();

        // 2. Concurrent bank account save recovery and failure
        BeneficiaryEntity benConc = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").build();
        BeneficiaryProviderAccountEntity concSaved = BeneficiaryProviderAccountEntity.builder()
                .beneficiary(benConc).provider(IntegrationProvider.BLINDPAY).externalBankAccountId("ba_conc").build();
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(benConc.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concSaved))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concSaved))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("customer-id"), any(), eq("beneficiary:" + benConc.getId() + ":blindpay")))
                .thenReturn(new BlindPayBankAccountResponse("ba_conc", "pix", "C", "approved", null, "BR", null, OffsetDateTime.now()));
        when(beneficiaryProviderAccountRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup bank"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup bank"))
                .thenReturn(concSaved)
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup bank"));
        when(gateway.getBankAccount("customer-id", "ba_conc"))
                .thenReturn(new BlindPayBankAccountResponse("ba_conc", "pix", "C", "approved", null, "BR", null, OffsetDateTime.now()))
                .thenReturn(new BlindPayBankAccountResponse("ba_mismatch", "pix", "C", "approved", null, "BR", null, OffsetDateTime.now()));

        assertThat(service.ensureBankAccount(company, benConc)).isEqualTo("ba_conc");
        assertThatThrownBy(() -> service.ensureBankAccount(company, benConc))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class);
        assertThatThrownBy(() -> service.ensureBankAccount(company, benConc))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 3. Concurrent wallet save recovery, wallet failure, and ensureManagedWallet id mismatch
        ProviderWalletEntity concWallet = wallet(acc, "w_conc", "0x1");
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(acc.getId(), "polygon"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concWallet))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(gateway.createWallet(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayWalletResponse("w_conc", "V", "e", "0x1",
                        BlindPayApi.BlockchainWallet.Network.polygon, OffsetDateTime.now()));
        when(providerWalletRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup wallet"));

        assertThat(service.ensureWallet(company, "polygon")).isEqualTo("w_conc");
        assertThatThrownBy(() -> service.ensureWallet(company, "polygon"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // Wallet creation failure records FAILED, lastErrorCode, updatedAt
        ProviderWalletEntity failingWallet = ProviderWalletEntity.builder()
                .providerAccount(acc).network("polygon").updatedAt(past).build();
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(acc.getId(), "polygon"))
                .thenReturn(Optional.of(failingWallet));
        doReturn(failingWallet).when(providerWalletRepository).save(any());
        when(gateway.createWallet(eq("customer-id"), any(), anyString()))
                .thenThrow(ages.vstable.backend.exception.BlindPayIntegrationException.response(
                        "wallet err", 500, "WALLET_ERR", "t", null));
        assertThatThrownBy(() -> service.ensureWallet(company, "polygon"))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class);
        assertThat(failingWallet.getStatus()).isEqualTo("FAILED");
        assertThat(failingWallet.getLastErrorCode()).isEqualTo("WALLET_ERR");
        assertThat(failingWallet.getUpdatedAt()).isAfter(past);

        // ensureManagedWallet when second lookup finds wallet with different externalWalletId
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(acc.getId(), "base_sepolia"))
                .thenReturn(Optional.of(wallet(acc, "bl_1", "0x1")))
                .thenReturn(Optional.of(wallet(acc, "bl_other", "0x1")));
        assertThatThrownBy(() -> service.ensureManagedWallet(company, "base_sepolia"))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class)
                .hasMessage("BlindPay managed wallet is not registered");
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
