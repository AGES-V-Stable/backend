package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.CompanyMembershipEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * Resource-level authorization shared by controllers. Route-level rules (authenticated, ADMIN)
 * stay in SecurityConfiguration / @PreAuthorize; this class answers "may this principal touch
 * this company?" from the company membership loaded for the current request, never from the
 * roles stored in the token. Without a membership the access is denied.
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

    /** The permission on that company, or an administrator (platform-level read access). */
    public static void requirePermissionOrAdmin(Authentication authentication, UUID companyId,
                                                CompanyPermission permission) {
        if (isAdmin(authentication)) {
            return;
        }
        requirePermission(authentication, companyId, permission);
    }

    /** Only an active member of that company whose role grants the permission. */
    public static void requirePermission(Authentication authentication, UUID companyId, CompanyPermission permission) {
        if (companyId == null) {
            throw denied();
        }
        grant(activeMembership(companyUser(authentication).orElse(null), companyId), permission);
    }

    /** Company of the principal's active membership, for routes that do not name a company. */
    public static UUID requireCompanyId(Authentication authentication, CompanyPermission permission) {
        return requireCompanyId(companyUser(authentication).orElse(null), permission);
    }

    /** Same as above for controllers that receive the principal directly; a null user is denied. */
    public static UUID requireCompanyId(UserEntity user, CompanyPermission permission) {
        CompanyMembershipEntity membership = activeMembership(user, null);
        grant(membership, permission);
        return membership.getCompanyId();
    }

    /** Only the owner of the user's own company, for routes that do not name a company. */
    public static void requireOwner(UserEntity user) {
        requireOwnerRole(activeMembership(user, null));
    }

    /** Only the owner of that company. */
    public static void requireOwner(UserEntity user, UUID companyId) {
        if (companyId == null) {
            throw denied();
        }
        requireOwnerRole(activeMembership(user, companyId));
    }

    private static void requireOwnerRole(CompanyMembershipEntity membership) {
        if (membership.getRole() != CompanyRole.OWNER) {
            throw new ForbiddenException("OWNER_REQUIRED", "Apenas o proprietário da empresa pode realizar esta ação.");
        }
    }

    private static ForbiddenException denied() {
        return new ForbiddenException("COMPANY_ACCESS_DENIED", "Você não tem acesso a esta empresa.");
    }

    private static void grant(CompanyMembershipEntity membership, CompanyPermission permission) {
        if (!membership.getRole().permissionsFor(membership.getStatus()).contains(permission)) {
            throw new ForbiddenException("PERMISSION_DENIED", "Seu perfil não permite esta ação.");
        }
    }

    /**
     * The user's active membership, optionally in the given company. A removed membership reports the
     * revoked access only for its own company; any other company is simply not authorized.
     */
    private static CompanyMembershipEntity activeMembership(UserEntity user, UUID companyId) {
        CompanyMembershipEntity membership = user == null ? null : user.getMembership();
        if (membership == null || (companyId != null && !companyId.equals(membership.getCompanyId()))) {
            throw denied();
        }
        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new ForbiddenException("COMPANY_ACCESS_REVOKED", "Seu acesso a esta empresa foi removido.");
        }
        return membership;
    }
}
