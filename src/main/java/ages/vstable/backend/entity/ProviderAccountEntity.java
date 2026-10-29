package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "provider_accounts", uniqueConstraints =
        @UniqueConstraint(name = "provider_accounts_company_provider_key", columnNames = {"company_id", "provider"}))
public class ProviderAccountEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IntegrationProvider provider;

    @Column(name = "external_customer_id")
    private String externalCustomerId;

    @Column(nullable = false, length = 50)
    private String status;

    /** kyc_status bruto do provedor (ex.: verifying, approved, compliance_request). */
    @Column(name = "kyc_status", length = 50)
    private String kycStatus;

    /** aml_status bruto do provedor (clear, hit, error); nulo enquanto não houve triagem. */
    @Column(name = "aml_status", length = 20)
    private String amlStatus;

    @Column(name = "tos_id", length = 15)
    private String tosId;

    @Column(name = "tos_accepted_at")
    private OffsetDateTime tosAcceptedAt;

    /** updated_at do cliente no provedor; descarta webhooks fora de ordem. */
    @Column(name = "provider_updated_at")
    private OffsetDateTime providerUpdatedAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "synchronized_at")
    private OffsetDateTime synchronizedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
