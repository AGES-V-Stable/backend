package ages.vstable.backend.service;

import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.exception.ForbiddenException;

import java.util.UUID;

/** Every compliance step (document, liveness, KYC) only accepts the representative that owns the verification. */
final class KycOwnership {

    private KycOwnership() {
    }

    static void verify(AveniaKycVerificationEntity kyc, UUID currentUserId) {
        if (currentUserId == null || !currentUserId.equals(kyc.getUserId())) {
            throw new ForbiddenException("Esta verificação de KYC não pertence ao usuário autenticado");
        }
    }
}
