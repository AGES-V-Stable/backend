package ages.vstable.backend.dto.onboarding;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class OnboardingResponseDTO {
    UUID usuarioId;
    UUID empresaId;
    UUID verificacaoKycId;
}
