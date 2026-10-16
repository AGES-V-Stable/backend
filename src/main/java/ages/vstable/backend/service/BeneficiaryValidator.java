package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
class BeneficiaryValidator {

    private static final Set<String> SWIFT_CURRENCIES = Set.of(
            "USD", "GBP", "EUR", "HKD", "SGD", "CHF", "AUD", "CAD",
            "NZD", "JPY", "CNY", "THB", "PHP", "INR", "KRW", "IDR");

    void validate(BeneficiaryCreateRequest request) {
        requireNonBlank(request.getNickname(), "nickname");
        if (request.getReceivingMethod() == ReceivingMethod.PIX_KEY) {
            validatePixKey(request);
        } else if (request.getReceivingMethod() == ReceivingMethod.BANK_ACCOUNT) {
            validateBankAccount(request);
        } else if (request.getReceivingMethod() == ReceivingMethod.CRYPTO_WALLET) {
            validateWallet(request);
        }
    }

    private void validatePixKey(BeneficiaryCreateRequest request) {
        requireNonBlank(request.getPixKey(), "pixKey");
        if (hasText(request.getCurrency()) && !"BRL".equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException("currency deve ser BRL para beneficiário PIX");
        }
    }

    private void validateBankAccount(BeneficiaryCreateRequest request) {
        String rail = normalizeRail(request.getPaymentRail());
        switch (rail) {
            case "pix" -> validateBrlBankAccount(request);
            case "ach", "wire" -> validateUsdBankAccount(request);
            case "sepa" -> validateEurBankAccount(request);
            case "international_swift", "swift" -> validateSwiftBankAccount(request);
            default -> throw new IllegalArgumentException(
                    "paymentRail não suportado pela Avenia: " + request.getPaymentRail());
        }
    }

    private void validateBrlBankAccount(BeneficiaryCreateRequest request) {
        requireCurrency(request, "BRL");
        requireNonBlank(request.getBankCode(), "bankCode");
        requireNonBlank(request.getBranchNumber(), "branchNumber");
        requireNonBlank(request.getAccountNumber(), "accountNumber");
        requireNonBlank(request.getAccountType(), "accountType");
    }

    private void validateUsdBankAccount(BeneficiaryCreateRequest request) {
        requireCurrency(request, "USD");
        requireNonBlank(request.getBankName(), "bankName");
        requireNonBlank(request.getAccountNumber(), "accountNumber");
        requireNonBlank(request.getRoutingNumber(), "routingNumber");
        requireAddress(request);
        requireCountryCode(request.getCountryCode(), 3, "countryCode");
    }

    private void validateEurBankAccount(BeneficiaryCreateRequest request) {
        requireCurrency(request, "EUR");
        requireNonBlank(request.getIban(), "iban");
        requireCountryCode(request.getCountryCode(), 3, "countryCode");
    }

    private void validateSwiftBankAccount(BeneficiaryCreateRequest request) {
        requireNonBlank(request.getCurrency(), "currency");
        String currency = request.getCurrency().trim().toUpperCase(Locale.ROOT);
        if (!SWIFT_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("currency não suportada para transferência SWIFT: " + currency);
        }
        requireNonBlank(request.getBankName(), "bankName");
        requireNonBlank(request.getSwiftBic(), "swiftBic");
        if (!hasText(request.getIban()) && !hasText(request.getAccountNumber())) {
            throw new IllegalArgumentException("iban ou accountNumber: Campo obrigatório.");
        }
        requireNonBlank(request.getAddressLine1(), "addressLine1");
        requireNonBlank(request.getCity(), "city");
        requireCountryCode(request.getCountryCode(), 2, "countryCode");
        requireCountryCode(request.getBankCountryCode(), 2, "bankCountryCode");
    }

    private void validateWallet(BeneficiaryCreateRequest request) {
        requireNonBlank(request.getWalletAddress(), "walletAddress");
        requireNonNull(request.getBlockchainNetwork(), "blockchainNetwork");
        requireNonBlank(request.getCurrency(), "currency");
    }

    private void requireAddress(BeneficiaryCreateRequest request) {
        requireNonBlank(request.getAddressLine1(), "addressLine1");
        requireNonBlank(request.getCity(), "city");
        requireNonBlank(request.getStateProvinceRegion(), "stateProvinceRegion");
        requireNonBlank(request.getPostalCode(), "postalCode");
    }

    private void requireCurrency(BeneficiaryCreateRequest request, String expected) {
        requireNonBlank(request.getCurrency(), "currency");
        if (!expected.equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException("currency deve ser " + expected + " para o paymentRail informado");
        }
    }

    private void requireCountryCode(String value, int length, String field) {
        requireNonBlank(value, field);
        if (value.trim().length() != length) {
            throw new IllegalArgumentException(field + " deve ter " + length + " caracteres");
        }
    }

    private String normalizeRail(String value) {
        return value == null || value.isBlank()
                ? "international_swift"
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + ": Campo obrigatório.");
        }
    }

    private void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + ": Campo obrigatório.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
