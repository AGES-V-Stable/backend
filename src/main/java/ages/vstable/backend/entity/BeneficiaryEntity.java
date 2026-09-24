package ages.vstable.backend.entity;

import ages.vstable.backend.entity.enums.BankAccountType;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
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
@Table(name = "beneficiaries")
public class BeneficiaryEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @Column(name = "avenia_id")
    private UUID aveniaId;

    @Column(name = "avenia_wallet_id")
    private UUID aveniaWalletId;

    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;

    @Column(name = "internal_description", length = 255)
    private String internalDescription;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "receiving_method", columnDefinition = "receiving_method_enum", nullable = false)
    @Builder.Default
    private ReceivingMethod receivingMethod = ReceivingMethod.BANK_ACCOUNT;

    @Column(name = "pix_key", length = 255)
    private String pixKey;

    @Column(name = "identification_document", length = 20)
    private String identificationDocument;

    @Column(name = "account_holder_name", length = 255)
    private String accountHolderName;

    @Column(name = "bank_code", length = 10)
    private String bankCode;

    @Column(name = "branch_number", length = 20)
    private String branchNumber;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "account_type", columnDefinition = "bank_account_type_enum")
    private BankAccountType accountType;

    @Column(name = "country", length = 100)
    private String country;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "blockchain_network", columnDefinition = "blockchain_network_enum")
    private BlockchainNetwork blockchainNetwork;

    @Column(name = "wallet_address", length = 120)
    private String walletAddress;

    @Column(name = "wallet_memo", length = 100)
    private String walletMemo;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}