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

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "tipo_documento", nullable = false, columnDefinition = "tipo_documento_enum")
    private TipoDocumento tipoDocumento;

    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "url_arquivo", nullable = false)
    private String urlArquivo;

    @Column(name = "tamanho_arquivo_bytes")
    private Long tamanhoArquivoBytes;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "conteudo", columnDefinition = "bytea")
    private byte[] conteudo;

    @Column(name = "hash_sha256", length = 64)
    private String hashSha256;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "status_compliance_enum")
    private StatusCompliance status;

    @Column(name = "enviado_em")
    private OffsetDateTime enviadoEm;
}
