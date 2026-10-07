package ages.vstable.backend.entity;

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
@Table(name = "provider_wallets", uniqueConstraints =
        @UniqueConstraint(name = "provider_wallets_account_network_key",
                columnNames = {"provider_account_id", "network"}))
public class ProviderWalletEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_account_id", nullable = false)
    private ProviderAccountEntity providerAccount;

    @Column(nullable = false, length = 50)
    private String network;

    @Column(name = "external_wallet_id", length = 255)
    private String externalWalletId;

    @Column(length = 50, nullable = false)
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
