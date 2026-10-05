package ages.vstable.backend.controller;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UserControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new UserController()).build();
    }

    @Test
    void me_returnsCurrentUser_whenAuthenticated() throws Exception {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setFullName("Usuario Teste");
        user.setEmail("usuario@teste.com");
        user.setCompanyId(UUID.randomUUID());

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());

        mockMvc.perform(get("/v1/users/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.name").value("Usuario Teste"))
                .andExpect(jsonPath("$.email").value("usuario@teste.com"))
                .andExpect(jsonPath("$.companyId").value(user.getCompanyId().toString()))
                .andExpect(jsonPath("$.accountType").value("USER"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    void me_returnsAdministratorWithoutCompany() throws Exception {
        AdministratorEntity admin = AdministratorEntity.builder()
                .id(UUID.randomUUID())
                .fullName("Admin V-Stable")
                .email("admin@vstable.com")
                .build();

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());

        mockMvc.perform(get("/v1/users/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Admin V-Stable"))
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.accountType").value("ADMIN"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"));
    }

    @Test
    void me_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
