package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.ExchangeRateInfo;
import ages.vstable.backend.dto.transfer.FeeInfo;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.utils.JwtTokenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private SecurityContextRepository securityContextRepository;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private UserRepository userRepository;

    private String validPayload() throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("amount", 125000.00);
            put("amountType", "SOURCE");
            put("sourceCurrency", "BRL");
            put("destinationCurrency", "USD");
        }});
    }

    private TransferQuoteResponse sampleResponse() {
        return new TransferQuoteResponse(
                new MoneyAmount(new BigDecimal("125000.00"), "BRL"),
                new MoneyAmount(new BigDecimal("24235.14"), "USD"),
                new ExchangeRateInfo("USD", "BRL", new BigDecimal("5.16")),
                new FeeInfo(new BigDecimal("0.45"), new BigDecimal("562.50"), "BRL"),
                new MoneyAmount(new BigDecimal("125562.50"), "BRL"));
    }

    @Test
    void post_quoteValida_retorna200ComBreakdownSemIdentificadoresDaAvenia() throws Exception {
        when(transferService.quote(any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/v1/transfers/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.amount").value(125000.00))
                .andExpect(jsonPath("$.source.currency").value("BRL"))
                .andExpect(jsonPath("$.destination.amount").value(24235.14))
                .andExpect(jsonPath("$.destination.currency").value("USD"))
                .andExpect(jsonPath("$.exchangeRate.rate").value(5.16))
                .andExpect(jsonPath("$.fee.percentage").value(0.45))
                .andExpect(jsonPath("$.fee.amount").value(562.50))
                .andExpect(jsonPath("$.total.amount").value(125562.50))
                .andExpect(jsonPath("$.quoteToken").doesNotExist())
                .andExpect(jsonPath("$.quoteId").doesNotExist())
                .andExpect(jsonPath("$.ticketId").doesNotExist());
    }

    @Test
    void post_semAmount_retorna400() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("amountType", "SOURCE");
        }});

        mockMvc.perform(post("/v1/transfers/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_semAmountType_retorna400() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("amount", 100.00);
        }});

        mockMvc.perform(post("/v1/transfers/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_moedaDeDestinoNaoDeterminavel_retorna422() throws Exception {
        when(transferService.quote(any(), any())).thenThrow(
                new UnprocessableEntityException("Não foi possível determinar a moeda de destino da transferência"));

        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("amount", 100.00);
            put("amountType", "SOURCE");
        }});

        mockMvc.perform(post("/v1/transfers/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void post_falhaNaAvenia_retorna503QuandoRetryable() throws Exception {
        when(transferService.quote(any(), any())).thenThrow(
                AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        mockMvc.perform(post("/v1/transfers/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isServiceUnavailable());
    }

    private String createTransferPayload(UUID beneficiaryId) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("amount", 125000.00);
            put("amountType", "SOURCE");
            put("sourceCurrency", "BRL");
            put("destinationCurrency", "USD");
            put("paymentMethod", "PIX");
            put("beneficiaryId", beneficiaryId.toString());
            put("description", "Pagamento de importação");
        }});
    }

    @Test
    void post_transferenciaValida_retorna200ComStatusSemIdentificadoresDaAvenia() throws Exception {
        when(transferService.create(any(), any())).thenReturn(new CreateTransferResponse(TransactionStatus.PROCESSING));

        mockMvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.quoteToken").doesNotExist())
                .andExpect(jsonPath("$.quoteId").doesNotExist())
                .andExpect(jsonPath("$.ticketId").doesNotExist());
    }

    @Test
    void post_semBeneficiaryId_retorna400() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {{
            put("amount", 100.00);
            put("amountType", "SOURCE");
            put("paymentMethod", "PIX");
        }});

        mockMvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_beneficiarioInexistente_retorna404() throws Exception {
        when(transferService.create(any(), any())).thenThrow(new NotFoundException("Beneficiário não encontrado"));

        mockMvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_falhaNaAveniaAoCriarTransferencia_retorna503QuandoRetryable() throws Exception {
        when(transferService.create(any(), any())).thenThrow(
                AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        mockMvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable());
    }
}
