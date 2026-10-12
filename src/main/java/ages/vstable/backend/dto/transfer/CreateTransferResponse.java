package ages.vstable.backend.dto.transfer;

import ages.vstable.backend.entity.enums.TransactionStatus;

public record CreateTransferResponse(TransactionStatus status) {
}
