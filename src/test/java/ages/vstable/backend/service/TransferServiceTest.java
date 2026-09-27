package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.AmountType;
import ages.vstable.backend.dto.transfer.PaymentMethod;
import ages.vstable.backend.dto.transfer.TransferCreateRequest;
import ages.vstable.backend.dto.transfer.TransferCreateResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResult;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AveniaClient aveniaClient;

    @InjectMocks
    private TransferService transferService;

    @Test
    void quote_shouldReturnFormattedResponse_whenCurrenciesAreProvided() {
        TransferQuoteRequest request = buildQuoteRequest("BRL", "USD");
        when(aveniaClient.getQuote("BRL", "USD", new BigDecimal("125000.00"), AmountType.SOURCE))
                .thenReturn(buildAveniaQuote());

        TransferQuoteResponse response = transferService.quote(request);

        assertThat(response.source().amount()).isEqualByComparingTo("125000.00");
        assertThat(response.source().currency()).isEqualTo("BRL");
        assertThat(response.destination().amount()).isEqualByComparingTo("24235.14");
        assertThat(response.destination().currency()).isEqualTo("USD");
        assertThat(response.exchangeRate().fromCurrency()).isEqualTo("USD");
        assertThat(response.exchangeRate().toCurrency()).isEqualTo("BRL");
        assertThat(response.exchangeRate().rate()).isEqualByComparingTo("5.16");
        assertThat(response.fee().percentage()).isEqualByComparingTo("0.45");
        assertThat(response.fee().amount()).isEqualByComparingTo("562.50");
        assertThat(response.fee().currency()).isEqualTo("BRL");
        assertThat(response.total().amount()).isEqualByComparingTo("125562.50");
        assertThat(response.total().currency()).isEqualTo("BRL");
    }

    @Test
    void quote_shouldDefaultToBRL_whenSourceCurrencyIsAbsent() {
        TransferQuoteRequest request = buildQuoteRequest(null, "USD");
        when(aveniaClient.getQuote(eq(TransferService.DEFAULT_SOURCE_CURRENCY), eq("USD"), any(), any()))
                .thenReturn(buildAveniaQuote());

        TransferQuoteResponse response = transferService.quote(request);

        verify(aveniaClient).getQuote(TransferService.DEFAULT_SOURCE_CURRENCY, "USD",
                new BigDecimal("125000.00"), AmountType.SOURCE);
        assertThat(response.source().currency()).isEqualTo(TransferService.DEFAULT_SOURCE_CURRENCY);
    }

    @Test
    void quote_shouldDefaultToUSD_whenDestinationCurrencyIsAbsent() {
        TransferQuoteRequest request = buildQuoteRequest("BRL", null);
        when(aveniaClient.getQuote(eq("BRL"), eq(TransferService.DEFAULT_DESTINATION_CURRENCY), any(), any()))
                .thenReturn(buildAveniaQuote());

        transferService.quote(request);

        verify(aveniaClient).getQuote("BRL", TransferService.DEFAULT_DESTINATION_CURRENCY,
                new BigDecimal("125000.00"), AmountType.SOURCE);
    }

    @Test
    void quote_shouldPropagateAveniaException_whenIntegrationFails() {
        TransferQuoteRequest request = buildQuoteRequest("BRL", "USD");
        when(aveniaClient.getQuote(any(), any(), any(), any()))
                .thenThrow(new AveniaIntegrationException("Avenia unavailable"));

        assertThatThrownBy(() -> transferService.quote(request))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("Avenia unavailable");
    }

    @Test
    void create_shouldReturnProcessing_whenQuoteAndTicketSucceed() {
        UUID beneficiaryId = UUID.randomUUID();
        TransferCreateRequest request = buildCreateRequest("BRL", "USD", beneficiaryId);
        when(aveniaClient.getQuote("BRL", "USD", new BigDecimal("125000.00"), AmountType.SOURCE))
                .thenReturn(buildAveniaQuote());
        when(aveniaClient.createTicket(eq("mock-quote-token"), any(UUID.class), eq(beneficiaryId), eq("USD")))
                .thenReturn(new AveniaTicketResult("UNPAID"));

        TransferCreateResponse response = transferService.create(request);

        assertThat(response.status()).isEqualTo("PROCESSING");
    }

    @Test
    void create_shouldAlwaysGenerateNewQuote_notReusePrevious() {
        UUID beneficiaryId = UUID.randomUUID();
        TransferCreateRequest request = buildCreateRequest("BRL", "USD", beneficiaryId);
        when(aveniaClient.getQuote(any(), any(), any(), any())).thenReturn(buildAveniaQuote());
        when(aveniaClient.createTicket(any(), any(), any(), any())).thenReturn(new AveniaTicketResult("UNPAID"));

        transferService.create(request);

        verify(aveniaClient).getQuote("BRL", "USD", new BigDecimal("125000.00"), AmountType.SOURCE);
        verify(aveniaClient).createTicket(eq("mock-quote-token"), any(UUID.class), eq(beneficiaryId), eq("USD"));
    }

    @Test
    void create_shouldPropagateAveniaException_whenQuoteFails() {
        TransferCreateRequest request = buildCreateRequest("BRL", "USD", UUID.randomUUID());
        when(aveniaClient.getQuote(any(), any(), any(), any()))
                .thenThrow(new AveniaIntegrationException("Quote service error"));

        assertThatThrownBy(() -> transferService.create(request))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("Quote service error");
    }

    @Test
    void create_shouldPropagateAveniaException_whenTicketCreationFails() {
        UUID beneficiaryId = UUID.randomUUID();
        TransferCreateRequest request = buildCreateRequest("BRL", "USD", beneficiaryId);
        when(aveniaClient.getQuote(any(), any(), any(), any())).thenReturn(buildAveniaQuote());
        when(aveniaClient.createTicket(any(), any(), any(), any()))
                .thenThrow(new AveniaIntegrationException("Ticket creation failed"));

        assertThatThrownBy(() -> transferService.create(request))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("Ticket creation failed");
    }

    private TransferQuoteRequest buildQuoteRequest(String sourceCurrency, String destinationCurrency) {
        TransferQuoteRequest request = new TransferQuoteRequest();
        request.setAmount(new BigDecimal("125000.00"));
        request.setAmountType(AmountType.SOURCE);
        request.setSourceCurrency(sourceCurrency);
        request.setDestinationCurrency(destinationCurrency);
        return request;
    }

    private TransferCreateRequest buildCreateRequest(String sourceCurrency, String destinationCurrency, UUID beneficiaryId) {
        TransferCreateRequest request = new TransferCreateRequest();
        request.setAmount(new BigDecimal("125000.00"));
        request.setAmountType(AmountType.SOURCE);
        request.setSourceCurrency(sourceCurrency);
        request.setDestinationCurrency(destinationCurrency);
        request.setPaymentMethod(PaymentMethod.ACCOUNT_BALANCE);
        request.setBeneficiaryId(beneficiaryId);
        request.setDescription("Test transfer");
        return request;
    }

    private AveniaQuoteResult buildAveniaQuote() {
        return new AveniaQuoteResult(
                new BigDecimal("125000.00"),
                new BigDecimal("24235.14"),
                "mock-quote-token",
                new BigDecimal("5.16"),
                "USD/BRL",
                new BigDecimal("562.50"),
                new BigDecimal("0.45"),
                "BRL"
        );
    }
}
