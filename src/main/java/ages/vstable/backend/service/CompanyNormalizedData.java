package ages.vstable.backend.service;

record CompanyNormalizedData(
        String razaoSocial,
        String cnpj,
        String pais,
        String cep,
        String cidade,
        String estado
) {
}
