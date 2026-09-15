package ages.vstable.backend.dto.representante;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class UserResponse {

    private UUID id;
    private String nomeCompleto;
    private String email;
}
