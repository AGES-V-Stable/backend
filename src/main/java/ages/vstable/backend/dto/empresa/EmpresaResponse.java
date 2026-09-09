package ages.vstable.backend.dto.empresa;

import ages.vstable.backend.entity.enums.StatusCompliance;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class EmpresaResponse {

    private UUID id;

    private String razaoSocial;
    private String nomeFantasia;
    private String cnpj;
    private String pais;
    private String cep;
    private String cidade;
    private String estado;

    private StatusCompliance statusKyb;
    private StatusCompliance statusAml;

    private BigDecimal saldoDisponivelBrl;

    private OffsetDateTime criadoEm;
    private OffsetDateTime atualizadoEm;
}
