package ages.vstable.backend.controller;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RepresentativeController.class)
class RepresentativeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

    private UserResponse representative(UUID id, UUID companyId) {
        UserResponse response = new UserResponse();
        response.setId(id);
        response.setCompanyId(companyId);
        response.setFullName("Joao da Silva");
        response.setEmail("joao@example.com");
        response.setCreatedAt(OffsetDateTime.now());
        response.setUpdatedAt(OffsetDateTime.now());
        return response;
    }

    @Test
    void get_listsAllRepresentatives_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        when(userService.findAll()).thenReturn(List.of(representative(id, companyId)));

        mockMvc.perform(get("/v1/representatives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].companyId").value(companyId.toString()))
                .andExpect(jsonPath("$[0].email").value("joao@example.com"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void get_noRepresentativesRegistered_returns200WithEmptyList() throws Exception {
        when(userService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/v1/representatives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
