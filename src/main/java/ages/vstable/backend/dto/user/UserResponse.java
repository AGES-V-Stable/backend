package ages.vstable.backend.dto.user;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class UserResponse {

    private UUID id;
    private UUID companyId;
    private String fullName;
    private String email;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
