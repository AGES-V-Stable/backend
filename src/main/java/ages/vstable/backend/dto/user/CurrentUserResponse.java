package ages.vstable.backend.dto.user;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CurrentUserResponse {

    private UUID id;
    private String name;
    private String email;
    /** Null for administrators, who do not belong to a company. */
    private UUID companyId;
    /** ADMIN or USER. */
    private String accountType;
    private List<String> roles;
}
