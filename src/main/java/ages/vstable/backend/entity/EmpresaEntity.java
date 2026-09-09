package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.StatusCompliance;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "empresas")
public class EmpresaEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "razao_social", nullable = false, length = 255)
    private String razaoSocial;

    @Column(name = "nome_fantasia", length = 255)
    private String nomeFantasia;

    @Column(name = "cnpj", nullable = false, unique = true, length = 18)
    private String cnpj;

    @Column(name = "pais", nullable = false, length = 100)
    private String pais;

    @Column(name = "cep", nullable = false, length = 20)
    private String cep;

    @Column(name = "cidade", length = 255)
    private String cidade;

    @Column(name = "estado", nullable = false, length = 100)
    private String estado;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status_kyb", columnDefinition = "status_compliance_enum")
    private StatusCompliance statusKyb = StatusCompliance.PENDENTE;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status_aml", columnDefinition = "status_compliance_enum")
    private StatusCompliance statusAml = StatusCompliance.PENDENTE;

    @Column(name = "saldo_disponivel_brl", precision = 15, scale = 2)
    private BigDecimal saldoDisponivelBrl = BigDecimal.ZERO;

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm;
}
