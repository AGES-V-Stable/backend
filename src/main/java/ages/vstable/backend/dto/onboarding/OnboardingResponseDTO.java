package ages.vstable.backend.dto.onboarding;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class OnboardingResponseDTO {
    UUID userId;
    UUID companyId;
    UUID kycVerificationId;
    String accessToken;
}
