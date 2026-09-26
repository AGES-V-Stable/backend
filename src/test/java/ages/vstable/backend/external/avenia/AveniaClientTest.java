package ages.vstable.backend.external.avenia;

import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AveniaClientTest {

    private MockRestServiceServer server;
    private AveniaClient client;
    private AveniaProperties properties;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() throws Exception {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        KeyPair keyPair = AveniaRequestSignerTest.rsaKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        properties = new AveniaProperties();
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
    void createsQuoteWithOptionalParametersAndDefaultThirdPartyFlags() {
        properties.setBaseUrl("https://avenia.test/");
        server.expect(once(), requestTo("https://avenia.test/v2/account/quote/fixed-rate"
                        + "?inputCurrency=BRL&inputPaymentMethod=PIX"
                        + "&outputCurrency=USDC&outputPaymentMethod=POLYGON"
                        + "&inputThirdParty=false&outputThirdParty=false"
                        + "&inputAmount=250.00"
                        + "&markupFloatingFee=0.01&markupInputFixedFee=2.00"
                        + "&markupOutputFixedFee=3.00&markupCurrency=USDC"
                        + "&ticketRefundId=refund-1&outputBrCode=br-code"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"quoteToken\":\"optional-quote\"}", MediaType.APPLICATION_JSON));

        AveniaQuoteResponse response = client.createQuote(new AveniaQuoteRequest(
                "BRL", "PIX", "USDC", "POLYGON",
                new BigDecimal("250.00"), null, null, null, " ",
                new BigDecimal("0.01"), new BigDecimal("2.00"), new BigDecimal("3.00"),
                "USDC", "refund-1", "br-code", " "));

        assertEquals("optional-quote", response.quoteToken());
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
    void createsTicketWithoutSubAccountAtMinimumDuration() {
        server.expect(once(), requestTo("https://avenia.test/v2/account/tickets/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"UNPAID\"}", MediaType.APPLICATION_JSON));

        AveniaTicketResponse response = client.createTicket(AveniaTicketRequest.builder()
                .quoteToken("quote-token")
                .customDuration(300)
                .build());

        assertEquals("UNPAID", response.status());
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
    void mapsPostProviderFailureWithEmptyBodyAsRetryable() {
        server.expect(requestTo("https://avenia.test/v2/account/tickets/"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AveniaIntegrationException exception = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createTicket(AveniaTicketRequest.builder()
                        .quoteToken("quote-token")
                        .build()));

        assertEquals(503, exception.getProviderStatus());
        assertTrue(exception.isRetryable());
        assertEquals("Could not create the Avenia ticket: HTTP 503", exception.getMessage());
    }

    @Test
    void hidesMalformedAndUnrecognizedProviderErrorBodies() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/account/quote/fixed-rate")))
                .andRespond(withBadRequest().body("not-json").contentType(MediaType.TEXT_PLAIN));

        AveniaIntegrationException malformed = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertEquals("Could not obtain a quote from Avenia: HTTP 400", malformed.getMessage());

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/account/quote/fixed-rate")))
                .andRespond(withBadRequest().body("{\"code\":\"INVALID\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        AveniaIntegrationException unrecognized = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertEquals("Could not obtain a quote from Avenia: HTTP 400", unrecognized.getMessage());
        server.verify();
    }

    @Test
    void mapsGetAndPostCommunicationFailuresAsRetryable() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/account/quote/fixed-rate")))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        AveniaIntegrationException quoteFailure = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertTrue(quoteFailure.isRetryable());
        assertNull(quoteFailure.getProviderStatus());

        server.reset();
        server.expect(requestTo("https://avenia.test/v2/account/tickets/"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        AveniaIntegrationException ticketFailure = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createTicket(AveniaTicketRequest.builder()
                        .quoteToken("quote-token")
                        .build()));
        assertTrue(ticketFailure.isRetryable());
        server.verify();
    }

    @Test
    void rejectsMissingClientConfiguration() {
        properties.setBaseUrl(" ");
        AveniaIntegrationException missingBaseUrl = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertEquals("AVENIA_BASE_URL is not configured", missingBaseUrl.getMessage());

        properties.setBaseUrl("https://avenia.test");
        properties.setApiKey(" ");
        AveniaIntegrationException missingApiKey = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertEquals("AVENIA_API_KEY is not configured", missingApiKey.getMessage());
    }

    @Test
    void rejectsMissingAndInvalidPrivateKeys() {
        properties.setPrivateKey(" ");
        AveniaIntegrationException missingKey = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertEquals("AVENIA_PRIVATE_KEY is not configured", missingKey.getMessage());

        properties.setPrivateKey("not-a-private-key");
        AveniaIntegrationException invalidKey = assertThrows(
                AveniaIntegrationException.class,
                () -> client.createQuote(quoteRequest()));
        assertEquals("AVENIA_PRIVATE_KEY must be a valid PKCS#8 RSA private key", invalidKey.getMessage());
    }

    @Test
    void mapsSigningAndSerializationFailuresAsConfigurationErrors() {
        AveniaRequestSigner failingSigner = new AveniaRequestSigner() {
            @Override
            public String sign(String timestamp, String method, String uri, String body,
                               java.security.PrivateKey privateKey) {
                throw new IllegalStateException("signing failed");
            }
        };
        AveniaClient signingClient = new AveniaClient(properties, failingSigner, new ObjectMapper(), builder);

        AveniaIntegrationException signingFailure = assertThrows(
                AveniaIntegrationException.class,
                () -> signingClient.createQuote(quoteRequest()));
        assertEquals("Could not sign the Avenia request", signingFailure.getMessage());

        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JacksonException {
                throw new JacksonException("serialization failed") {
                };
            }
        };
        AveniaClient serializationClient = new AveniaClient(
                properties, new AveniaRequestSigner(), failingMapper, builder);

        AveniaIntegrationException serializationFailure = assertThrows(
                AveniaIntegrationException.class,
                () -> serializationClient.createTicket(AveniaTicketRequest.builder()
                        .quoteToken("quote-token")
                        .build()));
        assertEquals("Could not serialize the Avenia request", serializationFailure.getMessage());
    }

    @Test
    void validatesQuoteAndTicketInputsBeforeCallingProvider() {
        assertThrows(IllegalArgumentException.class, () -> client.createQuote(null));
        assertThrows(IllegalArgumentException.class, () -> client.createTicket(null));
        assertThrows(IllegalArgumentException.class, () -> client.createTicket(
                AveniaTicketRequest.builder().quoteToken(" ").build()));
        assertThrows(IllegalArgumentException.class, () -> client.createTicket(
                AveniaTicketRequest.builder().quoteToken("token").customDuration(299).build()));
        assertThrows(IllegalArgumentException.class, () -> client.createTicket(
                AveniaTicketRequest.builder().quoteToken("token").customDuration(259201).build()));
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

    @Test
    void rejectsNonPositiveAmountsAndMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.ZERO, null, false, false, null,
                null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                null, BigDecimal.valueOf(-1), false, false, null,
                null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AveniaQuoteRequest(
                " ", "INTERNAL", "USD", "SWIFT",
                BigDecimal.ONE, null, false, false, null,
                null, null, null, null, null, null, null));
    }

    private AveniaQuoteRequest quoteRequest() {
        return new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                new BigDecimal("100.00"), null, false, false, "PERMIT",
                null, null, null, null, null, null, "sub-1");
    }
}
