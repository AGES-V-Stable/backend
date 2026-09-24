package ages.vstable.backend.dto.beneficiary;

import ages.vstable.backend.entity.enums.BankAccountType;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class BeneficiaryResponse {
    private UUID id;
    private UUID companyId;
    private String nickname;
    private String internalDescription;
    private ReceivingMethod receivingMethod;
    private String pixKey;
    private String identificationDocument;
    private String accountHolderName;
    private String bankCode;
    private String branchNumber;
    private String accountNumber;
    private BankAccountType accountType;
    private String country;
    private BlockchainNetwork blockchainNetwork;
    private String walletAddress;
    private String walletMemo;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

}
