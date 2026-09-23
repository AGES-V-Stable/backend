package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.ComplianceStatus;
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
@Table(name = "avenia_kyc_verifications")
public class AveniaKycVerificationEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "avenia_process_id")
    private String aveniaProcessId;

    @Column(name = "document_id")
    private String documentId;

    @Column(name = "liveness_id")
    private String livenessId;

    // Subconta INDIVIDUAL na Avenia dedicada a esta verificação. Sem isso, todas as
    // chamadas usam a conta principal da API key — e, assim que ela for aprovada uma
    // vez, a Avenia rejeita qualquer submissão nova com "user already approved in
    // level 1", mesmo para representantes diferentes. Ver AveniaSubAccountProvisioningService.
    @Column(name = "avenia_sub_account_id")
    private String aveniaSubAccountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "compliance_status_enum")
    private ComplianceStatus status = ComplianceStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb", nullable = false)
    private String responsePayload = "{}";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
