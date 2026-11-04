package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.InternalJobSecurityConfiguration;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.CustomAuthenticationProvider;
import ages.vstable.backend.configuration.security.BeanManager;
import ages.vstable.backend.service.InitialAdministratorSeeder;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SeedJobController.class)
@Import({InternalJobSecurityConfiguration.class, SecurityConfiguration.class,
        AuthenticationConfigurer.class, CustomAuthenticationProvider.class,
        BeanManager.class, JwtTokenUtils.class})
@ActiveProfiles("seed")
class InternalJobSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InitialAdministratorSeeder seeder;

    @MockitoBean
    private UserService userService;

    @Test
    void seedEvent_isAllowedAndOtherRoutesAreDenied() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"First Admin\",\"email\":\"admin@example.test\","
                                + "\"password\":\"temporary-secret-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_exists"));
        verify(seeder).seed("First Admin", "admin@example.test", "temporary-secret-123");

        mockMvc.perform(get("/v1/companies"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/events"))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedBootstrapPayload_isRejectedBeforeSeeding() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"First Admin\",\"email\":\"invalid\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
}
