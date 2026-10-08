package ages.vstable.backend.dto.onboarding;

import ages.vstable.backend.entity.enums.ComplianceStatus;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Estado da verificação de KYC mais recente do representante autenticado.
 * Permite retomar o cadastro após login, sem criar uma conta nova. Os dados
 * pessoais extras (CPF, endereço...) continuam não persistidos: se faltarem,
 * o frontend precisa coletá-los de novo.
 */
@Value
@Builder
public class OnboardingStatusResponse {
    UUID kycVerificationId;
    UUID companyId;
    ComplianceStatus status;
    boolean documentSubmitted;
    boolean livenessSubmitted;
}
