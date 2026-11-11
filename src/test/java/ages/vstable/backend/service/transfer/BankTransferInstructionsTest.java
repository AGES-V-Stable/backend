package ages.vstable.backend.service.transfer;

import ages.vstable.backend.dto.transfer.TransferExecutionResponse.BankTransfer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BankTransferInstructionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void toJson_documentedFields_areKeptAndAddressLinesJoined() {
        // Arrange
        JsonNode details = JSON.readTree("""
                {"routing_number":"021000089","account_number":"31254097","account_type":"Business checking",
                 "beneficiary":{"name":"BlindPay, Inc.","address_line_1":"8 The Green","address_line_2":"Suite 1"},
                 "receiving_bank":{"name":"Citibank","address_line_1":"399 Park Ave"},
                 "unknown_field":"ignored"}
                """);

        // Act
        BankTransfer transfer = BankTransferInstructions.fromJson(BankTransferInstructions.toJson(" MEMO ", details));

        // Assert
        assertThat(transfer).isEqualTo(new BankTransfer("MEMO", "BlindPay, Inc.", "8 The Green, Suite 1",
                "Citibank", "399 Park Ave", "021000089", "31254097", "Business checking"));
    }

    @Test
    void toJson_onlyMemoCode_keepsMemoAndLeavesBankFieldsEmpty() {
        BankTransfer transfer = BankTransferInstructions.fromJson(BankTransferInstructions.toJson("MEMO", null));

        assertThat(transfer).isEqualTo(new BankTransfer("MEMO", null, null, null, null, null, null, null));
    }

    @Test
    void toJson_nothingUsable_returnsNull() {
        JsonNode details = JSON.readTree("""
                {"routing_number":"  ","beneficiary":{"name":null},"receiving_bank":{"name":{"nested":"object"}}}
                """);

        assertThat(BankTransferInstructions.toJson("  ", details)).isNull();
    }

    @Test
    void fromJson_missingOrUnreadableContent_isTreatedAsAbsent() {
        assertThat(BankTransferInstructions.fromJson(null)).isNull();
        assertThat(BankTransferInstructions.fromJson(" ")).isNull();
        assertThat(BankTransferInstructions.fromJson("not json")).isNull();
    }
}
