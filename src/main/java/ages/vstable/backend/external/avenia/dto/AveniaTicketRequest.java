package ages.vstable.backend.external.avenia.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AveniaTicketRequest {

    private final String quoteToken;
    private final String externalId;
    private final Integer customDuration;

    @JsonIgnore
    private final String subAccountId;

    private final BrlPixInput ticketBrlPixInput;
    private final BlockchainOutput ticketBlockchainOutput;
    private final BrlPixOutput ticketBrlPixOutput;
    private final UsdOutput ticketUsdOutput;
    private final UsdOutput ticketUsdWireOutput;
    private final EurSepaOutput ticketEurSepaOutput;
    private final SwiftOutput ticketSwiftOutput;

    public AveniaTicketRequest withQuoteTokenAndSubAccount(String token, String accountId) {
        return toBuilder()
                .quoteToken(token)
                .subAccountId(accountId)
                .build();
    }

    public record BrlPixInput(String additionalData) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlockchainOutput(
            UUID beneficiaryWalletId,
            String walletChain,
            String walletAddress,
            String walletMemo
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrlPixOutput(
            UUID beneficiaryBrlBankAccountId,
            String pixKey,
            String pixMessage,
            String userName,
            String bankCode,
            String branchCode,
            String accountNumber,
            String accountType,
            String taxId,
            String brCode
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsdOutput(
            UUID beneficiaryUsdBankAccountId,
            String achReference,
            String wireMessage
    ) {
    }

    public record EurSepaOutput(UUID beneficiaryEurBankAccountId, String sepaReference) {
    }

    public record SwiftOutput(UUID beneficiarySwiftBankAccountId, UUID uploadedDocumentId) {
    }
}
