package ages.vstable.backend.service.transfer;

import ages.vstable.backend.dto.transfer.TransferExecutionResponse.BankTransfer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Instruções de pagamento por TED de um payin BlindPay. Usa apenas os campos documentados
 * (https://blindpay.com/docs/payins): {@code memo_code} e, em {@code blindpay_bank_details},
 * {@code routing_number}, {@code account_number}, {@code account_type}, {@code beneficiary} e
 * {@code receiving_bank} (nome e linhas de endereço). Campos ausentes ficam nulos; nenhum dado
 * bancário é presumido.
 */
final class BankTransferInstructions {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BankTransferInstructions() {
    }

    /** JSON gravado na execução, ou {@code null} quando a BlindPay não devolveu nenhum dado. */
    static String toJson(String memoCode, JsonNode bankDetails) {
        JsonNode details = bankDetails == null ? JSON.missingNode() : bankDetails;
        BankTransfer transfer = new BankTransfer(
                blankToNull(memoCode),
                text(details.path("beneficiary").path("name")),
                address(details.path("beneficiary")),
                text(details.path("receiving_bank").path("name")),
                address(details.path("receiving_bank")),
                text(details.path("routing_number")),
                text(details.path("account_number")),
                text(details.path("account_type")));
        boolean empty = Stream.of(transfer.memoCode(), transfer.beneficiaryName(), transfer.beneficiaryAddress(),
                transfer.bankName(), transfer.bankAddress(), transfer.routingNumber(), transfer.accountNumber(),
                transfer.accountType()).allMatch(Objects::isNull);
        return empty ? null : JSON.writeValueAsString(transfer);
    }

    /** Lê o JSON gravado; um conteúdo ilegível é tratado como ausente, nunca como dado parcial. */
    static BankTransfer fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, BankTransfer.class);
        } catch (JacksonException ex) {
            return null;
        }
    }

    private static String address(JsonNode party) {
        String joined = Stream.of(text(party.path("address_line_1")), text(party.path("address_line_2")))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isValueNode() || node.isNull() ? null : blankToNull(node.asString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
