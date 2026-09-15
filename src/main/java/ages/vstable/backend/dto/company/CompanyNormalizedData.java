package ages.vstable.backend.dto.company;

public record CompanyNormalizedData(
        String razaoSocial,
        String cnpj,
        String pais,
        String cep,
        String cidade,
        String estado
) {
}
