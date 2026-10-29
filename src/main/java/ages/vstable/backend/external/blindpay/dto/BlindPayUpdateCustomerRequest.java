package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.util.List;

/**
 * Corpo de PUT /v1/instances/{instance_id}/customers/{id} para cliente empresa.
 * Não aceita {@code type} nem {@code kyc_type}; cada sócio precisa do {@code id}
 * devolvido pela BlindPay. Atualizar um cliente rejeitado não reinicia o KYB.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayUpdateCustomerRequest(
        String email,
        String taxId,
        @JsonProperty(BlindPayApi.Customer.ADDRESS_LINE_1) String addressLine1,
        @JsonProperty(BlindPayApi.Customer.ADDRESS_LINE_2) String addressLine2,
        String city,
        String stateProvinceRegion,
        String country,
        String postalCode,
        String phoneNumber,
        String legalName,
        String alternateName,
        LocalDate formationDate,
        String website,
        BlindPayApi.Customer.BusinessType businessType,
        String businessDescription,
        String businessIndustry,
        String estimatedAnnualRevenue,
        String sourceOfWealth,
        String incorporationDocFile,
        String proofOfOwnershipDocFile,
        String proofOfAddressDocType,
        String proofOfAddressDocFile,
        String sourceOfFundsDocType,
        String sourceOfFundsDocFile,
        String purposeOfTransactions,
        String purposeOfTransactionsExplanation,
        String accountPurpose,
        String accountPurposeOther,
        Boolean publiclyTraded,
        List<BlindPayCreateCustomerRequest.Owner> owners,
        String externalId,
        String tosId
) {

    public BlindPayUpdateCustomerRequest {
        RequestValidation.requireText(email, "email");
        RequestValidation.requireText(country, "country");
        if (owners != null) {
            owners.forEach(owner -> RequestValidation.requireText(owner.id(), "owner id"));
            owners = List.copyOf(owners);
        }
    }
}
