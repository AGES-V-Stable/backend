package ages.vstable.backend.controller;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UserControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new UserController()).build();
    }

    private static UserEntity companyUser() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setFullName("Usuario Teste");
        user.setEmail("usuario@teste.com");
        user.setCompanyId(UUID.randomUUID());
        return user;
    }

    private static Authentication authenticated(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    @Test
    void me_returnsCurrentUser_whenAuthenticated() throws Exception {
        UserEntity user = companyUser();
        user.setTwoFactorEnabled(true);

        mockMvc.perform(get("/v1/users/me").principal(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.name").value("Usuario Teste"))
                .andExpect(jsonPath("$.email").value("usuario@teste.com"))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true))
                .andExpect(jsonPath("$.companyId").value(user.getCompanyId().toString()))
                .andExpect(jsonPath("$.companyAccess").doesNotExist())
                .andExpect(jsonPath("$.accountType").value("USER"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    void me_userWithActiveOwnerMembership_returnsCompanyAccessAndCompanyOfTheMembership() throws Exception {
        // Arrange: a conta guarda outra empresa; vale a do vínculo.
        UUID membershipCompanyId = UUID.randomUUID();
        UserEntity user = TestCompanyUsers.withMembership(
                companyUser(), membershipCompanyId, CompanyRole.OWNER, MembershipStatus.ACTIVE);

        // Act / Assert
        mockMvc.perform(get("/v1/users/me").principal(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(membershipCompanyId.toString()))
                .andExpect(jsonPath("$.companyAccess.membershipId").value(user.getMembership().getId().toString()))
                .andExpect(jsonPath("$.companyAccess.companyId").value(membershipCompanyId.toString()))
                .andExpect(jsonPath("$.companyAccess.role").value("OWNER"))
                .andExpect(jsonPath("$.companyAccess.status").value("ACTIVE"))
                .andExpect(jsonPath("$.companyAccess.permissions.length()").value(9))
                .andExpect(jsonPath("$.companyAccess.permissions[0]").value("COMPANY_READ"))
                .andExpect(jsonPath("$.companyAccess.permissions[8]").value("MEMBERS_MANAGE"))
                .andExpect(jsonPath("$.companyAccess.version").value(1))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    void me_userWithRemovedMembership_returnsNoCompanyAndOnlySelfMembershipRead() throws Exception {
        // Arrange
        UserEntity user = companyUser();
        TestCompanyUsers.withMembership(user, user.getCompanyId(), CompanyRole.OPERATOR,
                MembershipStatus.REMOVED);

        // Act / Assert
        mockMvc.perform(get("/v1/users/me").principal(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.companyAccess.status").value("REMOVED"))
                .andExpect(jsonPath("$.companyAccess.permissions.length()").value(1))
                .andExpect(jsonPath("$.companyAccess.permissions[0]").value("SELF_MEMBERSHIP_READ"));
    }

    @Test
    void me_response_neverExposesSecrets() throws Exception {
        // Arrange
        UserEntity user = TestCompanyUsers.withMembership(companyUser(), UUID.randomUUID(), CompanyRole.VIEWER,
                MembershipStatus.ACTIVE);
        user.setPasswordHash("hash-secreto");
        user.setPasswordSalt("salt-secreto");
        user.setTwoFactorSecret("segredo-2fa");

        // Act / Assert
        mockMvc.perform(get("/v1/users/me").principal(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("hash-secreto"))))
                .andExpect(content().string(not(containsString("salt-secreto"))))
                .andExpect(content().string(not(containsString("segredo-2fa"))))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void me_returnsAdministratorWithoutCompany() throws Exception {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Admin V-Stable")
                .email("admin@vstable.com")
                .twoFactorEnabled(true)
                .build();

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());

        mockMvc.perform(get("/v1/users/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.name").value("Admin V-Stable"))
                .andExpect(jsonPath("$.email").value("admin@vstable.com"))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true))
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.companyAccess").doesNotExist())
                .andExpect(jsonPath("$.accountType").value("ADMIN"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"));
    }

    @Test
    void me_withUnsupportedPrincipalType_returns401() throws Exception {
        Authentication unknownPrincipalAuth =
                new UsernamePasswordAuthenticationToken("anonymousUser", null, java.util.List.of());

        mockMvc.perform(get("/v1/users/me").principal(unknownPrincipalAuth))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
