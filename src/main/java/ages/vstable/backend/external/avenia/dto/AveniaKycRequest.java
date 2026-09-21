package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

@Data
public class AveniaKycRequest {
    private String fullName;
    private String dateOfBirth;
    private String countryOfTaxId;
    private String taxIdNumber;
    private String email;
    private String phone;
    private String country;
    private String state;
    private String city;
    private String zipCode;
    private String streetAddress;
    private String uploadedDocumentId;
    private String uploadedSelfieId;
}
