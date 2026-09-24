package ages.vstable.backend.controller;

import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.BeneficiaryService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BeneficiaryController.class)
@Import(BeneficiaryControllerTest.MethodSecurityTestConfiguration.class)
class BeneficiaryControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BeneficiaryService beneficiaryService;

    // Beans de Segurança necessários para o WebMvcTest
    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void findAll_returnsPaginatedList() throws Exception {
        UUID beneficiaryId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        BeneficiaryResponse response = buildResponse(beneficiaryId, companyId);
        Page<BeneficiaryResponse> pageResponse = new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1);

        when(beneficiaryService.findBeneficiaries(
                eq(companyId), eq("test"), any(), eq("Brasil"), any(Pageable.class))).thenReturn(pageResponse);

        mockMvc.perform(get("/v1/beneficiaries")
                .param("companyId", companyId.toString())
                .param("search", "test")
                .param("country", "Brasil")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.content[0].nickname").value("João Silva"))
                .andExpect(jsonPath("$.content[0].aveniaId").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void findById_returnsBeneficiary() throws Exception {
        UUID id = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        BeneficiaryResponse response = buildResponse(id, companyId);

        when(beneficiaryService.findById(id)).thenReturn(response);

        mockMvc.perform(get("/v1/beneficiaries/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.nickname").value("João Silva"))
                .andExpect(jsonPath("$.aveniaWalletId").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void findById_returnsNotFound_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();

        when(beneficiaryService.findById(id)).thenThrow(new NotFoundException("Beneficiário não encontrado"));

        mockMvc.perform(get("/v1/beneficiaries/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Beneficiário não encontrado"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void findById_returnsForbidden_whenNotAdmin() throws Exception {
        mockMvc.perform(get("/v1/beneficiaries/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    private BeneficiaryResponse buildResponse(UUID id, UUID companyId) {
        BeneficiaryResponse response = new BeneficiaryResponse();
        response.setId(id);
        response.setCompanyId(companyId);
        response.setNickname("João Silva");
        response.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        response.setCreatedAt(OffsetDateTime.now());
        return response;
    }
}