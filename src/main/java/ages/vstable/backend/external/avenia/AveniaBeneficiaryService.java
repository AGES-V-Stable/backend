package ages.vstable.backend.external.avenia;

import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryRegistration;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaBrlBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaEurBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaSwiftBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaUsdBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AveniaBeneficiaryService {

    private final AveniaGateway aveniaGateway;

    public AveniaBeneficiaryRegistration register(BeneficiaryEntity beneficiary, String subAccountId) {
        return switch (beneficiary.getReceivingMethod()) {
            case PIX_KEY -> bankAccount(aveniaGateway.createBrlBeneficiary(brlPixRequest(beneficiary, subAccountId)));
            case CRYPTO_WALLET -> wallet(aveniaGateway.createWalletBeneficiary(walletRequest(beneficiary, subAccountId)));
            case BANK_ACCOUNT -> registerBankAccount(beneficiary, subAccountId);
        };
    }

    private AveniaBeneficiaryRegistration registerBankAccount(
            BeneficiaryEntity beneficiary, String subAccountId) {
        return switch (paymentRail(beneficiary)) {
            case "pix" -> bankAccount(aveniaGateway.createBrlBeneficiary(
                    brlBankRequest(beneficiary, subAccountId)));
            case "ach", "wire" -> bankAccount(aveniaGateway.createUsdBeneficiary(
                    usdRequest(beneficiary, subAccountId)));
            case "sepa" -> bankAccount(aveniaGateway.createEurBeneficiary(
                    eurRequest(beneficiary, subAccountId)));
            case "international_swift", "swift" -> bankAccount(aveniaGateway.createSwiftBeneficiary(
                    swiftRequest(beneficiary, subAccountId)));
            default -> throw new UnprocessableEntityException(
                    "Método de recebimento bancário não suportado pela Avenia: " + beneficiary.getPaymentRail());
        };
    }

    private AveniaBrlBeneficiaryRequest brlPixRequest(BeneficiaryEntity beneficiary, String subAccountId) {
        return new AveniaBrlBeneficiaryRequest(
                beneficiary.getNickname(), beneficiary.getInternalDescription(), beneficiary.getPixKey(),
                null, null, null, null, null, null, subAccountId);
    }

    private AveniaBrlBeneficiaryRequest brlBankRequest(BeneficiaryEntity beneficiary, String subAccountId) {
        return new AveniaBrlBeneficiaryRequest(
                beneficiary.getNickname(), beneficiary.getInternalDescription(), null,
                beneficiary.getIdentificationDocument(), beneficiary.getAccountHolderName(),
                beneficiary.getBankCode(), beneficiary.getBranchNumber(), beneficiary.getAccountNumber(),
                beneficiary.getAccountType() == null ? null : beneficiary.getAccountType().name(), subAccountId);
    }

    private AveniaUsdBeneficiaryRequest usdRequest(BeneficiaryEntity beneficiary, String subAccountId) {
        AveniaUsdBeneficiaryRequest.Address address = new AveniaUsdBeneficiaryRequest.Address(
                beneficiary.getAddressLine1(), beneficiary.getAddressLine2(), beneficiary.getCity(),
                beneficiary.getStateProvinceRegion(), beneficiary.getPostalCode(), beneficiary.getCountryCode());
        return new AveniaUsdBeneficiaryRequest(
                beneficiary.getNickname(), beneficiary.getInternalDescription(), beneficiary.getAccountNumber(),
                beneficiary.getRoutingNumber(), beneficiary.getAccountHolderName(), beneficiary.getBankName(),
                address, subAccountId);
    }

    private AveniaEurBeneficiaryRequest eurRequest(BeneficiaryEntity beneficiary, String subAccountId) {
        return new AveniaEurBeneficiaryRequest(
                beneficiary.getNickname(), beneficiary.getInternalDescription(), beneficiary.getIban(),
                beneficiary.getSwiftBic(), beneficiary.getCountryCode(), beneficiary.getAccountHolderName(),
                subAccountId);
    }

    private AveniaSwiftBeneficiaryRequest swiftRequest(BeneficiaryEntity beneficiary, String subAccountId) {
        boolean usesIban = hasText(beneficiary.getIban());
        return new AveniaSwiftBeneficiaryRequest(
                beneficiary.getNickname(), beneficiary.getInternalDescription(), beneficiary.getAccountHolderName(),
                beneficiaryType(beneficiary.getBeneficiaryType()), usesIban ? "iban" : "account_number",
                usesIban ? beneficiary.getIban() : beneficiary.getAccountNumber(), beneficiary.getSwiftBic(),
                beneficiary.getBankName(), beneficiary.getBankCountryCode(), beneficiary.getAddressLine1(),
                beneficiary.getCity(), beneficiary.getStateProvinceRegion(), beneficiary.getPostalCode(),
                beneficiary.getCountryCode(), uppercase(beneficiary.getCurrency()), null,
                beneficiary.getRoutingNumber(), subAccountId);
    }

    private AveniaWalletBeneficiaryRequest walletRequest(BeneficiaryEntity beneficiary, String subAccountId) {
        return new AveniaWalletBeneficiaryRequest(
                beneficiary.getNickname(), beneficiary.getInternalDescription(), beneficiary.getWalletAddress(),
                beneficiary.getBlockchainNetwork().name().toUpperCase(Locale.ROOT), beneficiary.getWalletMemo(),
                subAccountId);
    }

    private AveniaBeneficiaryRegistration bankAccount(AveniaBeneficiaryResponse response) {
        return AveniaBeneficiaryRegistration.bankAccount(response.id());
    }

    private AveniaBeneficiaryRegistration wallet(AveniaBeneficiaryResponse response) {
        return AveniaBeneficiaryRegistration.wallet(response.id());
    }

    public String paymentRail(BeneficiaryEntity beneficiary) {
        return beneficiary.getPaymentRail() == null || beneficiary.getPaymentRail().isBlank()
                ? "international_swift"
                : beneficiary.getPaymentRail().trim().toLowerCase(Locale.ROOT);
    }

    private String beneficiaryType(String value) {
        if ("INDIVIDUAL".equalsIgnoreCase(value) || "individual".equalsIgnoreCase(value)) {
            return "individual";
        }
        if ("LEGAL_ENTITY".equalsIgnoreCase(value) || "company".equalsIgnoreCase(value)) {
            return "company";
        }
        throw new IllegalArgumentException("beneficiaryType deve ser INDIVIDUAL ou LEGAL_ENTITY");
    }

    private String uppercase(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
