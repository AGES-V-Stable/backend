package ages.vstable.backend.dto.company;

public record CompanyNormalizedData(
        String legalName,
        String cnpj,
        String country,
        String zipCode,
        String city,
        String state
) {
}
