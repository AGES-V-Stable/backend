package ages.vstable.backend.controller;

import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.service.BalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BalanceControllerTest {

    @Mock
    private BalanceService service;

    @InjectMocks
    private BalanceController controller;

    private MockMvc mockMvc;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Authentication authenticated(Object principal) {
        return new UsernamePasswordAuthenticationToken(principal, null);
    }

    private UserEntity companyUser(UUID userCompanyId) {
        return UserEntity.builder().id(UUID.randomUUID()).companyId(userCompanyId).build();
    }

    @Test
    void getBalance_returnsTheBalanceOfTheAuthenticatedUsersCompany() throws Exception {
        when(service.getBalance(companyId)).thenReturn(new BalanceResponse("485200.00"));

        mockMvc.perform(get("/v1/balance").principal(authenticated(companyUser(companyId))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.availableBalanceBrl").value("485200.00"));
    }

    @Test
    void getBalance_isForbiddenForAdministrators() throws Exception {
        Authentication admin = authenticated(new AdministratorEntity());

        mockMvc.perform(get("/v1/balance").principal(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void getBalance_isForbiddenForUsersWithoutCompany() throws Exception {
        mockMvc.perform(get("/v1/balance").principal(authenticated(companyUser(null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void getBalance_returnsNotFoundWhenCompanyDoesNotExist() throws Exception {
        when(service.getBalance(companyId)).thenThrow(new NotFoundException("Company not found"));

        mockMvc.perform(get("/v1/balance").principal(authenticated(companyUser(companyId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
