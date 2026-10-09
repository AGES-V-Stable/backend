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

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "synchronized_at")
    private OffsetDateTime synchronizedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
