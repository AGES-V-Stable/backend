package ages.vstable.backend.external.avenia.dto;

import java.util.UUID;

public record AveniaBeneficiaryRegistration(UUID bankAccountId, UUID walletId) {

    public static AveniaBeneficiaryRegistration bankAccount(UUID id) {
        return new AveniaBeneficiaryRegistration(id, null);
    }

    public static AveniaBeneficiaryRegistration wallet(UUID id) {
        return new AveniaBeneficiaryRegistration(null, id);
    }
}
