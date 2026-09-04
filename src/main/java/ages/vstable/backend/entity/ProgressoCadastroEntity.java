package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "progresso_cadastros")
public class ProgressoCadastroEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "empresa_id")
    private UUID empresaId;

    @Column(name = "email_contato", nullable = false, length = 255)
    private String emailContato;

    @Column(name = "etapa_atual")
    private Integer etapaAtual = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dados_temporarios", columnDefinition = "jsonb")
    private String dadosTemporarios = "{}";

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm;
}
