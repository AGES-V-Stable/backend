package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * Resource-level authorization shared by controllers. Route-level rules (authenticated, ADMIN)
 * stay in SecurityConfiguration / @PreAuthorize; this class answers "may this principal touch
 * this company?".
 */
public final class CompanyAccess {

    private CompanyAccess() {
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + JwtTokenUtils.ACCOUNT_TYPE_ADMIN).equals(authority.getAuthority()));
    }

    public static Optional<UserEntity> companyUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserEntity user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static UUID currentUserId(Authentication authentication) {
        return companyUser(authentication).map(UserEntity::getId).orElse(null);
    }

    /** Company user of that company, or an administrator. */
    public static void assertCanRead(Authentication authentication, UUID companyId) {
        if (isAdmin(authentication) || belongsTo(authentication, companyId)) {
            return;
        }
        throw new ForbiddenException("Access to this company is not allowed");
    }

    /** Only a user of that company (operations performed on the company's behalf). */
    public static void assertMember(Authentication authentication, UUID companyId) {
        if (!belongsTo(authentication, companyId)) {
            throw new ForbiddenException("Access to this company is not allowed");
        }
    }

    private static boolean belongsTo(Authentication authentication, UUID companyId) {
        return companyUser(authentication)
                .map(user -> companyId != null && companyId.equals(user.getCompanyId()))
                .orElse(false);
    }
}
