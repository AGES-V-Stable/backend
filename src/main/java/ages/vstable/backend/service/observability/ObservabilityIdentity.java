package ages.vstable.backend.service.observability;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import java.util.UUID;

public final class ObservabilityIdentity {
    private ObservabilityIdentity() { }
    public static UUID id(Object principal) {
        if (principal instanceof UserEntity user) return user.getId();
        if (principal instanceof AdministratorEntity administrator) return administrator.getId();
        return null;
    }
}
