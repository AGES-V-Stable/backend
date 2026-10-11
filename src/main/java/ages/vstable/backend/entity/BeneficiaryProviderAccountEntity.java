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
@Table(name = "beneficiary_provider_accounts", uniqueConstraints =
        @UniqueConstraint(name = "beneficiary_provider_accounts_beneficiary_provider_key",
                columnNames = {"beneficiary_id", "provider"}))
public class BeneficiaryProviderAccountEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "beneficiary_id", nullable = false)
    private BeneficiaryEntity beneficiary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IntegrationProvider provider;

    @Column(name = "external_bank_account_id")
    private String externalBankAccountId;

    @Column(name = "external_wallet_id")
    private String externalWalletId;

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
