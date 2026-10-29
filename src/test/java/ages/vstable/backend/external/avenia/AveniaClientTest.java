package ages.vstable.backend.external.avenia;

import ages.vstable.backend.dto.compliance.KycSubmitRequest;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaBrlBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentStatusResponse;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentUploadResponse;
import ages.vstable.backend.external.avenia.dto.AveniaEurBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttempt;
import ages.vstable.backend.external.avenia.dto.AveniaKycAttemptsResponse;
import ages.vstable.backend.external.avenia.dto.AveniaKycResponse;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaSubAccountResponse;
import ages.vstable.backend.external.avenia.dto.AveniaSwiftBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaUsdBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;
import ages.vstable.backend.logging.LogCapture;
import ages.vstable.backend.logging.OperationalLog;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.blankString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
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
        properties.setApiKey("minha-api-key");
        properties.setPrivateKey(pem);

        client = new AveniaClient(
                properties,
                new AveniaRequestSigner(),
                new ObjectMapper(),
                builder);
    }

    // ---------------------------------------------------------------------
    // Quote / Ticket
    // ---------------------------------------------------------------------

    @Test
    void createsQuoteWithOnlyInputAmountAndMapsResponse() {
        server.expect(once(), requestTo("https://avenia.test/v2/account/quote/fixed-rate"
                        + "?inputCurrency=BRLA&inputPaymentMethod=INTERNAL"
                        + "&outputCurrency=USD&outputPaymentMethod=SWIFT"
                        + "&inputThirdParty=false&outputThirdParty=false"
                        + "&inputAmount=100.00&blockchainSendMethod=PERMIT"
                        + "&subAccountId=sub-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "minha-api-key"))
                .andExpect(header("X-API-Timestamp", matchesPattern("\\d{13}")))
                .andExpect(header("X-API-Signature", not(blankString())))
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

        assertThat(response.quoteToken()).isEqualTo("quote-token");
        assertThat(response.outputAmount()).isEqualTo(new BigDecimal("18.25"));
        assertThat(response.appliedFees().getFirst().amount()).isEqualTo(new BigDecimal("1.25"));
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

        assertThat(response.quoteToken()).isEqualTo("output-quote");
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

        assertThat(response.quoteToken()).isEqualTo("optional-quote");
        server.verify();
    }

    @Test
    void sendsImmediateFeeSettlementAndMapsProviderConfirmation() {
        server.expect(once(), requestTo("https://avenia.test/v2/account/quote/fixed-rate"
                        + "?inputCurrency=BRL&inputPaymentMethod=PIX"
                        + "&outputCurrency=USD&outputPaymentMethod=WIRE"
                        + "&inputThirdParty=false&outputThirdParty=false"
                        + "&inputAmount=100.00&feeSettlement=IMMEDIATE"
                        + "&subAccountId=sub-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "quoteToken":"quote-token",
                          "feeSettlement":"IMMEDIATE"
                        }
                        """, MediaType.APPLICATION_JSON));

        AveniaQuoteResponse response = client.createQuote(new AveniaQuoteRequest(
                "BRL", "PIX", "USD", "WIRE",
                new BigDecimal("100.00"), null, null, null, null,
                null, null, null, null, null, null, "IMMEDIATE", "sub-1"));

        assertThat(response.feeSettlement()).isEqualTo("IMMEDIATE");
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
                .andExpect(header("X-API-Key", "minha-api-key"))
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

        assertThat(response.id()).isEqualTo(ticketId);
        assertThat(response.status()).isEqualTo("PROCESSING");
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

        assertThat(response.status()).isEqualTo("UNPAID");
        server.verify();
    }

    @Test
    void createsPixBeneficiaryInSubAccountWithoutSerializingSubAccountId() {
        UUID beneficiaryId = UUID.randomUUID();
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/bank-accounts/brl/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "minha-api-key"))
                .andExpect(content().json("""
                        {
                          "alias":"Fornecedor PIX",
                          "description":"Conta principal",
                          "pixKey":"pix@example.com"
                        }
                        """, true))
                .andRespond(withSuccess("{\"id\":\"" + beneficiaryId + "\"}", MediaType.APPLICATION_JSON));

        AveniaBeneficiaryResponse response = client.createBrlBeneficiary(new AveniaBrlBeneficiaryRequest(
                "Fornecedor PIX", "Conta principal", "pix@example.com",
                null, null, null, null, null, null, "sub-1"));

        assertThat(response.id()).isEqualTo(beneficiaryId);
        server.verify();
    }

    @Test
    void createsWalletBeneficiaryAndRejectsResponseWithoutId() {
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/wallets/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "alias":"Wallet",
                          "walletAddress":"0xabc",
                          "walletChain":"POLYGON"
                        }
                        """, true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createWalletBeneficiary(new AveniaWalletBeneficiaryRequest(
                "Wallet", null, "0xabc", "POLYGON", null, "sub-1")))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessage("Avenia returned a beneficiary without id");
        server.verify();
    }

    @Test
    void createsUsdBeneficiaryInSubAccountWithAddress() {
        UUID beneficiaryId = UUID.randomUUID();
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/bank-accounts/usd/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "alias":"Supplier USD",
                          "bankAccountNumber":"123456",
                          "bankRoutingNumber":"021000021",
                          "bankBeneficiaryName":"Supplier Inc",
                          "bankName":"Test Bank",
                          "beneficiaryAddress":{
                            "streetLine1":"123 Main St",
                            "city":"New York",
                            "state":"NY",
                            "postalCode":"10001",
                            "country":"USA"
                          }
                        }
                        """, true))
                .andRespond(withSuccess("{\"id\":\"" + beneficiaryId + "\"}", MediaType.APPLICATION_JSON));

        AveniaBeneficiaryResponse response = client.createUsdBeneficiary(new AveniaUsdBeneficiaryRequest(
                "Supplier USD", null, "123456", "021000021", "Supplier Inc", "Test Bank",
                new AveniaUsdBeneficiaryRequest.Address("123 Main St", null, "New York", "NY", "10001", "USA"),
                "sub-1"));

        assertThat(response.id()).isEqualTo(beneficiaryId);
        server.verify();
    }

    @Test
    void createsEurBeneficiaryInSubAccount() {
        UUID beneficiaryId = UUID.randomUUID();
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/bank-accounts/eur/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "alias":"Supplier EUR",
                          "iban":"FR1420041010050500013M02606",
                          "bic":"COBADEFFXXX",
                          "country":"FRA",
                          "bankBeneficiaryName":"Supplier SARL"
                        }
                        """, true))
                .andRespond(withSuccess("{\"id\":\"" + beneficiaryId + "\"}", MediaType.APPLICATION_JSON));

        AveniaBeneficiaryResponse response = client.createEurBeneficiary(new AveniaEurBeneficiaryRequest(
                "Supplier EUR", null, "FR1420041010050500013M02606", "COBADEFFXXX", "FRA",
                "Supplier SARL", "sub-1"));

        assertThat(response.id()).isEqualTo(beneficiaryId);
        server.verify();
    }

    @Test
    void createsSwiftBeneficiaryInSubAccount() {
        UUID beneficiaryId = UUID.randomUUID();
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/bank-accounts/swift/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "alias":"Supplier GBP",
                          "beneficiaryName":"Supplier Ltd",
                          "beneficiaryType":"company",
                          "accountStandard":"iban",
                          "accountValue":"GB29NWBK60161331926819",
                          "bic":"NWBKGB2L",
                          "currency":"GBP"
                        }
                        """, false))
                .andRespond(withSuccess("{\"id\":\"" + beneficiaryId + "\"}", MediaType.APPLICATION_JSON));

        AveniaBeneficiaryResponse response = client.createSwiftBeneficiary(new AveniaSwiftBeneficiaryRequest(
                "Supplier GBP", null, "Supplier Ltd", "company", "iban", "GB29NWBK60161331926819",
                "NWBKGB2L", "NatWest", "GB", "1 King Street", "London", "England", "EC2V 8AU", "GB",
                "GBP", null, null, "sub-1"));

        assertThat(response.id()).isEqualTo(beneficiaryId);
        server.verify();
    }

    @Test
    void createsWalletBeneficiaryAndReturnsProviderId() {
        UUID walletId = UUID.randomUUID();
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/wallets/?subAccountId=sub-1"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"" + walletId + "\"}", MediaType.APPLICATION_JSON));

        AveniaBeneficiaryResponse response = client.createWalletBeneficiary(new AveniaWalletBeneficiaryRequest(
                "Wallet", null, "0xabc", "POLYGON", null, "sub-1"));

        assertThat(response.id()).isEqualTo(walletId);
        server.verify();
    }

    @Test
    void rejectsBeneficiaryResponseWithoutBody() {
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/account/beneficiaries/bank-accounts/brl/?subAccountId=sub-1"))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> client.createBrlBeneficiary(new AveniaBrlBeneficiaryRequest(
                "Fornecedor PIX", null, "pix@example.com", null, null, null, null, null, null, "sub-1")))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessage("Avenia returned a beneficiary without id");
        server.verify();
    }

    @Test
    void rejectsMissingBeneficiaryRequestsBeforeCallingProvider() {
        assertThatThrownBy(() -> client.createBrlBeneficiary(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("beneficiary request must be provided");
        assertThatThrownBy(() -> client.createUsdBeneficiary(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createEurBeneficiary(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createSwiftBeneficiary(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createWalletBeneficiary(null))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void mapsProviderValidationFailureWithoutLeakingWholeBody() {
        server.expect(requestTo(containsString("/v2/account/quote/fixed-rate")))
                .andRespond(withBadRequest().body("""
                        {"error":"inputAmount is invalid","sensitive":"must-not-leak"}
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .isInstanceOf(AveniaIntegrationException.class)
                .satisfies(e -> {
                    AveniaIntegrationException ex = (AveniaIntegrationException) e;
                    assertThat(ex.getProviderStatus()).isEqualTo(400);
                    assertThat(ex.isRetryable()).isFalse();
                })
                .hasMessage("Could not obtain a quote from Avenia: HTTP 400 - inputAmount is invalid");
    }

    @Test
    void mapsPostProviderFailureWithEmptyBodyAsRetryable() {
        server.expect(requestTo("https://avenia.test/v2/account/tickets/"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.createTicket(AveniaTicketRequest.builder()
                        .quoteToken("quote-token")
                        .build()))
                .isInstanceOf(AveniaIntegrationException.class)
                .satisfies(e -> {
                    AveniaIntegrationException ex = (AveniaIntegrationException) e;
                    assertThat(ex.getProviderStatus()).isEqualTo(503);
                    assertThat(ex.isRetryable()).isTrue();
                })
                .hasMessage("Could not create the Avenia ticket: HTTP 503");
    }

    @Test
    void hidesMalformedAndUnrecognizedProviderErrorBodies() {
        server.expect(requestTo(containsString("/v2/account/quote/fixed-rate")))
                .andRespond(withBadRequest().body("not-json").contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .hasMessage("Could not obtain a quote from Avenia: HTTP 400");

        server.reset();
        server.expect(requestTo(containsString("/v2/account/quote/fixed-rate")))
                .andRespond(withBadRequest().body("{\"code\":\"INVALID\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .hasMessage("Could not obtain a quote from Avenia: HTTP 400");
        server.verify();
    }

    @Test
    void mapsGetAndPostCommunicationFailuresAsRetryable() {
        server.expect(requestTo(containsString("/v2/account/quote/fixed-rate")))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .isInstanceOf(AveniaIntegrationException.class)
                .satisfies(e -> {
                    AveniaIntegrationException ex = (AveniaIntegrationException) e;
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.getProviderStatus()).isNull();
                });

        server.reset();
        server.expect(requestTo("https://avenia.test/v2/account/tickets/"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.createTicket(AveniaTicketRequest.builder()
                        .quoteToken("quote-token")
                        .build()))
                .isInstanceOf(AveniaIntegrationException.class)
                .satisfies(e -> assertThat(((AveniaIntegrationException) e).isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void rejectsMissingClientConfiguration() {
        properties.setBaseUrl(" ");
        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .hasMessage("AVENIA_BASE_URL is not configured");

        properties.setBaseUrl("https://avenia.test");
        properties.setApiKey(" ");
        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .hasMessage("AVENIA_API_KEY is not configured");
    }

    @Test
    void rejectsMissingAndInvalidPrivateKeys() {
        properties.setPrivateKey(" ");
        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .hasMessage("AVENIA_PRIVATE_KEY is not configured");

        properties.setPrivateKey("not-a-private-key");
        assertThatThrownBy(() -> client.createQuote(quoteRequest()))
                .hasMessage("AVENIA_PRIVATE_KEY must be a valid PKCS#8 RSA private key");
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

        assertThatThrownBy(() -> signingClient.createQuote(quoteRequest()))
                .hasMessage("Could not sign the Avenia request");

        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JacksonException {
                throw new JacksonException("serialization failed") {
                };
            }
        };
        AveniaClient serializationClient = new AveniaClient(
                properties, new AveniaRequestSigner(), failingMapper, builder);

        assertThatThrownBy(() -> serializationClient.createTicket(AveniaTicketRequest.builder()
                        .quoteToken("quote-token")
                        .build()))
                .hasMessage("Could not serialize the Avenia request");
    }

    @Test
    void validatesQuoteAndTicketInputsBeforeCallingProvider() {
        assertThatThrownBy(() -> client.createQuote(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createTicket(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createTicket(
                AveniaTicketRequest.builder().quoteToken(" ").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createTicket(
                AveniaTicketRequest.builder().quoteToken("token").customDuration(299).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createTicket(
                AveniaTicketRequest.builder().quoteToken("token").customDuration(259201).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createTicket(
                AveniaTicketRequest.builder()
                        .quoteToken("token")
                        .externalId("x".repeat(69))
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("68");
    }

    @Test
    void rejectsQuoteWithBothAmountsBeforeCallingAvenia() {
        assertThatThrownBy(() -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.TEN, BigDecimal.ONE, false, false, "PERMIT",
                null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsQuoteWithoutAnyAmountBeforeCallingAvenia() {
        assertThatThrownBy(() -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                null, null, false, false, "PERMIT",
                null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveAmountsAndMissingRequiredFields() {
        assertThatThrownBy(() -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                BigDecimal.ZERO, null, false, false, null,
                null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                null, BigDecimal.valueOf(-1), false, false, null,
                null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AveniaQuoteRequest(
                " ", "INTERNAL", "USD", "SWIFT",
                BigDecimal.ONE, null, false, false, null,
                null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AveniaQuoteRequest quoteRequest() {
        return new AveniaQuoteRequest(
                "BRLA", "INTERNAL", "USD", "SWIFT",
                new BigDecimal("100.00"), null, false, false, "PERMIT",
                null, null, null, null, null, null, "sub-1");
    }

    // ---------------------------------------------------------------------
    // KYC / Liveness / Documento / Subconta
    // ---------------------------------------------------------------------

    // Shape real da resposta da Avenia sandbox, confirmado em teste manual em 2026-09-09
    @Test
    void iniciarLiveness_respostaComSucesso_retornaCamposEEnviaHeadersDeAssinatura() {
        server.expect(once(), requestTo("https://avenia.test/v2/documents/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "minha-api-key"))
                .andExpect(content().string("{\"documentType\":\"SELFIE-FROM-LIVENESS\"}"))
                .andRespond(withSuccess("""
                        {"id":"liveness-123","sessionId":"session-456",
                         "livenessUrl":"https://app.sandbox.avenia.io/liveness/session-456?jwt=abc",
                         "validateLivenessToken":"token-789"}
                        """, MediaType.APPLICATION_JSON));

        AveniaDocumentResponse response = client.iniciarLiveness(null);

        assertThat(response.getId()).isEqualTo("liveness-123");
        assertThat(response.getSessionId()).isEqualTo("session-456");
        assertThat(response.getLivenessUrl()).isEqualTo("https://app.sandbox.avenia.io/liveness/session-456?jwt=abc");
        assertThat(response.getValidateLivenessToken()).isEqualTo("token-789");
        server.verify();
    }

    @Test
    void iniciarLiveness_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo("https://avenia.test/v2/documents/"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.iniciarLiveness(null))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void iniciarLiveness_semChavePrivadaConfigurada_lancaAveniaIntegrationException() {
        properties.setPrivateKey("");

        assertThatThrownBy(() -> client.iniciarLiveness(null))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessage("AVENIA_PRIVATE_KEY is not configured");
    }

    // Confirmado contra o sandbox real em 2026-09-23: passar ?subAccountId= na URI
    // faz a Avenia operar sobre a subconta em vez da conta principal da API key.
    @Test
    void iniciarLiveness_comSubAccountId_anexaComoQueryParamNaUri() {
        server.expect(once(), requestTo("https://avenia.test/v2/documents/?subAccountId=sub-123"))
                .andRespond(withSuccess("""
                        {"id":"liveness-123","sessionId":"session-456",
                         "livenessUrl":"https://app.sandbox.avenia.io/liveness/session-456?jwt=abc",
                         "validateLivenessToken":"token-789"}
                        """, MediaType.APPLICATION_JSON));

        client.iniciarLiveness("sub-123");

        server.verify();
    }

    // Shape real confirmado em 2026-09-21 contra o sandbox: POST /v2/documents/
    // com documentType ID devolve "uploadURLFront"/"uploadURLBack" (URL maiúsculo).
    @Test
    void iniciarDocumento_respostaComSucesso_retornaCamposEEnviaBodyComDocumentType() {
        server.expect(once(), requestTo("https://avenia.test/v2/documents/"))
                .andExpect(content().string("{\"documentType\":\"ID\",\"isDoubleSided\":true}"))
                .andRespond(withSuccess(
                        "{\"id\":\"doc-123\",\"uploadURLFront\":\"https://s3/front\",\"uploadURLBack\":\"https://s3/back\"}",
                        MediaType.APPLICATION_JSON));

        AveniaDocumentUploadResponse response = client.iniciarDocumento("ID", true, null);

        assertThat(response.getId()).isEqualTo("doc-123");
        assertThat(response.getUploadUrlFront()).isEqualTo("https://s3/front");
        assertThat(response.getUploadUrlBack()).isEqualTo("https://s3/back");
        server.verify();
    }

    @Test
    void iniciarDocumento_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo("https://avenia.test/v2/documents/"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.iniciarDocumento("PASSPORT", false, null))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    // Shape real confirmado em 2026-09-16 contra o sandbox: GET /v2/documents/{id}
    @Test
    void consultarStatusDocumento_respostaComSucesso_retornaDocumentoComReadyEStatus() {
        server.expect(once(), requestTo("https://avenia.test/v2/documents/doc-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"document":{"id":"doc-123","documentType":"SELFIE-FROM-LIVENESS",
                         "uploadStatusFront":"WAITING-UPLOAD","uploadErrorFront":"",
                         "uploadStatusBack":"","uploadErrorBack":"","ready":false}}
                        """, MediaType.APPLICATION_JSON));

        AveniaDocumentStatusResponse response = client.consultarStatusDocumento("doc-123", null);

        assertThat(response.getDocument().getId()).isEqualTo("doc-123");
        assertThat(response.getDocument().isReady()).isFalse();
        assertThat(response.getDocument().getUploadStatusFront()).isEqualTo("WAITING-UPLOAD");
        server.verify();
    }

    @Test
    void consultarStatusDocumento_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo("https://avenia.test/v2/documents/doc-123"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.consultarStatusDocumento("doc-123", null))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    private KycSubmitRequest kycRequest() {
        KycSubmitRequest request = new KycSubmitRequest();
        request.setFullName("Maria da Silva");
        request.setDateOfBirth("1990-05-20");
        request.setTaxIdNumber("52998224725");
        request.setEmail("maria@empresa.com");
        request.setPhone("11987654321");
        request.setCountry("Brasil");
        request.setState("SP");
        request.setCity("São Paulo");
        request.setZipCode("90000000");
        request.setStreetAddress("Rua Teste, 100");
        return request;
    }

    // Shape NÃO confirmado contra o sandbox real
    @Test
    void finalizarKyc_respostaComSucesso_retornaIdEEnviaBodyComDadosPessoaisEIds() {
        server.expect(once(), requestTo("https://avenia.test/v2/kyc/new-level-1/api"))
                .andExpect(content().string(containsString("\"fullName\":\"Maria da Silva\"")))
                .andExpect(content().string(containsString("\"countryOfTaxId\":\"BR\"")))
                .andExpect(content().string(containsString("\"taxIdNumber\":\"52998224725\"")))
                .andExpect(content().string(containsString("\"uploadedDocumentId\":\"doc-123\"")))
                .andExpect(content().string(containsString("\"uploadedSelfieId\":\"liveness-123\"")))
                // Confirmado contra o sandbox real: a Avenia exige código ISO no "country" e
                // rejeita "Brasil" por extenso com "InvalidFieldError: country is invalid".
                .andExpect(content().string(containsString("\"country\":\"BR\"")))
                .andRespond(withSuccess("{\"id\":\"kyc-process-123\"}", MediaType.APPLICATION_JSON));

        AveniaKycResponse response = client.finalizarKyc(kycRequest(), "doc-123", "liveness-123", null);

        assertThat(response.getId()).isEqualTo("kyc-process-123");
        server.verify();
    }

    // Confirmado contra o sandbox real (400 "InvalidFieldError: country is invalid" ao
    // enviar "Brasil" por extenso), daí a normalização para código ISO antes do envio.
    @Test
    void finalizarKyc_paisNaoSuportado_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        KycSubmitRequest request = kycRequest();
        request.setCountry("Argentina");

        assertThatThrownBy(() -> client.finalizarKyc(request, "doc-123", "liveness-123", null))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void finalizarKyc_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo("https://avenia.test/v2/kyc/new-level-1/api"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.finalizarKyc(kycRequest(), "doc-123", "liveness-123", null))
                .isInstanceOf(AveniaIntegrationException.class)
                .satisfies(e -> {
                    AveniaIntegrationException ex = (AveniaIntegrationException) e;
                    assertThat(ex.getProviderStatus()).isEqualTo(500);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    void finalizarKyc_comSubAccountId_anexaComoQueryParamNaUri() {
        server.expect(once(), requestTo("https://avenia.test/v2/kyc/new-level-1/api?subAccountId=sub-123"))
                .andRespond(withSuccess("{\"id\":\"kyc-process-123\"}", MediaType.APPLICATION_JSON));

        client.finalizarKyc(kycRequest(), "doc-123", "liveness-123", "sub-123");

        server.verify();
    }

    // Restaura o comportamento perdido num diff não commitado: o corpo completo do
    // erro não é propagado (pode conter dados de KYC), só status + mensagem de validação.
    @Test
    void finalizarKyc_aveniaRetornaMensagemDeValidacao_propagaSomenteStatusEMensagem() {
        server.expect(requestTo("https://avenia.test/v2/kyc/new-level-1/api"))
                .andRespond(withBadRequest()
                        .body("{\"message\":\"country is invalid\",\"sensitiveData\":\"nao-propagar\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.finalizarKyc(kycRequest(), "doc-123", "liveness-123", null))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("HTTP 400 - country is invalid")
                .hasMessageNotContaining("sensitiveData")
                .hasMessageNotContaining("nao-propagar")
                .satisfies(e -> {
                    AveniaIntegrationException ex = (AveniaIntegrationException) e;
                    assertThat(ex.getProviderStatus()).isEqualTo(400);
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    // Confirmado contra o sandbox real em 2026-09-23: POST /v2/account/sub-accounts
    @Test
    void criarSubconta_respostaComSucesso_retornaIdEEnviaAccountTypeIndividual() {
        server.expect(once(), requestTo("https://avenia.test/v2/account/sub-accounts"))
                .andExpect(content().string(containsString("\"accountType\":\"INDIVIDUAL\"")))
                .andExpect(content().string(containsString("\"name\":\"kyc-abc\"")))
                .andRespond(withSuccess("{\"id\":\"sub-123\"}", MediaType.APPLICATION_JSON));

        AveniaSubAccountResponse response = client.criarSubconta("kyc-abc");

        assertThat(response.getId()).isEqualTo("sub-123");
        server.verify();
    }

    @Test
    void criarSubconta_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo("https://avenia.test/v2/account/sub-accounts"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.criarSubconta("kyc-abc"))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    // Confirmado contra o sandbox real em 2026-09-23: GET /v2/kyc/attempts/?levelName=level-1&subAccountId=...
    @Test
    void listarTentativasKyc_respostaComSucesso_retornaListaDeAttempts() {
        server.expect(once(), requestTo(
                        "https://avenia.test/v2/kyc/attempts/?levelName=level-1&subAccountId=sub-123"))
                .andRespond(withSuccess("""
                        {"attempts":[{"id":"attempt-1","status":"COMPLETED",
                         "result":"APPROVED","resultMessage":"","rejectionLabels":[],"retryable":false}],
                         "cursor":"abc"}
                        """, MediaType.APPLICATION_JSON));

        AveniaKycAttemptsResponse response = client.listarTentativasKyc("sub-123");

        assertThat(response.getAttempts()).hasSize(1);
        AveniaKycAttempt attempt = response.getAttempts().get(0);
        assertThat(attempt.getId()).isEqualTo("attempt-1");
        assertThat(attempt.getStatus()).isEqualTo("COMPLETED");
        assertThat(attempt.getResult()).isEqualTo("APPROVED");
        assertThat(attempt.isRetryable()).isFalse();
        server.verify();
    }

    @Test
    void listarTentativasKyc_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo(containsString("/v2/kyc/attempts/")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.listarTentativasKyc("sub-123"))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    // Confirmado contra o sandbox real em 2026-09-23: GET /v2/kyc/attempts/{id}
    @Test
    void consultarTentativa_respostaComSucesso_retornaAttemptComSubAccountIdNaUri() {
        server.expect(once(), requestTo("https://avenia.test/v2/kyc/attempts/attempt-1?subAccountId=sub-123"))
                .andRespond(withSuccess(
                        "{\"attempt\":{\"id\":\"attempt-1\",\"status\":\"PENDING\",\"retryable\":false}}",
                        MediaType.APPLICATION_JSON));

        AveniaKycAttempt attempt = client.consultarTentativa("attempt-1", "sub-123");

        assertThat(attempt.getId()).isEqualTo("attempt-1");
        assertThat(attempt.getStatus()).isEqualTo("PENDING");
        server.verify();
    }

    @Test
    void consultarTentativa_servidorRetornaErro_lancaAveniaIntegrationException() {
        server.expect(requestTo(containsString("/v2/kyc/attempts/attempt-1")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("erro interno"));

        assertThatThrownBy(() -> client.consultarTentativa("attempt-1", "sub-123"))
                .isInstanceOf(AveniaIntegrationException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"success", "http_failure", "timeout", "communication_failure"})
    void providerLogsAreCorrelatedAndExcludePayloadsAndSecrets(String outcome) {
        var expectation = server.expect(requestTo("https://avenia.test/v2/documents/private-id"));
        switch (outcome) {
            case "success" -> expectation.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            case "http_failure" -> expectation.andRespond(withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"synthetic-private-payload\",\"code\":\"synthetic-secret\"}"));
            case "timeout" -> expectation.andRespond(request -> {
                throw new java.net.SocketTimeoutException("synthetic-private-payload");
            });
            default -> expectation.andRespond(request -> {
                throw new IOException("synthetic-private-payload");
            });
        }
        MDC.put("requestId", "provider-request");
        try (var capture = new LogCapture(
                OperationalLog.class)) {
            if (outcome.equals("success")) {
                client.consultarStatusDocumento("private-id", null);
            } else {
                assertThatThrownBy(() -> client.consultarStatusDocumento("private-id", null)).isInstanceOf(AveniaIntegrationException.class);
            }
            assertThat(capture.events()).hasSize(1);
            var event = capture.events().getFirst();
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "provider-request");
            assertThat(LogCapture.fields(event))
                    .containsEntry("provider", "avenia").containsEntry("outcome", outcome).containsKey("durationMs");
            assertThat(event.getLevel()).isEqualTo(outcome.equals("success")
                    ? Level.INFO : Level.WARN);
            assertThat(LogCapture.fields(event).toString())
                    .doesNotContain("synthetic-private-payload", "synthetic-secret", "private-id", "minha-api-key", "test-api-key");
            if (outcome.equals("success") || outcome.equals("http_failure")) {
                assertThat(LogCapture.fields(event))
                        .containsEntry("status", outcome.equals("success") ? 200 : 400);
            }
            server.verify();
        } finally {
            MDC.clear();
        }
    }

}
