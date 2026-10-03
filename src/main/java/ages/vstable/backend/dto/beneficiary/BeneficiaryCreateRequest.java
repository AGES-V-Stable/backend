package ages.vstable.backend.dto.beneficiary;

import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BeneficiaryCreateRequest {

    @NotBlank
    private String beneficiaryType;

    @NotBlank
    private String legalName;

    @NotBlank
    private String identificationDocument;

    @NotBlank
    private String country;

    @NotBlank
    private String address;

    @NotNull
    private ReceivingMethod receivingMethod;

    private String nickname;
    private String internalDescription;

    // Required when receivingMethod = BANK_ACCOUNT (checked manually by BeneficiaryValidator)
    private String bankName;
    private String swiftBic;
    private String bankCode;
    private String branchNumber;
    private String accountNumber;
    private String currency;

    // Required when receivingMethod = CRYPTO_WALLET (checked manually by BeneficiaryValidator)
    private String walletAddress;
    private BlockchainNetwork blockchainNetwork;
    private String walletMemo;

    @AssertTrue(message = "Confirmação de revisão dos dados é obrigatória")
    private boolean confirmed;
}
