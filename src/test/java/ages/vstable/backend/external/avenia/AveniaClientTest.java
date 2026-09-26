package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AveniaClientTest {

    private MockRestServiceServer server;
    private AveniaClient client;

    @BeforeEach
    void setUp() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        KeyPair keyPair = AveniaRequestSignerTest.rsaKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        AveniaProperties properties = new AveniaProperties();
        properties.setBaseUrl("https://avenia.test");
        properties.setApiKey("api-key");
        properties.setPrivateKey(pem);

        client = new AveniaClient(
                properties,
                new AveniaRequestSigner(),
                new ObjectMapper(),
                builder);
    }

    @Test
    void createsQuoteWithOnlyInputAmountAndMapsResponse() {
        server.expect(once(), requestTo("https://avenia.test/v2/account/quote/fixed-rate"
                        + "?inputCurrency=BRLA&inputPaymentMethod=INTERNAL"
                        + "&outputCurrency=USD&outputPaymentMethod=SWIFT"
                        + "&inputThirdParty=false&outputThirdParty=false"
                        + "&inputAmount=100.00&blockchainSendMethod=PERMIT"
                        + "&subAccountId=sub-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "api-key"))
                .andExpect(header("X-API-Timestamp", org.hamcrest.Matchers.matchesPattern("\\d{13}")))
                .andExpect(header("X-API-Signature", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankString())))
                .andRespond(withSuccess("""
                        {
                          "quoteToken": "quote-token",
                          "inputCurrency": "BRLA",
                          "inputAmount": "100.00",
                          "outputCurrency": "USD",
                          "outputAmount": "18.25",
                          "basePrice": "5.47",
                          "pairName": "BRLAUSD",
                          "appliedFees": [{"type":"Out Fee","amount":"1.25","currency":"BRLA"}]
                        }
                        """, MediaType.APPLICATION_JSON));

        AveniaQuoteResponse response = client.createQuote(quoteRequest());

        assertEquals("quote-token", response.quoteToken());
        assertEquals(new BigDecimal("18.25"), response.outputAmount());
        assertEquals(new BigDecimal("1.25"), response.appliedFees().getFirst().amount());
        server.verify();
    }

    @Test
    void createsQuoteWithOnlyOutputAmount() {
        server.expect(once(), requestTo("https://avenia.test/v2/account/quote/fixed-rate"
                        + "?inputCurrency=BRLA&inputPaymentMethod=INTERNAL"
                        + "&outputCurrency=USD&outputPaymentMethod=SWIFT"
                        + "&inputThirdParty=false&outputThirdParty=false"
                        + "&outputAmount=30.00&blockchainSendMethod=PERMIT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"quoteToken\":\"output-quote\"}", MediaType.APPLICATION_JSON));

        AveniaQuoteResponse response = client.createQuote(new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                null, new BigDecimal("30.00"), false, false, "PERMIT",
                null, null, null, null, null, null, null));

        assertEquals("output-quote", response.quoteToken());
        server.verify();
    }

    @Test
    void createsSwiftTicketWithQuoteTokenAndSameSubAccount() {
        UUID beneficiaryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        AveniaTicketRequest request = AveniaTicketRequest.builder()
                .quoteToken("quote-token")
                .externalId("local-transfer-id")
                .subAccountId("sub-1")
                .ticketSwiftOutput(new AveniaTicketRequest.SwiftOutput(beneficiaryId, documentId))
                .build();

        server.expect(once(), requestTo("https://avenia.test/v2/account/tickets/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "api-key"))
                .andExpect(content().json("""
                        {
                          "quoteToken":"quote-token",
                          "externalId":"local-transfer-id",
                          "ticketSwiftOutput":{
                            "beneficiarySwiftBankAccountId":"%s",
                            "uploadedDocumentId":"%s"
                          }
                        }
                        """.formatted(beneficiaryId, documentId), true))
                .andRespond(withSuccess("{\"id\":\"" + ticketId + "\",\"status\":\"PROCESSING\"}",
                        MediaType.APPLICATION_JSON));

        AveniaTicketResponse response = client.createTicket(request);

        assertEquals(ticketId, response.id());
        assertEquals("PROCESSING", response.status());
        server.verify();
    }

    @Test
    void mapsProviderValidationFailureWithoutLeakingWholeBody() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/account/quote/fixed-rate")))
                .andRespond(withBadRequest().body("""
                        {"error":"inputAmount is invalid","sensitive":"must-not-leak"}
                        """).contentType(MediaType.APPLICATION_JSON));

        AveniaIntegrationException exception = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));

        assertEquals(400, exception.getProviderStatus());
        assertFalse(exception.isRetryable());
        assertEquals("Could not obtain a quote from Avenia: HTTP 400 - inputAmount is invalid",
                exception.getMessage());
    }

    @Test
    void rejectsQuoteWithBothAmountsBeforeCallingAvenia() {
        assertThrows(IllegalArgumentException.class, () -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.TEN, BigDecimal.ONE, false, false, "PERMIT",
                null, null, null, null, null, null, null));
    }

    @Test
    void rejectsQuoteWithoutAnyAmountBeforeCallingAvenia() {
        assertThrows(IllegalArgumentException.class, () -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                null, null, false, false, "PERMIT",
                null, null, null, null, null, null, null));
    }

    private AveniaQuoteRequest quoteRequest() {
        return new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                new BigDecimal("100.00"), null, false, false, "PERMIT",
                null, null, null, null, null, null, "sub-1");
    }
}
