package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.service.TransactionVolumeService;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransactionVolumeControllerTest {

    @Mock
    private TransactionVolumeService service;

    @InjectMocks
    private TransactionVolumeController controller;

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
    void getTransactionVolume_returnsTheVolumeOfTheAuthenticatedUsersCompanyOverTheWholeHistory() throws Exception {
        when(service.getSettledVolume(companyId, null)).thenReturn(new TransactionVolumeResponse("412000.00"));

        mockMvc.perform(get("/v1/dashboard/transaction-volume").principal(authenticated(companyUser(companyId))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactedVolumeBrl").value("412000.00"));
    }

    @Test
    void getTransactionVolume_passesTheStartDateToTheService() throws Exception {
        when(service.getSettledVolume(companyId, LocalDate.of(2026, 10, 1)))
                .thenReturn(new TransactionVolumeResponse("75.30"));

        mockMvc.perform(get("/v1/dashboard/transaction-volume")
                        .param("startDate", "2026-10-01")
                        .principal(authenticated(companyUser(companyId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactedVolumeBrl").value("75.30"));
    }

    @Test
    void getTransactionVolume_rejectsAMalformedStartDate() throws Exception {
        mockMvc.perform(get("/v1/dashboard/transaction-volume")
                        .param("startDate", "01/10/2026")
                        .principal(authenticated(companyUser(companyId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(service);
    }

    @Test
    void getTransactionVolume_isForbiddenForAdministrators() throws Exception {
        mockMvc.perform(get("/v1/dashboard/transaction-volume").principal(authenticated(new AdministratorEntity())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void getTransactionVolume_isForbiddenForUsersWithoutCompany() throws Exception {
        mockMvc.perform(get("/v1/dashboard/transaction-volume").principal(authenticated(companyUser(null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }
}
