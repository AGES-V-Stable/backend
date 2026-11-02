package ages.vstable.backend.dto.authentication;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponseDTO {
    Boolean requires2fa;
    String challengeToken;
    String otpauthUri;
    String token;
    String code;
    String message;
}
