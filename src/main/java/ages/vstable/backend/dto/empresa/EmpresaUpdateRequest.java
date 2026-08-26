package ages.vstable.backend.dto.empresa;

import lombok.Data;

@Data
public class EmpresaUpdateRequest {

    private String razaoSocial;
    private String nomeFantasia;
    private String cnpj;
}