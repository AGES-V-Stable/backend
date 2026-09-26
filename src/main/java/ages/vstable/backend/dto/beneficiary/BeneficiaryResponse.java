package ages.vstable.backend.dto.beneficiary;

import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class BeneficiaryResponse {

    private UUID id;
    private UUID companyId;

    private String beneficiaryType;
    private String legalName;
    private String identificationDocument;
    private String country;
    private String address;
    private String nickname;
    private String internalDescription;

    private ReceivingMethod receivingMethod;

    private String bankName;
    private String swiftBic;
    private String bankCode;
    private String branchNumber;
    private String accountNumber;
    private String currency;

    private String walletAddress;
    private BlockchainNetwork blockchainNetwork;
    private String walletMemo;

    private OffsetDateTime createdAt;
}
