package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Individual identity verification, separate from the company's BlindPay provider account. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "blindpay_individual_kyc")
public class BlindPayIndividualKycEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "customer_id", length = 50, unique = true)
    private String customerId;

    @Column(name = "tos_id", length = 50)
    private String tosId;

    @Column(name = "tos_session_key", unique = true)
    private UUID tosSessionKey;

    @Column(name = "tos_accepted_at")
    private OffsetDateTime tosAcceptedAt;

    @Column(name = "kyc_status", length = 50)
    private String kycStatus;

    @Column(name = "aml_status", length = 30)
    private String amlStatus;

    @Column(name = "provider_updated_at")
    private OffsetDateTime providerUpdatedAt;

    /** Cliente recusado substituído por um novo cadastro; seus webhooks são ignorados. */
    @Column(name = "superseded_customer_id", length = 50)
    private String supersededCustomerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
