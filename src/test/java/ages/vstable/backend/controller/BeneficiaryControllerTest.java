package ages.vstable.backend.controller;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.service.BeneficiaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class BeneficiaryControllerTest {

    @Mock
    private BeneficiaryService beneficiaryService;

    @InjectMocks
    private BeneficiaryController beneficiaryController;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID companyId = UUID.randomUUID();

    private Authentication member() {
        return userOf(companyId);
    }

    private Authentication userOf(UUID company) {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(company).build();
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private Authentication admin() {
        AdministratorEntity admin = AdministratorEntity.builder().id(UUID.randomUUID()).build();
        return new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
    }

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(beneficiaryController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_validBankAccountRequest_returns201() throws Exception {
        BeneficiaryCreateRequest request = validBankAccountRequest();
        BeneficiaryResponse response = sampleResponse();

        when(beneficiaryService.create(eq(companyId), any())).thenReturn(response);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.getId().toString()));
    }

    @Test
    void create_validWalletRequest_returns201() throws Exception {
        BeneficiaryCreateRequest request = validWalletRequest();
        BeneficiaryResponse response = sampleResponse();

        when(beneficiaryService.create(eq(companyId), any())).thenReturn(response);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.getId().toString()));
    }

    @Test
    void create_missingIdentificationDocument_returns400() throws Exception {
        BeneficiaryCreateRequest request = validBankAccountRequest();
        request.setIdentificationDocument(null);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_companyNotFound_returns404() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new NotFoundException("Company not found"));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Company not found"));
    }

    @Test
    void create_companyNotApproved_returns403() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new ForbiddenException("Empresa não verificada"));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Empresa não verificada"));
    }

    @Test
    void create_missingBankFieldsForBankAccount_returns400() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new IllegalArgumentException("bankName: Campo obrigatório."));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("bankName: Campo obrigatório."));
    }

    @Test
    void create_missingWalletFieldsForCryptoWallet_returns400() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new IllegalArgumentException("walletAddress: Campo obrigatório."));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validWalletRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("walletAddress: Campo obrigatório."));
    }

    @Test
    void create_confirmedFalse_returns400() throws Exception {
        BeneficiaryCreateRequest request = validBankAccountRequest();
        request.setConfirmed(false);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId).principal(member())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void findAll_returnsPaginatedList() throws Exception {
        UUID beneficiaryId = UUID.randomUUID();
        UUID searchCompanyId = UUID.randomUUID();
        BeneficiaryResponse response = buildResponse(beneficiaryId, searchCompanyId);
        Page<BeneficiaryResponse> pageResponse = new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1);

        when(beneficiaryService.findBeneficiaries(
                eq(searchCompanyId), eq("test"), any(), eq("Brasil"), any(Pageable.class))).thenReturn(pageResponse);

        mockMvc.perform(get("/v1/beneficiaries")
                .param("companyId", searchCompanyId.toString())
                .param("search", "test")
                .param("document", "123")
                .param("country", "Brasil")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].companyId").value(searchCompanyId.toString()))
                .andExpect(jsonPath("$.content[0].nickname").value("João Silva"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void findById_returnsBeneficiary() throws Exception {
        UUID id = UUID.randomUUID();
        UUID searchCompanyId = UUID.randomUUID();
        BeneficiaryResponse response = buildResponse(id, searchCompanyId);

        when(beneficiaryService.findById(id)).thenReturn(response);

        mockMvc.perform(get("/v1/beneficiaries/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.companyId").value(searchCompanyId.toString()))
                .andExpect(jsonPath("$.nickname").value("João Silva"));
    }

    @Test
    void findById_returnsNotFound_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();

        when(beneficiaryService.findById(id)).thenThrow(new NotFoundException("Beneficiário não encontrado"));

        mockMvc.perform(get("/v1/beneficiaries/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Beneficiário não encontrado"));
    }

    @Test
    void create_userOfAnotherCompany_returns403WithoutCallingService() throws Exception {
        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                .principal(userOf(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(beneficiaryService);
    }

    @Test
    void create_administrator_returns403() throws Exception {
        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                .principal(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(beneficiaryService);
    }

    @Test
    void findByCompany_member_returnsCompanyPage() throws Exception {
        Page<BeneficiaryResponse> page = new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1);
        when(beneficiaryService.findBeneficiaries(eq(companyId), eq("ana"), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/v1/companies/{companyId}/beneficiaries", companyId)
                .principal(member())
                .param("search", "ana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void findByCompany_administrator_isAllowed() throws Exception {
        when(beneficiaryService.findBeneficiaries(eq(companyId), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/v1/companies/{companyId}/beneficiaries", companyId).principal(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void findByCompany_userOfAnotherCompany_returns403() throws Exception {
        mockMvc.perform(get("/v1/companies/{companyId}/beneficiaries", companyId)
                .principal(userOf(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(beneficiaryService);
    }

    @Test
    void findByCompanyAndId_member_returnsBeneficiary() throws Exception {
        BeneficiaryResponse response = sampleResponse();
        when(beneficiaryService.findByIdForCompany(companyId, response.getId())).thenReturn(response);

        mockMvc.perform(get("/v1/companies/{companyId}/beneficiaries/{id}", companyId, response.getId())
                .principal(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.getId().toString()));
    }

    @Test
    void findByCompanyAndId_beneficiaryOfAnotherCompany_returns404() throws Exception {
        UUID beneficiaryId = UUID.randomUUID();
        when(beneficiaryService.findByIdForCompany(companyId, beneficiaryId))
                .thenThrow(new NotFoundException("Beneficiário não encontrado"));

        mockMvc.perform(get("/v1/companies/{companyId}/beneficiaries/{id}", companyId, beneficiaryId)
                .principal(member()))
                .andExpect(status().isNotFound());
    }

    private BeneficiaryCreateRequest validBankAccountRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setBeneficiaryType("LEGAL_ENTITY");
        request.setLegalName("Fornecedor Teste Ltda");
        request.setIdentificationDocument("11222333000181");
        request.setCountry("Brasil");
        request.setAddress("Rua Teste, 123");
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Fornecedor Principal");
        request.setBankName("Banco Teste");
        request.setSwiftBic("TESTBRSPXXX");
        request.setAccountNumber("12345-6");
        request.setCurrency("BRL");
        request.setConfirmed(true);
        return request;
    }

    private BeneficiaryCreateRequest validWalletRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setBeneficiaryType("LEGAL_ENTITY");
        request.setLegalName("Fornecedor Teste Ltda");
        request.setIdentificationDocument("11222333000181");
        request.setCountry("Brasil");
        request.setAddress("Rua Teste, 123");
        request.setReceivingMethod(ReceivingMethod.CRYPTO_WALLET);
        request.setNickname("Carteira Principal");
        request.setWalletAddress("0xABCDEF1234567890");
        request.setBlockchainNetwork(BlockchainNetwork.ethereum);
        request.setConfirmed(true);
        return request;
    }

    private BeneficiaryResponse sampleResponse() {
        BeneficiaryResponse response = new BeneficiaryResponse();
        response.setId(UUID.randomUUID());
        response.setCompanyId(companyId);
        response.setBeneficiaryType("LEGAL_ENTITY");
        response.setLegalName("Fornecedor Teste Ltda");
        response.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        return response;
    }

    private BeneficiaryResponse buildResponse(UUID id, UUID searchCompanyId) {
        BeneficiaryResponse response = new BeneficiaryResponse();
        response.setId(id);
        response.setCompanyId(searchCompanyId);
        response.setNickname("João Silva");
        response.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        response.setCreatedAt(OffsetDateTime.now());
        return response;
    }
}
