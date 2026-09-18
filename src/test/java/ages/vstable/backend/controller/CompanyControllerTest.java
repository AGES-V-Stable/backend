package ages.vstable.backend.controller;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.service.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {

    @Mock
    private CompanyService companyService;

    @InjectMocks
    private CompanyController companyController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(companyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getComplianceStatus_shouldReturnCurrentStatus() throws Exception {
        UUID id = UUID.randomUUID();
        CompanyComplianceStatusResponse response = new CompanyComplianceStatusResponse();
        response.setId(id);
        response.setLegalName("Empresa Teste LTDA");
        response.setCnpj("00.000.000/0001-00");
        response.setStatusKyb(ComplianceStatus.APPROVED);
        response.setStatusAml(ComplianceStatus.UNDER_REVIEW);
        response.setOverallStatus(ComplianceStatus.UNDER_REVIEW);
        response.setDocuments(List.of());

        when(companyService.getComplianceStatus(id)).thenReturn(response);

        mockMvc.perform(get("/v1/companies/{id}/compliance-status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.statusKyb").value("APPROVED"))
                .andExpect(jsonPath("$.statusAml").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.overallStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.documents").isArray());
    }

    @Test
    void getComplianceStatus_shouldReturnNotFound_whenCompanyDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.getComplianceStatus(id)).thenThrow(new NotFoundException("Company not found"));

        mockMvc.perform(get("/v1/companies/{id}/compliance-status", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Company not found"));
    }

    @Test
    void getComplianceStatus_shouldReturnUnprocessableEntity_whenComplianceStatusIsInvalid() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.getComplianceStatus(id))
                .thenThrow(new UnprocessableEntityException("Company has an invalid compliance status"));

        mockMvc.perform(get("/v1/companies/{id}/compliance-status", id))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Company has an invalid compliance status"));
    }
}
