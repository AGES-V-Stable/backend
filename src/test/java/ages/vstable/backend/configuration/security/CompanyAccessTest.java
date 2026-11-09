package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyAccessTest {

    private final UUID companyId = UUID.randomUUID();

    private Authentication authenticated(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private Authentication memberOf(UUID company, CompanyRole role) {
        return authenticated(TestCompanyUsers.member(company, role));
    }

    private Authentication admin() {
        AdministratorEntity admin = AdministratorEntity.builder().id(UUID.randomUUID()).build();
        return new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
    }

    /** Usuário com companyId na conta mas sem nenhum vínculo carregado. */
    private UserEntity userWithoutMembership() {
        return UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();
    }

    private static void assertForbiddenWith(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ForbiddenException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void isAdmin_acceptsAdminAuthority() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(CompanyAccess.isAdmin(authentication)).isTrue();
    }

    @Test
    void isAdmin_rejectsAccountTypeAndNonAdminAuthorities() {
        Authentication accountType = new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ADMIN")));

        assertThat(CompanyAccess.isAdmin(accountType)).isFalse();
        assertThat(CompanyAccess.isAdmin(memberOf(companyId, CompanyRole.OWNER))).isFalse();
        assertThat(CompanyAccess.isAdmin(new UsernamePasswordAuthenticationToken("user", null, List.of())))
                .isFalse();
        assertThat(CompanyAccess.isAdmin(null)).isFalse();
    }

    @Test
    void currentUserId_isNullForAdministratorsAndAnonymous() {
        assertThat(CompanyAccess.currentUserId(admin())).isNull();
        assertThat(CompanyAccess.currentUserId(null)).isNull();
        Authentication member = memberOf(companyId, CompanyRole.OPERATOR);
        assertThat(CompanyAccess.currentUserId(member)).isEqualTo(((UserEntity) Objects.requireNonNull(member.getPrincipal())).getId());
    }

    @Test
    void companyUser_returnsTheCompanyPrincipal() {
        Authentication member = memberOf(companyId, CompanyRole.VIEWER);

        assertThat(CompanyAccess.companyUser(member)).contains((UserEntity) member.getPrincipal());
    }

    @Test
    void companyUser_rejectsAdministratorsAndNonCompanyPrincipals() {
        Authentication otherPrincipal = new UsernamePasswordAuthenticationToken("user", null, List.of());

        assertThat(CompanyAccess.companyUser(admin())).isEmpty();
        assertThat(CompanyAccess.companyUser(otherPrincipal)).isEmpty();
        assertThat(CompanyAccess.companyUser(null)).isEmpty();
        assertThat(CompanyAccess.currentUserId(otherPrincipal)).isNull();
    }

    @ParameterizedTest
    @EnumSource(CompanyRole.class)
    void requirePermission_activeMember_followsTheMatrixOfTheRole(CompanyRole role) {
        Authentication member = memberOf(companyId, role);
        List<CompanyPermission> granted = role.permissionsFor(MembershipStatus.ACTIVE);

        for (CompanyPermission permission : CompanyPermission.values()) {
            if (granted.contains(permission)) {
                assertThatCode(() -> CompanyAccess.requirePermission(member, companyId, permission))
                        .as("%s deve receber %s", role, permission).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> CompanyAccess.requirePermission(member, companyId, permission))
                        .as("%s não deve receber %s", role, permission)
                        .isInstanceOfSatisfying(ForbiddenException.class,
                                ex -> assertThat(ex.getCode()).isEqualTo("PERMISSION_DENIED"));
            }
        }
    }

    @Test
    void requirePermission_otherCompany_isDenied() {
        Authentication member = memberOf(UUID.randomUUID(), CompanyRole.OWNER);

        assertForbiddenWith(() -> CompanyAccess.requirePermission(member, companyId, CompanyPermission.COMPANY_READ),
                "COMPANY_ACCESS_DENIED");
    }

    @Test
    void requirePermission_removedMemberOfThatCompany_reportsRevokedAccess() {
        Authentication removed = authenticated(TestCompanyUsers.removedMember(companyId, CompanyRole.OPERATOR));

        assertForbiddenWith(() -> CompanyAccess.requirePermission(removed, companyId, CompanyPermission.COMPANY_READ),
                "COMPANY_ACCESS_REVOKED");
    }

    @Test
    void requirePermission_removedMemberAskingForAnotherCompany_isSimplyDenied() {
        Authentication removed = authenticated(TestCompanyUsers.removedMember(companyId, CompanyRole.OPERATOR));

        assertForbiddenWith(() -> CompanyAccess.requirePermission(
                removed, UUID.randomUUID(), CompanyPermission.COMPANY_READ), "COMPANY_ACCESS_DENIED");
    }

    @Test
    void requirePermission_userWithoutMembership_isDeniedEvenWithACompanyOnTheAccount() {
        Authentication member = authenticated(userWithoutMembership());

        assertForbiddenWith(() -> CompanyAccess.requirePermission(member, companyId, CompanyPermission.COMPANY_READ),
                "COMPANY_ACCESS_DENIED");
    }

    @Test
    void requirePermission_administratorsAnonymousAndMissingCompany_areDenied() {
        Authentication owner = memberOf(companyId, CompanyRole.OWNER);

        assertForbiddenWith(() -> CompanyAccess.requirePermission(admin(), companyId, CompanyPermission.COMPANY_READ),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requirePermission(null, companyId, CompanyPermission.COMPANY_READ),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requirePermission(owner, null, CompanyPermission.COMPANY_READ),
                "COMPANY_ACCESS_DENIED");
    }

    @Test
    void requirePermissionOrAdmin_allowsAdministratorsButStillAppliesTheMatrixToMembers() {
        assertThatCode(() -> CompanyAccess.requirePermissionOrAdmin(admin(), companyId, CompanyPermission.COMPANY_READ))
                .doesNotThrowAnyException();
        assertThatCode(() -> CompanyAccess.requirePermissionOrAdmin(
                memberOf(companyId, CompanyRole.VIEWER), companyId, CompanyPermission.BENEFICIARY_READ))
                .doesNotThrowAnyException();
        assertForbiddenWith(() -> CompanyAccess.requirePermissionOrAdmin(
                memberOf(UUID.randomUUID(), CompanyRole.OWNER), companyId, CompanyPermission.BENEFICIARY_READ),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requirePermissionOrAdmin(
                authenticated(TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER)), companyId,
                CompanyPermission.BENEFICIARY_READ), "COMPANY_ACCESS_REVOKED");
    }

    @Test
    void requireCompanyId_activeMember_returnsTheCompanyOfTheMembership() {
        UserEntity operator = TestCompanyUsers.member(companyId, CompanyRole.OPERATOR);
        // A conta guarda outra empresa: só vale o vínculo.
        operator.setCompanyId(UUID.randomUUID());

        assertThat(CompanyAccess.requireCompanyId(operator, CompanyPermission.TRANSFER_CREATE)).isEqualTo(companyId);
        assertThat(CompanyAccess.requireCompanyId(authenticated(operator), CompanyPermission.BALANCE_READ))
                .isEqualTo(companyId);
    }

    @Test
    void requireCompanyId_viewerCannotCreateTransfers() {
        UserEntity viewer = TestCompanyUsers.member(companyId, CompanyRole.VIEWER);

        assertForbiddenWith(() -> CompanyAccess.requireCompanyId(viewer, CompanyPermission.TRANSFER_CREATE),
                "PERMISSION_DENIED");
        assertThat(CompanyAccess.requireCompanyId(viewer, CompanyPermission.TRANSFER_READ)).isEqualTo(companyId);
    }

    @Test
    void requireCompanyId_removedMemberNoMembershipAdministratorAndAnonymous_areRefused() {
        assertForbiddenWith(() -> CompanyAccess.requireCompanyId(
                TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER), CompanyPermission.BALANCE_READ),
                "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> CompanyAccess.requireCompanyId(userWithoutMembership(),
                CompanyPermission.BALANCE_READ), "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requireCompanyId(admin(), CompanyPermission.BALANCE_READ),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requireCompanyId((UserEntity) null, CompanyPermission.BALANCE_READ),
                "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requireCompanyId((Authentication) null, CompanyPermission.BALANCE_READ),
                "COMPANY_ACCESS_DENIED");
    }

    @Test
    void requireOwner_onlyTheOwnerOfThatCompanyPasses() {
        UserEntity owner = TestCompanyUsers.member(companyId, CompanyRole.OWNER);

        assertThatCode(() -> CompanyAccess.requireOwner(owner)).doesNotThrowAnyException();
        assertThatCode(() -> CompanyAccess.requireOwner(owner, companyId)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"OPERATOR", "VIEWER"})
    void requireOwner_otherRoles_areToldThatTheOwnerIsRequired(CompanyRole role) {
        UserEntity member = TestCompanyUsers.member(companyId, role);

        assertForbiddenWith(() -> CompanyAccess.requireOwner(member), "OWNER_REQUIRED");
        assertForbiddenWith(() -> CompanyAccess.requireOwner(member, companyId), "OWNER_REQUIRED");
    }

    @Test
    void requireOwner_removedOwnerOtherCompanyAndMissingData_areRefusedWithTheMatchingCode() {
        assertForbiddenWith(() -> CompanyAccess.requireOwner(
                TestCompanyUsers.removedMember(companyId, CompanyRole.OWNER), companyId), "COMPANY_ACCESS_REVOKED");
        assertForbiddenWith(() -> CompanyAccess.requireOwner(
                TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER), companyId), "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requireOwner(userWithoutMembership()), "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requireOwner(null), "COMPANY_ACCESS_DENIED");
        assertForbiddenWith(() -> CompanyAccess.requireOwner(
                TestCompanyUsers.member(companyId, CompanyRole.OWNER), null), "COMPANY_ACCESS_DENIED");
    }

    @Test
    void rejectRevokedMembership_onlyRefusesARemovedMembership() {
        assertForbiddenWith(() -> CompanyAccess.rejectRevokedMembership(
                TestCompanyUsers.removedMember(companyId, CompanyRole.OPERATOR)), "COMPANY_ACCESS_REVOKED");
        assertThatCode(() -> CompanyAccess.rejectRevokedMembership(
                TestCompanyUsers.member(companyId, CompanyRole.VIEWER))).doesNotThrowAnyException();
        assertThatCode(() -> CompanyAccess.rejectRevokedMembership(userWithoutMembership()))
                .doesNotThrowAnyException();
        assertThatCode(() -> CompanyAccess.rejectRevokedMembership(null)).doesNotThrowAnyException();
    }
}
