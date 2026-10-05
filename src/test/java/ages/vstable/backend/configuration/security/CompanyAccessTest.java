package ages.vstable.backend.configuration.security;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

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
        assertThat(CompanyAccess.currentUserId(member)).isEqualTo(((UserEntity) member.getPrincipal()).getId());
    }
}
