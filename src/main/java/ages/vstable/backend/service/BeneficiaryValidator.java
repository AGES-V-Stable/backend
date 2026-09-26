package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import org.springframework.stereotype.Component;

@Component
class BeneficiaryValidator {

    void validate(BeneficiaryCreateRequest request) {
        if (request.getReceivingMethod() == ReceivingMethod.BANK_ACCOUNT) {
            requireNonBlank(request.getBankName(), "bankName");
            requireNonBlank(request.getSwiftBic(), "swiftBic");
            requireNonBlank(request.getAccountNumber(), "accountNumber");
            requireNonBlank(request.getCurrency(), "currency");
            requireNonBlank(request.getNickname(), "nickname");
        } else if (request.getReceivingMethod() == ReceivingMethod.CRYPTO_WALLET) {
            requireNonBlank(request.getWalletAddress(), "walletAddress");
            requireNonNull(request.getBlockchainNetwork(), "blockchainNetwork");
            requireNonBlank(request.getNickname(), "nickname");
        }
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + ": Campo obrigatório.");
        }
    }

    private void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + ": Campo obrigatório.");
        }
    }
}
