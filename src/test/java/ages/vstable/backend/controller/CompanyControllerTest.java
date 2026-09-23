package ages.vstable.backend.controller;

import ages.vstable.backend.dto.company.CompanyComplianceStatusResponse;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.dto.company.CompanyUpdateRequest;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.service.CompanyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(companyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        CompanyCreateRequest request = validCreateRequest();
        CompanyResponse response = sampleResponse(UUID.randomUUID());

        when(companyService.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.getId().toString()))
                .andExpect(jsonPath("$.cnpj").value(response.getCnpj()));
    }

    @Test
    void create_missingRequiredFields_returns400() throws Exception {
        CompanyCreateRequest request = new CompanyCreateRequest();

        mockMvc.perform(post("/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_cnpjAlreadyRegistered_returns409() throws Exception {
        when(companyService.create(any())).thenThrow(new ConflictException("CNPJ already registered"));

        mockMvc.perform(post("/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("CNPJ already registered"));
    }

    @Test
    void findAll_returnsListOfCompanies() throws Exception {
        CompanyResponse response = sampleResponse(UUID.randomUUID());
        when(companyService.findAll()).thenReturn(List.of(response));

        mockMvc.perform(get("/v1/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(response.getId().toString()));
    }

    @Test
    void findAll_noCompaniesRegistered_returnsEmptyList() throws Exception {
        when(companyService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/v1/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void findById_returnsCompany_whenExists() throws Exception {
        UUID id = UUID.randomUUID();
        CompanyResponse response = sampleResponse(id);
        when(companyService.findById(id)).thenReturn(Optional.of(response));

        mockMvc.perform(get("/v1/companies/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void findById_returnsNotFound_whenCompanyDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/companies/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_returnsUpdatedCompany_whenCompanyExists() throws Exception {
        UUID id = UUID.randomUUID();
        CompanyResponse response = sampleResponse(id);
        when(companyService.existsById(id)).thenReturn(true);
        when(companyService.update(eq(id), any())).thenReturn(response);

        mockMvc.perform(put("/v1/companies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void update_returnsNotFound_whenCompanyDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.existsById(id)).thenReturn(false);

        mockMvc.perform(put("/v1/companies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_missingRequiredFields_returns400() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/v1/companies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompanyUpdateRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_cnpjAlreadyRegisteredByAnotherCompany_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.existsById(id)).thenReturn(true);
        when(companyService.update(eq(id), any())).thenThrow(new ConflictException("CNPJ already registered"));

        mockMvc.perform(put("/v1/companies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void delete_returnsNoContent_whenCompanyExists() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.existsById(id)).thenReturn(true);

        mockMvc.perform(delete("/v1/companies/{id}", id))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returnsNotFound_whenCompanyDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.existsById(id)).thenReturn(false);

        mockMvc.perform(delete("/v1/companies/{id}", id))
                .andExpect(status().isNotFound());
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

    private CompanyCreateRequest validCreateRequest() {
        CompanyCreateRequest request = new CompanyCreateRequest();
        request.setLegalName("Empresa Legada Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }

    private CompanyUpdateRequest validUpdateRequest() {
        CompanyUpdateRequest request = new CompanyUpdateRequest();
        request.setLegalName("Empresa Legada Ltda");
        request.setCnpj("11.222.333/0001-81");
        request.setCountry("Brasil");
        request.setZipCode("90000-000");
        request.setCity("Porto Alegre");
        request.setState("RS");
        return request;
    }

    private CompanyResponse sampleResponse(UUID id) {
        CompanyResponse response = new CompanyResponse();
        response.setId(id);
        response.setLegalName("Empresa Legada Ltda");
        response.setCnpj("11222333000181");
        response.setCountry("Brasil");
        response.setZipCode("90000000");
        response.setCity("Porto Alegre");
        response.setState("RS");
        response.setStatusKyb(ComplianceStatus.PENDING);
        response.setStatusAml(ComplianceStatus.PENDING);
        return response;
    }
}
