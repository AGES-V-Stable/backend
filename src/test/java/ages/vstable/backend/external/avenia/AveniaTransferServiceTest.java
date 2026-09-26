package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class AveniaTransferServiceTest {

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

    private AveniaQuoteRequest quoteRequest() {
        return new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.TEN, null, false, false, "PERMIT",
                null, null, null, null, null, null, "sub-1");
    }

    private AveniaQuoteResponse quoteResponse(String token) {
        return new AveniaQuoteResponse(
                token, "BRLA", "INTERNAL", BigDecimal.TEN,
                "USD", "SWIFT", BigDecimal.ONE, BigDecimal.ZERO,
                "BRLA", false, false, List.of(), BigDecimal.TEN, "BRLAUSD");
    }

    private static class FakeAveniaGateway implements AveniaGateway {
        private AveniaQuoteResponse quoteResponse;
        private AveniaTicketResponse ticketResponse;
        private AveniaIntegrationException quoteFailure;
        private AveniaTicketRequest receivedTicketRequest;
        private int ticketCalls;

        @Override
        public AveniaQuoteResponse createQuote(AveniaQuoteRequest request) {
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
    }
}
