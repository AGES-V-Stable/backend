package ages.vstable.backend.service;

record CompanyNormalizedData(
        String legalName,
        String cnpj,
        String country,
        String zipCode,
        String city,
        String state
) {
}
