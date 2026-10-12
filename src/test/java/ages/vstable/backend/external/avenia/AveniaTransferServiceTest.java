package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaAppliedFee;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaBrlBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaEurBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaSwiftBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import ages.vstable.backend.external.avenia.dto.AveniaUsdBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AveniaTransferServiceTest {

    @Test
    void delegatesQuoteCreation() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("quote-token");

        AveniaQuoteResponse response = service.createQuote(quoteRequest());

        assertEquals("quote-token", response.quoteToken());
    }

    @Test
    void usesFreshQuoteTokenAndQuoteSubAccountToCreateTicket() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        AveniaQuoteRequest quoteRequest = quoteRequest();
        AveniaQuoteResponse quote = quoteResponse("fresh-token");
        UUID ticketId = UUID.randomUUID();
        AveniaTicketResponse ticket = new AveniaTicketResponse(
                ticketId, null, null, "PROCESSING", null, null);
        AveniaTicketRequest request = AveniaTicketRequest.builder()
                .quoteToken("old-token-that-must-not-be-used")
                .subAccountId("different-sub-account")
                .externalId("local-id")
                .build();

        gateway.quoteResponse = quote;
        gateway.ticketResponse = ticket;

        AveniaTransferResult result = service.createTransfer(quoteRequest, request);

        assertEquals("fresh-token", gateway.receivedTicketRequest.getQuoteToken());
        assertEquals("sub-1", gateway.receivedTicketRequest.getSubAccountId());
        assertEquals(ticketId, result.ticket().id());
    }

    @Test
    void doesNotCreateTicketWhenQuoteFails() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        AveniaIntegrationException failure = AveniaIntegrationException.communication("quote failed", null);
        gateway.quoteFailure = failure;

        assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void doesNotCreateTicketWhenQuoteHasNoToken() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse(null);

        assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void doesNotCreateTicketWhenQuoteContainsFeeInDifferentCurrency() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = new AveniaQuoteResponse(
                "token", "BRLA", "INTERNAL", BigDecimal.TEN,
                "USD", "SWIFT", BigDecimal.ONE, BigDecimal.ZERO,
                "BRLA", false, false,
                List.of(new AveniaAppliedFee("Out Fee", "desc", BigDecimal.ONE, "USD")),
                BigDecimal.TEN, "BRLAUSD");

        assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void rejectsQuoteWhenProviderDoesNotConfirmRequestedImmediateFeeSettlement() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        AveniaQuoteRequest request = quoteRequestWithImmediateFeeSettlement();
        gateway.quoteResponse = quoteResponse("token", "EOM");

        assertThrows(AveniaIntegrationException.class, () -> service.createQuote(request));
    }

    @Test
    void acceptsQuoteWhenProviderConfirmsRequestedImmediateFeeSettlement() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("token", "IMMEDIATE");

        AveniaQuoteResponse response = service.createQuote(quoteRequestWithImmediateFeeSettlement());

        assertEquals("IMMEDIATE", response.feeSettlement());
    }

    @Test
    void rejectsExternalIdAboveProviderLimitBeforeRequestingQuoteOrTicket() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        AveniaTicketRequest request = AveniaTicketRequest.builder()
                .externalId("x".repeat(69))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> service.createTransfer(quoteRequest(), request));

        assertEquals(0, gateway.quoteCalls);
        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void acceptsExternalIdAtProviderLimit() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("token");
        gateway.ticketResponse = new AveniaTicketResponse(
                UUID.randomUUID(), null, null, "PROCESSING", null, null);

        service.createTransfer(quoteRequest(), AveniaTicketRequest.builder()
                .externalId("x".repeat(68))
                .build());

        assertEquals("x".repeat(68), gateway.receivedTicketRequest.getExternalId());
        assertEquals(1, gateway.ticketCalls);
    }

    @Test
    void rejectsCustomDurationAboveTenMinutesForCurrencyConversion() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        AveniaTicketRequest request = AveniaTicketRequest.builder()
                .customDuration(601)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> service.createTransfer(quoteRequest(), request));

        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void rejectsMissingTransferRequests() {
        AveniaTransferService service = new AveniaTransferService(new FakeAveniaGateway());

        assertThrows(IllegalArgumentException.class,
                () -> service.createTransfer(null, AveniaTicketRequest.builder().build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTransfer(quoteRequest(), null));
    }

    @Test
    void acceptsLongerDurationWhenThereIsNoCurrencyConversion() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        AveniaQuoteRequest quoteRequest = new AveniaQuoteRequest(
                "USD", "INTERNAL", "usd", "SWIFT",
                BigDecimal.TEN, null, false, false, null,
                null, null, null, null, null, null, null);
        gateway.quoteResponse = new AveniaQuoteResponse(
                "same-currency-token", "USD", "INTERNAL", BigDecimal.TEN,
                "USD", "SWIFT", BigDecimal.TEN, BigDecimal.ZERO,
                "USD", false, false, List.of(), BigDecimal.ONE, "USDUSD");
        gateway.ticketResponse = new AveniaTicketResponse(
                UUID.randomUUID(), null, null, "UNPAID", null, null);

        AveniaTransferResult result = service.createTransfer(quoteRequest,
                AveniaTicketRequest.builder().customDuration(3600).build());

        assertEquals("same-currency-token", result.quote().quoteToken());
        assertEquals(1, gateway.ticketCalls);
    }

    @Test
    void rejectsNullAndBlankQuoteTokens() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);

        gateway.quoteResponse = null;
        assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        gateway.quoteResponse = quoteResponse(" ");
        assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void rejectsIncompleteQuoteAndTicketResponses() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = new AveniaQuoteResponse(
                "token", "BRLA", "INTERNAL", BigDecimal.TEN,
                "USD", "WIRE", null, BigDecimal.ZERO,
                "BRLA", false, false, List.of(), BigDecimal.TEN, "BRLAUSD");

        assertThrows(AveniaIntegrationException.class, () -> service.createQuote(quoteRequest()));

        gateway.quoteResponse = quoteResponse("valid-token");
        gateway.ticketResponse = new AveniaTicketResponse(null, null, null, null, null, null);
        assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));
    }

    private AveniaQuoteRequest quoteRequest() {
        return new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.TEN, null, false, false, "PERMIT",
                null, null, null, null, null, null, "sub-1");
    }

    private AveniaQuoteRequest quoteRequestWithImmediateFeeSettlement() {
        return new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.TEN, null, false, false, "PERMIT",
                null, null, null, null, null, null, "IMMEDIATE", "sub-1");
    }

    private AveniaQuoteResponse quoteResponse(String token) {
        return new AveniaQuoteResponse(
                token, "BRLA", "INTERNAL", BigDecimal.TEN,
                "USD", "SWIFT", BigDecimal.ONE, BigDecimal.ZERO,
                "BRLA", false, false, List.of(), BigDecimal.TEN, "BRLAUSD");
    }

    private AveniaQuoteResponse quoteResponse(String token, String feeSettlement) {
        return new AveniaQuoteResponse(
                token, "BRLA", "INTERNAL", BigDecimal.TEN,
                "USD", "SWIFT", BigDecimal.ONE, BigDecimal.ZERO,
                "BRLA", false, false, List.of(), BigDecimal.TEN, "BRLAUSD",
                feeSettlement);
    }

    private static class FakeAveniaGateway implements AveniaGateway {
        private AveniaQuoteResponse quoteResponse;
        private AveniaTicketResponse ticketResponse;
        private AveniaIntegrationException quoteFailure;
        private AveniaTicketRequest receivedTicketRequest;
        private int quoteCalls;
        private int ticketCalls;

        @Override
        public AveniaQuoteResponse createQuote(AveniaQuoteRequest request) {
            quoteCalls++;
            if (quoteFailure != null) {
                throw quoteFailure;
            }
            return quoteResponse;
        }

        @Override
        public AveniaTicketResponse createTicket(AveniaTicketRequest request) {
            ticketCalls++;
            receivedTicketRequest = request;
            return ticketResponse;
        }

        @Override
        public AveniaBeneficiaryResponse createBrlBeneficiary(AveniaBrlBeneficiaryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AveniaBeneficiaryResponse createUsdBeneficiary(AveniaUsdBeneficiaryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AveniaBeneficiaryResponse createEurBeneficiary(AveniaEurBeneficiaryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AveniaBeneficiaryResponse createSwiftBeneficiary(AveniaSwiftBeneficiaryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AveniaBeneficiaryResponse createWalletBeneficiary(AveniaWalletBeneficiaryRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
