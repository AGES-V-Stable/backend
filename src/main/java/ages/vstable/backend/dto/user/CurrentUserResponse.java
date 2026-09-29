package ages.vstable.backend.dto.user;

import lombok.Data;

import java.util.UUID;

@Data
public class CurrentUserResponse {

    private UUID id;
    private String name;
    private String email;
    private UUID companyId;
}
