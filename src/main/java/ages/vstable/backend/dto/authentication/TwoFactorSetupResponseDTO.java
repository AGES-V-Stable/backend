package ages.vstable.backend.dto.authentication;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TwoFactorSetupResponseDTO {
    String otpauthUri;
    boolean twoFactorEnabled;
}