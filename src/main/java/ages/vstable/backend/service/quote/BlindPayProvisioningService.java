package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.*;
import ages.vstable.backend.repository.BeneficiaryProviderAccountRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import ages.vstable.backend.repository.ProviderWalletRepository;
import ages.vstable.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class BlindPayProvisioningService {

    private final BlindPayGateway blindPayGateway;
    private final ProviderAccountRepository providerAccountRepository;
    private final BeneficiaryProviderAccountRepository beneficiaryProviderAccountRepository;
    private final ProviderWalletRepository providerWalletRepository;
    private final UserRepository userRepository;

    public String ensureBankAccount(CompanyEntity company, BeneficiaryEntity beneficiary) {
        ProviderAccountEntity providerAccount = ensureCustomer(company);
        BeneficiaryProviderAccountEntity existing = beneficiaryProviderAccountRepository
                .findByBeneficiaryIdAndProvider(beneficiary.getId(), IntegrationProvider.BLINDPAY)
                .orElse(null);
        if (existing != null && hasText(existing.getExternalBankAccountId())) {
            return existing.getExternalBankAccountId();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BeneficiaryProviderAccountEntity link = existing == null
                ? BeneficiaryProviderAccountEntity.builder()
                    .beneficiary(beneficiary)
                    .provider(IntegrationProvider.BLINDPAY)
                    .status("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build()
                : existing;

        try {
            BlindPayBankAccountResponse response = blindPayGateway.createBankAccount(
                    providerAccount.getExternalCustomerId(), bankAccountRequest(beneficiary),
                    "beneficiary:" + beneficiary.getId() + ":blindpay");
            link.setExternalBankAccountId(response.id());
            link.setStatus(response.status() == null ? "CREATED" : response.status().toUpperCase());
            link.setLastErrorCode(null);
            link.setSynchronizedAt(now);
            link.setUpdatedAt(now);
            beneficiaryProviderAccountRepository.save(link);
            return response.id();
        } catch (BlindPayIntegrationException ex) {
            link.setStatus("FAILED");
            link.setLastErrorCode(ex.getProviderErrorCode());
            link.setUpdatedAt(now);
            beneficiaryProviderAccountRepository.save(link);
            throw ex;
        }
    }

    public String ensureWallet(CompanyEntity company, String networkValue) {
        BlindPayApi.BlockchainWallet.Network network;
        try {
            network = BlindPayApi.BlockchainWallet.Network.valueOf(networkValue.toLowerCase());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Unsupported BlindPay network: " + networkValue);
        }

        ProviderAccountEntity providerAccount = ensureCustomer(company);
        ProviderWalletEntity existing = providerWalletRepository
                .findByProviderAccountIdAndNetwork(providerAccount.getId(), network.name())
                .orElse(null);
        if (existing != null && hasText(existing.getExternalWalletId())) {
            return existing.getExternalWalletId();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProviderWalletEntity wallet = existing == null
                ? ProviderWalletEntity.builder()
                    .providerAccount(providerAccount)
                    .network(network.name())
                    .status("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build()
                : existing;

        try {
            BlindPayWalletResponse response = blindPayGateway.createWallet(
                    providerAccount.getExternalCustomerId(),
                    new BlindPayCreateWalletRequest(
                            network,
                            company.getId() + ":" + network.name(),
                            "V-Stable " + network.name()),
                    "company:" + company.getId() + ":blindpay:wallet:" + network.name());
            if (response == null || !hasText(response.id())) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay wallet response has no id");
            }
            wallet.setExternalWalletId(response.id());
            wallet.setStatus("CREATED");
            wallet.setLastErrorCode(null);
            wallet.setSynchronizedAt(now);
            wallet.setUpdatedAt(now);
            providerWalletRepository.save(wallet);
            return response.id();
        } catch (BlindPayIntegrationException ex) {
            wallet.setStatus("FAILED");
            wallet.setLastErrorCode(ex.getProviderErrorCode());
            wallet.setUpdatedAt(now);
            providerWalletRepository.save(wallet);
            throw ex;
        }
    }

    private ProviderAccountEntity ensureCustomer(CompanyEntity company) {
        ProviderAccountEntity existing = providerAccountRepository
                .findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY)
                .orElse(null);
        if (existing != null && hasText(existing.getExternalCustomerId())) {
            return existing;
        }

        UserEntity representative = userRepository.findFirstByCompanyIdOrderByCreatedAtAsc(company.getId())
                .orElseThrow(() -> new IllegalStateException("Company has no representative"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProviderAccountEntity account = existing == null
                ? ProviderAccountEntity.builder()
                    .company(company)
                    .provider(IntegrationProvider.BLINDPAY)
                    .status("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build()
                : existing;

        try {
            BlindPayCustomerCreatedResponse response = blindPayGateway.createCustomer(
                    BlindPayCreateCustomerRequest.builder()
                            .type(BlindPayApi.Customer.Type.business)
                            .kycType(BlindPayApi.Customer.KycType.standard)
                            .email(representative.getEmail())
                            .taxId(company.getCnpj())
                            .legalName(company.getLegalName())
                            .city(company.getCity())
                            .stateProvinceRegion(company.getState())
                            .country(countryCode(company.getCountry()))
                            .postalCode(company.getZipCode())
                            .externalId(company.getId().toString())
                            .build(),
                    "company:" + company.getId() + ":blindpay");
            String customerId = hasText(response.customerId()) ? response.customerId() : response.id();
            if (!hasText(customerId)) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay customer response has no id");
            }
            account.setExternalCustomerId(customerId);
            account.setStatus("CREATED");
            account.setLastErrorCode(null);
            account.setSynchronizedAt(now);
            account.setUpdatedAt(now);
            return providerAccountRepository.save(account);
        } catch (BlindPayIntegrationException ex) {
            account.setStatus("FAILED");
            account.setLastErrorCode(ex.getProviderErrorCode());
            account.setUpdatedAt(now);
            providerAccountRepository.save(account);
            throw ex;
        }
    }

    private BlindPayCreateBankAccountRequest bankAccountRequest(BeneficiaryEntity beneficiary) {
        BlindPayApi.BankAccount.Type type;
        try {
            type = BlindPayApi.BankAccount.Type.valueOf(beneficiary.getPaymentRail());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Unsupported BlindPay payment rail: " + beneficiary.getPaymentRail());
        }

        return BlindPayCreateBankAccountRequest.builder()
                .type(type)
                .name(beneficiary.getNickname())
                .recipientRelationship(beneficiary.getRecipientRelationship())
                .beneficiaryName(beneficiary.getAccountHolderName())
                .accountClass(accountClass(beneficiary.getAccountClass()))
                .taxId(beneficiary.getIdentificationDocument())
                .routingNumber(beneficiary.getRoutingNumber())
                .accountNumber(beneficiary.getAccountNumber())
                .accountType(accountType(beneficiary))
                .addressLine1(beneficiary.getAddressLine1())
                .addressLine2(beneficiary.getAddressLine2())
                .city(beneficiary.getCity())
                .stateProvinceRegion(beneficiary.getStateProvinceRegion())
                .country(countryCode(firstText(beneficiary.getCountryCode(), beneficiary.getCountry())))
                .postalCode(beneficiary.getPostalCode())
                .pixKey(beneficiary.getPixKey())
                .tedBankCode(beneficiary.getBankCode())
                .tedBranchCode(beneficiary.getBranchNumber())
                .tedCpfCnpj(beneficiary.getIdentificationDocument())
                .swiftCodeBic(beneficiary.getSwiftBic())
                .swiftAccountHolderName(beneficiary.getAccountHolderName())
                .swiftAccountNumberIban(firstText(beneficiary.getIban(), beneficiary.getAccountNumber()))
                .swiftBeneficiaryAddressLine1(beneficiary.getAddressLine1())
                .swiftBeneficiaryAddressLine2(beneficiary.getAddressLine2())
                .swiftBeneficiaryCountry(countryCode(firstText(beneficiary.getCountryCode(), beneficiary.getCountry())))
                .swiftBeneficiaryCity(beneficiary.getCity())
                .swiftBeneficiaryStateProvinceRegion(beneficiary.getStateProvinceRegion())
                .swiftBeneficiaryPostalCode(beneficiary.getPostalCode())
                .swiftBankName(beneficiary.getBankName())
                .swiftBankAddressLine1(beneficiary.getBankAddressLine1())
                .swiftBankAddressLine2(beneficiary.getBankAddressLine2())
                .swiftBankCountry(beneficiary.getBankCountryCode())
                .swiftBankCity(beneficiary.getBankCity())
                .swiftBankStateProvinceRegion(beneficiary.getBankStateProvinceRegion())
                .swiftBankPostalCode(beneficiary.getBankPostalCode())
                .swiftPaymentCode(beneficiary.getSwiftPaymentCode())
                .sepaIban(beneficiary.getIban())
                .sepaBeneficiaryBic(beneficiary.getSwiftBic())
                .sepaBeneficiaryLegalName(beneficiary.getAccountHolderName())
                .sepaBeneficiaryAddressLine1(beneficiary.getAddressLine1())
                .sepaBeneficiaryAddressLine2(beneficiary.getAddressLine2())
                .sepaBeneficiaryCity(beneficiary.getCity())
                .sepaBeneficiaryStateProvinceRegion(beneficiary.getStateProvinceRegion())
                .sepaBeneficiaryPostalCode(beneficiary.getPostalCode())
                .sepaBeneficiaryCountry(countryCode(firstText(beneficiary.getCountryCode(), beneficiary.getCountry())))
                .build();
    }

    private BlindPayApi.BankAccount.AccountClass accountClass(String value) {
        if (!hasText(value)) {
            return null;
        }
        return BlindPayApi.BankAccount.AccountClass.valueOf(value.toLowerCase());
    }

    private BlindPayApi.BankAccount.AccountType accountType(BeneficiaryEntity beneficiary) {
        if (beneficiary.getAccountType() == null) {
            return null;
        }
        return switch (beneficiary.getAccountType()) {
            case checking -> BlindPayApi.BankAccount.AccountType.checking;
            case savings -> BlindPayApi.BankAccount.AccountType.saving;
            default -> null;
        };
    }

    private String countryCode(String country) {
        if (!hasText(country)) {
            return country;
        }
        String normalized = country.trim();
        if (normalized.length() == 2) {
            return normalized.toUpperCase();
        }
        if (normalized.equalsIgnoreCase("Brasil") || normalized.equalsIgnoreCase("Brazil")) {
            return "BR";
        }
        throw new IllegalArgumentException("Country must use ISO 3166-1 alpha-2: " + country);
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
