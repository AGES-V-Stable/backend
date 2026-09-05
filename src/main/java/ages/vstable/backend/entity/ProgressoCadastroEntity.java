package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.StatusOnboarding;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "progresso_cadastros")
@Getter
@Setter
public class ProgressoCadastroEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id")
    private EmpresaEntity empresa;

    @Column(name = "email_contato", nullable = false)
    private String emailContato;

    @Column(name = "etapa_atual")
    private Integer etapaAtual = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_geral")
    private StatusOnboarding statusGeral;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_compliance_final")
    private StatusCompliance statusComplianceFinal;

    @Column(name = "dados_temporarios", columnDefinition = "jsonb")
    private String dadosTemporarios;

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm;
}
