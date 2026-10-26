package ages.vstable.backend.configuration.security;

import ages.vstable.backend.controller.DeploymentSeedController;
import ages.vstable.backend.service.DeploymentSeedService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DeploymentSeedController.class)
@ActiveProfiles("deployment-seed")
@Import({DeploymentSeedSecurityConfiguration.class, SecurityConfiguration.class,
        AuthenticationConfigurer.class, CustomAuthenticationProvider.class,
        BeanManager.class, JwtTokenUtils.class})
class DeploymentSeedSecurityTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean DeploymentSeedService seedService;
    @MockitoBean UserService userService;

    @Test
    void seedRoute_acceptsOnlyPostInPrivateProfile() throws Exception {
        String payload = """
                {"admin":{"fullName":"Initial Admin","email":"admin@example.test","password":"long-password-value"}}
                """;

        mockMvc.perform(post("/internal/deployment-seed").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(true));
        verify(seedService).seed(new ObjectMapper().readValue(payload,
                ages.vstable.backend.dto.DeploymentSeedRequest.class));
        mockMvc.perform(get("/internal/deployment-seed"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/internal/deployment-seed").content("invalid-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicApiProfile_hasNoSeedController() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeploymentSeedController.class)
                .withBean(DeploymentSeedService.class, () -> seedService)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("lambda"))
                .run(context -> assertThat(context).doesNotHaveBean(DeploymentSeedController.class));
    }
}
