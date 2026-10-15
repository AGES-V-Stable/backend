package ages.vstable.backend.dto.blindpay;

import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Situação do onboarding da empresa na BlindPay. {@code enabled} só é verdadeiro com
 * KYB aprovado, AML {@code clear} e termos aceitos. {@code synchronizationFailed}
 * indica que a consulta à BlindPay falhou e os dados são os últimos persistidos.
 */
public record BlindPayComplianceStatusResponse(
        BlindPayOnboardingStatus status,
        boolean enabled,
        boolean termsAccepted,
        boolean submitted,
        boolean canSubmit,
        boolean canUpdate,
        String kycStatus,
        String amlStatus,
        List<String> kycWarningCodes,
        List<OwnerReference> owners,
        String lastErrorCode,
        OffsetDateTime synchronizedAt,
        boolean synchronizationFailed
) {

    public BlindPayComplianceStatusResponse {
        kycWarningCodes = kycWarningCodes == null ? List.of() : List.copyOf(kycWarningCodes);
        owners = owners == null ? List.of() : List.copyOf(owners);
    }

    /** Identificador de um sócio na BlindPay, exigido para atualizar o cadastro. */
    public record OwnerReference(String id, String role, String firstName, String lastName) {
    }
}
