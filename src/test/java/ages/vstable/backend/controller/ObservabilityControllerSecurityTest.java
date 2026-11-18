package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.*;
import ages.vstable.backend.dto.observability.EventPageResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.service.observability.*;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ObservabilityController.class, BrowserEventController.class})
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class ObservabilityControllerSecurityTest {
    MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @BeforeEach void setup() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }
    @MockitoBean ObservabilityQueryService service;
    @MockitoBean ObservabilityRecorder recorder;
    @MockitoBean UserService userService;
    @MockitoBean JwtTokenUtils jwtTokenUtils;
    @MockitoBean AuthenticationProvider authenticationProvider;

    @Test
    void anonymousCannotReadOrIngest() throws Exception {
        mvc.perform(get("/v1/admin/observability/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/admin/observability/events/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/observability/browser-events")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, recorder);
    }

    @Test @WithMockUser(roles = "USER")
    void companyUserCannotReadEventsOrDetails() throws Exception {
        mvc.perform(get("/v1/admin/observability/events")).andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/observability/events/dashboard")).andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/observability/events/" + UUID.randomUUID())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void adminCanQueryAndInvalidFiltersAreRejected() throws Exception {
        when(service.list(any())).thenReturn(new EventPageResponse(List.of(), 0, 25, 0, 0, 0, List.of()));
        mvc.perform(get("/v1/admin/observability/events").param("severity", "ERROR").param("module", "transfer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/v1/admin/observability/events").param("size", "10000")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/observability/events").param("severity", "SECRET")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/observability/events/dashboard").param("module", "transfer"))
                .andExpect(status().isOk());
        verify(service).dashboard(argThat(filter -> "transfer".equals(filter.module())));
        for (String[] invalid : List.of(new String[]{"severity", "DEBUG"}, new String[]{"module", "bad.module"},
                new String[]{"from", "yesterday"}, new String[]{"page", "1001"}, new String[]{"size", "101"}))
            mvc.perform(get("/v1/admin/observability/events/dashboard").param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void browserEventsAreAuthenticatedBoundedValidatedAndRateLimited() throws Exception {
        var user = UserEntity.builder().id(UUID.randomUUID()).build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID()
                + "\",\"kind\":\"UI_ERROR\",\"module\":\"TRANSFERS\"}";
        mvc.perform(post("/v1/observability/browser-events").with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        verify(recorder).browser(any(), eq(user.getId()));
        mvc.perform(post("/v1/observability/browser-events").with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(1025))).andExpect(status().is(413));
        mvc.perform(post("/v1/observability/browser-events").with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("UI_ERROR", "PASSWORD")))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 17; i++) mvc.perform(post("/v1/observability/browser-events").with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        mvc.perform(post("/v1/observability/browser-events").with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }
}
