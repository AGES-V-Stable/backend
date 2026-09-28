package ages.vstable.backend.controller;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.dto.beneficiary.BeneficiaryResponse;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(beneficiaryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_validBankAccountRequest_returns201() throws Exception {
        BeneficiaryCreateRequest request = validBankAccountRequest();
        BeneficiaryResponse response = sampleResponse();

        when(beneficiaryService.create(eq(companyId), any())).thenReturn(response);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
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

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(response.getId().toString()));
    }

    @Test
    void create_missingIdentificationDocument_returns400() throws Exception {
        BeneficiaryCreateRequest request = validBankAccountRequest();
        request.setIdentificationDocument(null);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_companyNotFound_returns404() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new NotFoundException("Company not found"));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Company not found"));
    }

    @Test
    void create_companyNotApproved_returns403() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new ForbiddenException("Empresa não verificada"));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Empresa não verificada"));
    }

    @Test
    void create_missingBankFieldsForBankAccount_returns400() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new IllegalArgumentException("bankName: Campo obrigatório."));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBankAccountRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("bankName: Campo obrigatório."));
    }

    @Test
    void create_missingWalletFieldsForCryptoWallet_returns400() throws Exception {
        when(beneficiaryService.create(eq(companyId), any()))
                .thenThrow(new IllegalArgumentException("walletAddress: Campo obrigatório."));

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validWalletRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("walletAddress: Campo obrigatório."));
    }

    @Test
    void create_confirmedFalse_returns400() throws Exception {
        BeneficiaryCreateRequest request = validBankAccountRequest();
        request.setConfirmed(false);

        mockMvc.perform(post("/v1/companies/{companyId}/beneficiaries", companyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
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
}
