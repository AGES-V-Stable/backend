package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;
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
@Table(name = "documentos_compliance")
public class DocumentoComplianceEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private EmpresaEntity empresa;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_documento", columnDefinition = "tipo_documento_enum", nullable = false)
    private TipoDocumento tipoDocumento;

    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "url_arquivo", nullable = false, columnDefinition = "text")
    private String urlArquivo;

    @Column(name = "tamanho_arquivo_bytes")
    private Long tamanhoArquivoBytes;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "status_compliance_enum", nullable = false)
    @Builder.Default
    private StatusCompliance status = StatusCompliance.EM_ANALISE;

    @Column(name = "enviado_em")
    private OffsetDateTime enviadoEm;
}
