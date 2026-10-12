package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
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

    private Authentication userOf(UUID company) {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(company).build();
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private Authentication admin() {
        AdministratorEntity admin = AdministratorEntity.builder().id(UUID.randomUUID()).build();
        return new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
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
        assertThat(CompanyAccess.isAdmin(userOf(companyId))).isFalse();
        assertThat(CompanyAccess.isAdmin(new UsernamePasswordAuthenticationToken("user", null, List.of())))
                .isFalse();
        assertThat(CompanyAccess.isAdmin(null)).isFalse();
    }

    @Test
    void assertCanRead_allowsMembersAndAdmins() {
        assertThatCode(() -> CompanyAccess.assertCanRead(userOf(companyId), companyId)).doesNotThrowAnyException();
        assertThatCode(() -> CompanyAccess.assertCanRead(admin(), companyId)).doesNotThrowAnyException();
    }

    @Test
    void assertCanRead_rejectsOtherCompaniesAndAnonymous() {
        assertThatThrownBy(() -> CompanyAccess.assertCanRead(userOf(UUID.randomUUID()), companyId))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> CompanyAccess.assertCanRead(null, companyId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assertMember_rejectsAdministrators() {
        assertThatCode(() -> CompanyAccess.assertMember(userOf(companyId), companyId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CompanyAccess.assertMember(admin(), companyId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void currentUserId_isNullForAdministratorsAndAnonymous() {
        assertThat(CompanyAccess.currentUserId(admin())).isNull();
        assertThat(CompanyAccess.currentUserId(null)).isNull();
        Authentication member = userOf(companyId);
        assertThat(CompanyAccess.currentUserId(member)).isEqualTo(((UserEntity) Objects.requireNonNull(member.getPrincipal())).getId());
    }

    @Test
    void companyUser_returnsTheCompanyPrincipal() {
        Authentication member = userOf(companyId);

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

    @Test
    void assertCanRead_rejectsNullCompanyIdForMembers() {
        Authentication member = userOf(companyId);

        assertThatThrownBy(() -> CompanyAccess.assertCanRead(member, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assertCanRead_rejectsUsersWithoutACompany() {
        Authentication member = userOf(null);

        assertThatThrownBy(() -> CompanyAccess.assertCanRead(member, companyId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assertCanRead_rejectsNonCompanyPrincipalsWithoutAdminAuthority() {
        Authentication otherPrincipal = new UsernamePasswordAuthenticationToken("user", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThatThrownBy(() -> CompanyAccess.assertCanRead(otherPrincipal, companyId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assertMember_rejectsUsersOfAnotherCompany() {
        Authentication member = userOf(UUID.randomUUID());

        assertThatThrownBy(() -> CompanyAccess.assertMember(member, companyId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assertMember_rejectsMissingCompanyMembership() {
        Authentication member = userOf(null);

        assertThatThrownBy(() -> CompanyAccess.assertMember(member, companyId))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> CompanyAccess.assertMember(member, null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> CompanyAccess.assertMember(null, companyId))
                .isInstanceOf(ForbiddenException.class);
    }
}
