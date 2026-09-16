package ages.vstable.backend.controller;

import ages.vstable.backend.dto.user.UserResponse;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

@WebMvcTest(RepresentanteController.class)
class RepresentanteControllerTest {

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

    private UserResponse representante(UUID id, UUID empresaId) {
        UserResponse response = new UserResponse();
        response.setId(id);
        response.setCompanyId(empresaId);
        response.setFullName("Joao da Silva");
        response.setEmail("joao@example.com");
        response.setCreatedAt(OffsetDateTime.now());
        response.setUpdatedAt(OffsetDateTime.now());
        return response;
    }

    @Test
    void get_listaTodosOsRepresentantes_retorna200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();
        when(userService.findAll()).thenReturn(List.of(representante(id, empresaId)));

        mockMvc.perform(get("/v1/representantes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].companyId").value(empresaId.toString()))
                .andExpect(jsonPath("$[0].email").value("joao@example.com"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void get_semRepresentantesCadastrados_retorna200ComListaVazia() throws Exception {
        when(userService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/v1/representantes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
