package ages.vstable.backend.dto.blindpay;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** KYC Standard only. The authenticated user's e-mail comes from their session. */
public record BlindPayIndividualKycRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull @Past LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = "\\d{11}") String taxId,
        @NotBlank @Pattern(regexp = "\\+55[1-9]\\d{9,10}") String phoneNumber,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String stateProvinceRegion,
        @NotBlank @Pattern(regexp = "BR") String country,
        @NotBlank @Pattern(regexp = "\\d{8}") String postalCode,
        @NotBlank @Pattern(regexp = "BR") String idDocCountry,
        @NotNull BlindPayApi.Customer.IdDocType idDocType,
        @Pattern(regexp = "UTILITY_BILL|BANK_STATEMENT|RENTAL_AGREEMENT|TAX_DOCUMENT|GOVERNMENT_CORRESPONDENCE")
        String proofOfAddressDocType
) {
}
