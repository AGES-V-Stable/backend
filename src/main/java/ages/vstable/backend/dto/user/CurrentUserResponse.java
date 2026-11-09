package ages.vstable.backend.dto.user;

import ages.vstable.backend.dto.membership.CompanyAccessResponse;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CurrentUserResponse {

    private UUID id;
    private String name;
    private String email;
    /** Null for administrators, who do not belong to a company, and for users whose company link was removed. */
    private UUID companyId;
    /** Null for administrators and for users that never had a company link. */
    private CompanyAccessResponse companyAccess;
    /** ADMIN or USER. */
    private Boolean twoFactorEnabled; 
    private String accountType;
    private List<String> roles;
}
