package ages.vstable.backend.dto.empresa;

import lombok.Data;

@Data
public class EmpresaCreateRequest {

    private String razaoSocial;
    private String nomeFantasia;
    private String cnpj;
}