package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Corpo de POST /v1/instances/{instance_id}/customers/{customer_id}/bank-accounts.
 * Cada {@code type} (trilho de pagamento) exige um grupo de campos diferente; a
 * lista exata por trilho vem de GET /v1/available/bank-details?rail={type}.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayCreateBankAccountRequest(
        BlindPayApi.BankAccount.Type type,
        String name,
        String recipientRelationship,
        String beneficiaryName,
        BlindPayApi.BankAccount.AccountClass accountClass,
        String phoneNumber,
        String taxId,

        // ACH, wire e RTP (EUA)
        String routingNumber,
        String accountNumber,
        BlindPayApi.BankAccount.AccountType accountType,
        @JsonProperty("address_line_1") String addressLine1,
        @JsonProperty("address_line_2") String addressLine2,
        String city,
        String stateProvinceRegion,
        String country,
        String postalCode,

        // PIX e TED (Brasil)
        String pixKey,
        String tedBankCode,
        String tedBranchCode,
        String tedCpfCnpj,

        // SPEI (México)
        String speiProtocol,
        String speiInstitutionCode,
        String speiClabe,

        // SWIFT internacional
        String swiftCodeBic,
        String swiftAccountHolderName,
        String swiftAccountNumberIban,
        @JsonProperty("swift_beneficiary_address_line_1") String swiftBeneficiaryAddressLine1,
        @JsonProperty("swift_beneficiary_address_line_2") String swiftBeneficiaryAddressLine2,
        String swiftBeneficiaryCountry,
        String swiftBeneficiaryCity,
        String swiftBeneficiaryStateProvinceRegion,
        String swiftBeneficiaryPostalCode,
        String swiftBankName,
        @JsonProperty("swift_bank_address_line_1") String swiftBankAddressLine1,
        @JsonProperty("swift_bank_address_line_2") String swiftBankAddressLine2,
        String swiftBankCountry,
        String swiftBankCity,
        String swiftBankStateProvinceRegion,
        String swiftBankPostalCode,
        String swiftPaymentCode,

        // SEPA (Europa)
        String sepaIban,
        String sepaBeneficiaryBic,
        String sepaBeneficiaryLegalName,
        @JsonProperty("sepa_beneficiary_address_line_1") String sepaBeneficiaryAddressLine1,
        @JsonProperty("sepa_beneficiary_address_line_2") String sepaBeneficiaryAddressLine2,
        String sepaBeneficiaryCity,
        String sepaBeneficiaryStateProvinceRegion,
        String sepaBeneficiaryPostalCode,
        String sepaBeneficiaryCountry
) {

    public BlindPayCreateBankAccountRequest {
        RequestValidation.requireNonNull(type, "type");
        RequestValidation.requireText(name, "name");
    }
}
