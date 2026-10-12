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
 * Corpo de POST /v1/instances/{instance_id}/customers. Serve tanto para pessoa
 * física (KYC) quanto para empresa (KYB); a BlindPay valida os campos exigidos
 * por cada combinação de {@code type} e {@code kyc_type}.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlindPayCreateCustomerRequest(
        BlindPayApi.Customer.Type type,
        BlindPayApi.Customer.KycType kycType,
        String email,
        String taxId,
        @JsonProperty(BlindPayApi.Customer.ADDRESS_LINE_1) String addressLine1,
        @JsonProperty(BlindPayApi.Customer.ADDRESS_LINE_2) String addressLine2,
        String city,
        String stateProvinceRegion,
        String country,
        String postalCode,
        String ipAddress,
        String phoneNumber,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String idDocCountry,
        BlindPayApi.Customer.IdDocType idDocType,
        String idDocFrontFile,
        String idDocBackFile,
        String proofOfAddressDocType,
        String proofOfAddressDocFile,
        String selfieFile,
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
        String sourceOfFundsDocType,
        String sourceOfFundsDocFile,
        String purposeOfTransactions,
        String accountPurpose,
        Boolean publiclyTraded,
        String occupation,
        List<Owner> owners,
        String externalId,
        String tosId
) {

    public BlindPayCreateCustomerRequest {
        RequestValidation.requireNonNull(type, "type");
        RequestValidation.requireNonNull(kycType, "kycType");
        RequestValidation.requireText(email, "email");
        RequestValidation.requireText(country, "country");
        owners = owners == null ? null : List.copyOf(owners);
    }

    /** Sócio ou controlador de um cliente empresa (KYB). */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Owner(
            BlindPayApi.Customer.OwnerRole role,
            String firstName,
            String lastName,
            LocalDate dateOfBirth,
            String taxId,
            BlindPayApi.Customer.TaxType taxType,
            @JsonProperty(BlindPayApi.Customer.ADDRESS_LINE_1) String addressLine1,
            @JsonProperty(BlindPayApi.Customer.ADDRESS_LINE_2) String addressLine2,
            String city,
            String stateProvinceRegion,
            String country,
            String postalCode,
            String idDocCountry,
            BlindPayApi.Customer.IdDocType idDocType,
            String idDocFrontFile,
            String idDocBackFile,
            String proofOfAddressDocType,
            String proofOfAddressDocFile,
            Integer ownershipPercentage,
            String title
    ) {

        public Owner {
            RequestValidation.requireNonNull(role, "owner role");
            if (ownershipPercentage != null && (ownershipPercentage < 0 || ownershipPercentage > 100)) {
                throw new IllegalArgumentException("ownershipPercentage must be between 0 and 100");
            }
        }
    }
}
