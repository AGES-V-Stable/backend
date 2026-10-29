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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
    void doesNotCreateTicketWhenPriceGuardRejectsFreshQuote() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("fresh-token");

        assertThrows(IllegalStateException.class, () -> service.createTransfer(
                quoteRequest(), AveniaTicketRequest.builder().build(),
                quote -> {
                    throw new IllegalStateException("price changed");
                }));

        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void priceGuardReceivesValidatedFreshQuoteBeforeTicket() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("fresh-token");
        gateway.ticketResponse = new AveniaTicketResponse(UUID.randomUUID(), null, null, "UNPAID", null, null);
        java.util.List<String> seen = new java.util.ArrayList<>();

        service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build(),
                quote -> seen.add(quote.quoteToken() + ":tickets=" + gateway.ticketCalls));

        assertEquals(java.util.List.of("fresh-token:tickets=0"), seen);
        assertEquals(1, gateway.ticketCalls);
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

    @Test
    void rejectsMissingQuoteRequestBeforeCallingProvider() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);

        assertThrows(IllegalArgumentException.class, () -> service.createQuote(null));

        assertEquals(0, gateway.quoteCalls);
    }

    @Test
    void rejectsNullTicketResponse() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("token");
        gateway.ticketResponse = null;

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals("Avenia returned an incomplete ticket", exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("incompleteQuotes")
    void rejectsQuoteWithMissingOrNonPositiveRequiredFieldWithoutCreatingTicket(AveniaQuoteResponse quote) {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote;

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals("Avenia returned an incomplete quote", exception.getMessage());
        assertEquals(0, gateway.ticketCalls);
    }

    static Stream<AveniaQuoteResponse> incompleteQuotes() {
        BigDecimal ten = BigDecimal.TEN;
        BigDecimal one = BigDecimal.ONE;
        return Stream.of(
                quote(null, "INTERNAL", ten, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote(" ", "INTERNAL", ten, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, null, "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, " ", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", null, ten, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", " ", ten, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, "USD", null, one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, "USD", " ", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", null, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", BigDecimal.ZERO, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, "USD", "SWIFT", one.negate(), null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, "USD", "SWIFT", one, null, null, List.of(), null),
                quote("BRLA", "INTERNAL", ten, "USD", "SWIFT", one, null, null, List.of(), BigDecimal.ZERO));
    }

    @ParameterizedTest
    @MethodSource("quotesForAnotherOperation")
    void rejectsQuoteThatDoesNotMatchRequestedCurrenciesAndMethods(AveniaQuoteResponse quote) {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote;

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals("Avenia returned a quote that does not match the request", exception.getMessage());
        assertEquals(0, gateway.ticketCalls);
    }

    static Stream<AveniaQuoteResponse> quotesForAnotherOperation() {
        BigDecimal ten = BigDecimal.TEN;
        BigDecimal one = BigDecimal.ONE;
        return Stream.of(
                quote("USDC", "INTERNAL", ten, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, "EUR", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "PIX", ten, "USD", "SWIFT", one, null, null, List.of(), ten),
                quote("BRLA", "INTERNAL", ten, "USD", "WIRE", one, null, null, List.of(), ten));
    }

    @Test
    void rejectsQuoteWithoutFeeSettlementWhenImmediateWasRequested() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("token", null);

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createQuote(quoteRequestWithImmediateFeeSettlement()));

        assertEquals("Avenia returned a quote with an unexpected fee settlement mode", exception.getMessage());
    }

    @Test
    void doesNotRequireFeeSettlementConfirmationWhenNoModeWasRequested() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quoteResponse("token", null);
        AveniaQuoteRequest blankFeeSettlement = new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.TEN, null, false, false, "PERMIT",
                null, null, null, null, null, null, " ", "sub-1");

        AveniaQuoteResponse response = service.createQuote(blankFeeSettlement);

        assertEquals("token", response.quoteToken());
    }

    @Test
    void acceptsAppliedFeesInInputCurrencyWithinInputAmount() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote("BRLA", "INTERNAL", BigDecimal.TEN, "USD", "SWIFT", BigDecimal.ONE,
                null, null,
                List.of(new AveniaAppliedFee("Markup Fee", "desc", new BigDecimal("1.50"), "BRLA"),
                        new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("0.50"), "brla")),
                BigDecimal.TEN);

        AveniaQuoteResponse response = service.createQuote(quoteRequest());

        assertEquals(2, response.appliedFees().size());
    }

    @ParameterizedTest
    @MethodSource("incompleteAppliedFees")
    void rejectsIncompleteAppliedFee(AveniaAppliedFee fee) {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote("BRLA", "INTERNAL", BigDecimal.TEN, "USD", "SWIFT", BigDecimal.ONE,
                null, null, Collections.singletonList(fee), BigDecimal.TEN);

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createQuote(quoteRequest()));

        assertEquals("Avenia returned an incomplete quote", exception.getMessage());
    }

    static Stream<AveniaAppliedFee> incompleteAppliedFees() {
        return Stream.of(
                null,
                new AveniaAppliedFee("Out Fee", "desc", null, "BRLA"),
                new AveniaAppliedFee("Out Fee", "desc", BigDecimal.ONE.negate(), "BRLA"),
                new AveniaAppliedFee("Out Fee", "desc", BigDecimal.ONE, null),
                new AveniaAppliedFee("Out Fee", "desc", BigDecimal.ONE, " "));
    }

    @Test
    void rejectsAppliedFeesAboveInputAmount() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote("BRLA", "INTERNAL", BigDecimal.TEN, "USD", "SWIFT", BigDecimal.ONE,
                null, null,
                List.of(new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("10.01"), "BRLA")),
                BigDecimal.TEN);

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createTransfer(quoteRequest(), AveniaTicketRequest.builder().build()));

        assertEquals("Avenia returned inconsistent financial data in the quote", exception.getMessage());
        assertEquals(0, gateway.ticketCalls);
    }

    @Test
    void acceptsMarkupInInputCurrencyWhenThereAreNoAppliedFees() {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote("BRLA", "INTERNAL", BigDecimal.TEN, "USD", "SWIFT", BigDecimal.ONE,
                new BigDecimal("0.75"), "BRLA", null, BigDecimal.TEN);

        AveniaQuoteResponse response = service.createQuote(quoteRequest());

        assertEquals(new BigDecimal("0.75"), response.markupAmount());
    }

    @ParameterizedTest
    @MethodSource("invalidMarkups")
    void rejectsInvalidMarkupWhenThereAreNoAppliedFees(
            BigDecimal markupAmount, String markupCurrency, String expectedMessage) {
        FakeAveniaGateway gateway = new FakeAveniaGateway();
        AveniaTransferService service = new AveniaTransferService(gateway);
        gateway.quoteResponse = quote("BRLA", "INTERNAL", BigDecimal.TEN, "USD", "SWIFT", BigDecimal.ONE,
                markupAmount, markupCurrency, List.of(), BigDecimal.TEN);

        AveniaIntegrationException exception = assertThrows(AveniaIntegrationException.class,
                () -> service.createQuote(quoteRequest()));

        assertEquals(expectedMessage, exception.getMessage());
    }

    static Stream<Arguments> invalidMarkups() {
        return Stream.of(
                Arguments.of(BigDecimal.ONE.negate(), "BRLA", "Avenia returned an incomplete quote"),
                Arguments.of(BigDecimal.ONE, null, "Avenia returned an incomplete quote"),
                Arguments.of(BigDecimal.ONE, " ", "Avenia returned an incomplete quote"),
                Arguments.of(BigDecimal.ONE, "USD",
                        "Avenia returned fees in a currency different from the input currency"),
                Arguments.of(new BigDecimal("10.01"), "BRLA",
                        "Avenia returned inconsistent financial data in the quote"));
    }

    private static AveniaQuoteResponse quote(
            String inputCurrency, String inputPaymentMethod, BigDecimal inputAmount,
            String outputCurrency, String outputPaymentMethod, BigDecimal outputAmount,
            BigDecimal markupAmount, String markupCurrency, List<AveniaAppliedFee> appliedFees,
            BigDecimal basePrice) {
        return new AveniaQuoteResponse(
                "token", inputCurrency, inputPaymentMethod, inputAmount,
                outputCurrency, outputPaymentMethod, outputAmount, markupAmount, markupCurrency,
                false, false, appliedFees, basePrice, "BRLAUSD");
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
