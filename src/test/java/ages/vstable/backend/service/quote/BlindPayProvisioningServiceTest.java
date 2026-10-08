package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.*;
import ages.vstable.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
    @Mock private UserRepository userRepository;

    @Test
    void ensureBankAccount_createsCustomerAndSwiftAccountUsingStableIdempotencyKeys() {
        BlindPayProvisioningService service = new BlindPayProvisioningService(
                gateway, providerAccountRepository, beneficiaryProviderAccountRepository,
                providerWalletRepository, userRepository);
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).legalName("Empresa Ltda")
                .cnpj("11222333000181").country("Brasil").zipCode("90000000").city("Porto Alegre")
                .state("RS").build();
        UserEntity representative = UserEntity.builder().email("owner@example.com").companyId(company.getId()).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").accountHolderName("Fornecedor LLC")
                .identificationDocument("123").receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .paymentRail("international_swift").swiftBic("BOFAUS3N")
                .accountNumber("123456").country("Brasil").addressLine1("Rua A, 1").build();

        when(providerAccountRepository.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByCompanyIdOrderByCreatedAtAsc(company.getId()))
                .thenReturn(Optional.of(representative));
        when(gateway.createCustomer(any(), anyString()))
                .thenReturn(new BlindPayCustomerCreatedResponse("cu_1", null));
        when(providerAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("cu_1"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse(
                        "ba_1", "international_swift", "Fornecedor", "approved",
                        "Fornecedor LLC", "BR", "vendor_or_supplier", OffsetDateTime.now()));

        String bankAccountId = service.ensureBankAccount(company, beneficiary);

        assertThat(bankAccountId).isEqualTo("ba_1");
        ArgumentCaptor<BlindPayCreateCustomerRequest> customer =
                ArgumentCaptor.forClass(BlindPayCreateCustomerRequest.class);
        verify(gateway).createCustomer(customer.capture(), eq("company:" + company.getId() + ":blindpay"));
        assertThat(customer.getValue().country()).isEqualTo("BR");
        assertThat(customer.getValue().externalId()).isEqualTo(company.getId().toString());

        ArgumentCaptor<BlindPayCreateBankAccountRequest> bank =
                ArgumentCaptor.forClass(BlindPayCreateBankAccountRequest.class);
        verify(gateway).createBankAccount(eq("cu_1"), bank.capture(),
                eq("beneficiary:" + beneficiary.getId() + ":blindpay"));
        assertThat(bank.getValue().swiftCodeBic()).isEqualTo("BOFAUS3N");
        assertThat(bank.getValue().swiftBeneficiaryCountry()).isEqualTo("BR");
    }

    @Test
    void ensureWallet_createsOneManagedWalletPerCompanyAndNetwork() {
        BlindPayProvisioningService service = new BlindPayProvisioningService(
                gateway, providerAccountRepository, beneficiaryProviderAccountRepository,
                providerWalletRepository, userRepository);
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).build();
        ProviderAccountEntity account = ProviderAccountEntity.builder()
                .id(UUID.randomUUID())
                .company(company)
                .provider(IntegrationProvider.BLINDPAY)
                .externalCustomerId("customer-id")
                .status("CREATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(providerWalletRepository.findByProviderAccountIdAndNetwork(account.getId(), "polygon"))
                .thenReturn(Optional.empty());
        when(gateway.createWallet(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayWalletResponse(
                        "wallet-id", "V-Stable polygon", companyId + ":polygon",
                        "0x123", BlindPayApi.BlockchainWallet.Network.polygon, OffsetDateTime.now()));
        when(providerWalletRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String walletId = service.ensureWallet(company, "polygon");

        assertThat(walletId).isEqualTo("wallet-id");
        ArgumentCaptor<BlindPayCreateWalletRequest> request =
                ArgumentCaptor.forClass(BlindPayCreateWalletRequest.class);
        verify(gateway).createWallet(eq("customer-id"), request.capture(),
                eq("company:" + companyId + ":blindpay:wallet:polygon"));
        assertThat(request.getValue().network()).isEqualTo(BlindPayApi.BlockchainWallet.Network.polygon);
        assertThat(request.getValue().externalId()).isEqualTo(companyId + ":polygon");
    }

    @Test
    void ensureBankAccount_customerInsertRace_reusesWinningProviderAccount() {
        BlindPayProvisioningService service = new BlindPayProvisioningService(
                gateway, providerAccountRepository, beneficiaryProviderAccountRepository,
                providerWalletRepository, userRepository);
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).legalName("Empresa Ltda")
                .cnpj("11222333000181").country("BR").zipCode("90000000").state("RS").build();
        UserEntity representative = UserEntity.builder().email("owner@example.com").companyId(companyId).build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company).build();
        ProviderAccountEntity winner = ProviderAccountEntity.builder()
                .id(UUID.randomUUID()).company(company).provider(IntegrationProvider.BLINDPAY)
                .externalCustomerId("customer-winner").build();
        BeneficiaryProviderAccountEntity existingBank = BeneficiaryProviderAccountEntity.builder()
                .beneficiary(beneficiary).provider(IntegrationProvider.BLINDPAY)
                .externalBankAccountId("bank-account-winner").build();

        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(userRepository.findFirstByCompanyIdOrderByCreatedAtAsc(companyId))
                .thenReturn(Optional.of(representative));
        when(gateway.createCustomer(any(), anyString()))
                .thenReturn(new BlindPayCustomerCreatedResponse("customer-winner", null));
        when(providerAccountRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.of(existingBank));

        assertThat(service.ensureBankAccount(company, beneficiary)).isEqualTo("bank-account-winner");
        verify(gateway, never()).createBankAccount(anyString(), any(), anyString());
    }

    @Test
    void ensureBankAccount_responseWithoutId_isRejected() {
        BlindPayProvisioningService service = new BlindPayProvisioningService(
                gateway, providerAccountRepository, beneficiaryProviderAccountRepository,
                providerWalletRepository, userRepository);
        UUID companyId = UUID.randomUUID();
        CompanyEntity company = CompanyEntity.builder().id(companyId).build();
        ProviderAccountEntity account = ProviderAccountEntity.builder()
                .id(UUID.randomUUID()).company(company).provider(IntegrationProvider.BLINDPAY)
                .externalCustomerId("customer-id").build();
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .nickname("Fornecedor").paymentRail("pix").pixKey("pix@example.com").build();

        when(providerAccountRepository.findByCompanyIdAndProvider(companyId, IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(beneficiaryProviderAccountRepository.findByBeneficiaryIdAndProvider(
                beneficiary.getId(), IntegrationProvider.BLINDPAY)).thenReturn(Optional.empty());
        when(gateway.createBankAccount(eq("customer-id"), any(), anyString()))
                .thenReturn(new BlindPayBankAccountResponse(
                        null, "pix", "Fornecedor", "approved", null, "BR", null, OffsetDateTime.now()));

        assertThatThrownBy(() -> service.ensureBankAccount(company, beneficiary))
                .isInstanceOf(ages.vstable.backend.exception.BlindPayIntegrationException.class)
                .hasMessage("BlindPay bank account response has no id");
    }
}
