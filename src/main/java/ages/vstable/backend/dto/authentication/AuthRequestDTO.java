package ages.vstable.backend.dto.authentication;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthRequestDTO {
    String email;
    String password;
}
