package ages.vstable.backend.dto.company;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class CompanyResponse {

    private UUID id;

    private String razaoSocial;
    private String nomeFantasia;
    private String cnpj;
    private String pais;
    private String cep;
    private String cidade;
    private String estado;

    private ComplianceStatus statusKyb;
    private ComplianceStatus statusAml;

    private BigDecimal saldoDisponivelBrl;

    private OffsetDateTime criadoEm;
    private OffsetDateTime atualizadoEm;
}
