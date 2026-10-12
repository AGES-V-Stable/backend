package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Cost;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Costs;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.ExchangeRate;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Money;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.exception.TransferDetailsException;
import ages.vstable.backend.exception.TransferDetailsExceptionHandler;
import ages.vstable.backend.service.TransferDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TransferDetailsControllerTest {

    @Mock
    private TransferDetailsService service;

    @InjectMocks
    private TransferDetailsController controller;

    private MockMvc mockMvc;

    private final UUID transferId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TransferDetailsExceptionHandler())
                .build();
    }

    private UserEntity user() {
        return UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();
    }

    private Authentication authenticated(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private TransferDetailsResponse details() {
        return new TransferDetailsResponse(
                transferId,
                companyId,
                OffsetDateTime.of(2026, 8, 24, 14, 32, 0, 0, ZoneOffset.ofHours(-3)),
                Type.PAGAMENTO,
                TransactionStatus.SETTLED,
                "Atlas Imports LLC",
                null,
                new Money("125000.00", "BRL"),
                new Money("23062.73", "USD"),
                "ACCOUNT_BALANCE",
                new ExchangeRate("USD", "BRL", "5.42"),
                new Costs(new Cost("562.50", "BRL", "0.45"), null, null),
                null,
                true);
    }

    @Test
    void getDetails_returnsTheContractWithDecimalStringsAndNulls() throws Exception {
        UserEntity user = user();
        when(service.getDetails(eq(transferId), eq(user))).thenReturn(details());

        mockMvc.perform(get("/api/transferencias/{id}", transferId).principal(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(transferId.toString()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.date").value(startsWith("2026-08-24T14:32:00")))
                .andExpect(jsonPath("$.type").value("PAGAMENTO"))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.counterpartyName").value("Atlas Imports LLC"))
                .andExpect(jsonPath("$.counterpartyDetails").value(nullValue()))
                .andExpect(jsonPath("$.source.amount").value("125000.00"))
                .andExpect(jsonPath("$.source.currency").value("BRL"))
                .andExpect(jsonPath("$.destination.amount").value("23062.73"))
                .andExpect(jsonPath("$.fundingSource").value("ACCOUNT_BALANCE"))
                .andExpect(jsonPath("$.exchangeRate.rate").value("5.42"))
                .andExpect(jsonPath("$.costs.serviceFee.percentage").value("0.45"))
                .andExpect(jsonPath("$.costs.spreadPercentage").value(nullValue()))
                .andExpect(jsonPath("$.costs.estimatedMarketCost").value(nullValue()))
                .andExpect(jsonPath("$.estimatedSavings").value(nullValue()))
                .andExpect(jsonPath("$.receiptAvailable").value(true));
    }

    @Test
    void getDetails_administratorPrincipal_isHandedToTheServiceAsNoCompanyUser() throws Exception {
        AdministratorEntity admin = AdministratorEntity.builder().id(UUID.randomUUID()).build();
        Authentication adminAuth = new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
        when(service.getDetails(eq(transferId), eq(null))).thenThrow(TransferDetailsException.companyAccessRequired());

        mockMvc.perform(get("/api/transferencias/{id}", transferId).principal(adminAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REQUIRED"));
    }

    @Test
    void getDetails_neverReadsTheCompanyFromTheRequest() throws Exception {
        UserEntity user = user();
        when(service.getDetails(eq(transferId), any())).thenReturn(details());

        mockMvc.perform(get("/api/transferencias/{id}", transferId)
                        .param("companyId", UUID.randomUUID().toString())
                        .principal(authenticated(user)))
                .andExpect(status().isOk());

        ArgumentCaptor<UserEntity> used = ArgumentCaptor.forClass(UserEntity.class);
        verify(service).getDetails(eq(transferId), used.capture());
        assertThat(used.getValue().getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void getDetails_invalidId_returns400WithCode() throws Exception {
        mockMvc.perform(get("/api/transferencias/{id}", "not-a-uuid").principal(authenticated(user())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSFER_ID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void getDetails_businessErrors_mapToTheirStatusAndCode() throws Exception {
        when(service.getDetails(eq(transferId), any()))
                .thenThrow(TransferDetailsException.userNotVerified())
                .thenThrow(TransferDetailsException.transferNotFound());

        mockMvc.perform(get("/api/transferencias/{id}", transferId).principal(authenticated(user())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_NOT_VERIFIED"));
        mockMvc.perform(get("/api/transferencias/{id}", transferId).principal(authenticated(user())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getDetails_unexpectedFailure_returns500WithoutLeakingTheCause() throws Exception {
        when(service.getDetails(eq(transferId), any())).thenThrow(new IllegalStateException("segredo interno 123"));

        mockMvc.perform(get("/api/transferencias/{id}", transferId).principal(authenticated(user())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("segredo interno"))));
    }

    @Test
    void downloadReceipt_returnsThePdfAsPrivateAttachment() throws Exception {
        UserEntity user = user();
        byte[] pdf = "%PDF-1.7 conteudo".getBytes();
        when(service.getReceipt(eq(transferId), eq(user)))
                .thenReturn(new TransferDetailsService.Receipt("comprovante-transferencia.pdf", pdf));

        mockMvc.perform(get("/api/transferencias/{id}/comprovante", transferId)
                        .accept(MediaType.APPLICATION_PDF)
                        .principal(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"comprovante-transferencia.pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void downloadReceipt_unavailable_returnsJsonErrorEvenWhenPdfWasRequested() throws Exception {
        when(service.getReceipt(eq(transferId), any())).thenThrow(TransferDetailsException.receiptUnavailable());

        mockMvc.perform(get("/api/transferencias/{id}/comprovante", transferId)
                        .accept(MediaType.APPLICATION_PDF)
                        .principal(authenticated(user())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RECEIPT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("O comprovante desta transferência ainda não está disponível."));
    }

    @Test
    void downloadReceipt_invalidId_returns400WithCode() throws Exception {
        mockMvc.perform(get("/api/transferencias/{id}/comprovante", "123")
                        .accept(MediaType.APPLICATION_PDF)
                        .principal(authenticated(user())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSFER_ID"));
    }

    @Test
    void downloadReceipt_pdfGenerationFailure_returns500() throws Exception {
        when(service.getReceipt(eq(transferId), any())).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("falha")));

        mockMvc.perform(get("/api/transferencias/{id}/comprovante", transferId)
                        .accept(MediaType.APPLICATION_PDF)
                        .principal(authenticated(user())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
