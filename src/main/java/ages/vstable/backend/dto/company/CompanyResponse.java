package ages.vstable.backend.dto.company;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class CompanyResponse {

    private UUID id;

    private String legalName;
    private String tradeName;
    private String cnpj;
    private String country;
    private String zipCode;
    private String city;
    private String state;


    private BigDecimal availableBalanceBrl;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
