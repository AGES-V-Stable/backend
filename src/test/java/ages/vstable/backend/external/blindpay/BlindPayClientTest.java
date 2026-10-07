package ages.vstable.backend.external.blindpay;

import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.external.blindpay.dto.BlindPayBankAccountResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateBankAccountRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateWalletRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayEvmPayoutRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayWalletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlindPayClientTest {

    private static final String BASE_URL = "https://blindpay.test";
    private static final String INSTANCE_ID = "in_000000000001";
    private static final String INSTANCE_URL = BASE_URL + "/v1/instances/" + INSTANCE_ID;
    private static final String API_KEY = "test-api-key";

    private MockRestServiceServer server;
    private BlindPayProperties properties;
    private BlindPayClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        properties = new BlindPayProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey(API_KEY);
        properties.setInstanceId(INSTANCE_ID);

        client = new BlindPayClient(properties, JsonMapper.builder().build(), builder);
    }

    // ---------------------------------------------------------------------
    // Customers
    // ---------------------------------------------------------------------

    @Test
    void createsBusinessCustomerWithSnakeCaseBodyAuthAndIdempotencyKey() {
        // Arrange
        server.expect(once(), requestTo(INSTANCE_URL + "/customers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "company-123"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {
                          "type": "business",
                          "kyc_type": "standard",
                          "email": "finance@acme.test",
                          "tax_id": "12345678000190",
                          "address_line_1": "Av. Ipiranga, 6681",
                          "city": "Porto Alegre",
                          "state_province_region": "RS",
                          "country": "BR",
                          "postal_code": "90619900",
                          "legal_name": "Acme Importadora LTDA",
                          "formation_date": "2020-04-23",
                          "business_type": "corporation",
                          "owners": [{
                            "role": "beneficial_owner",
                            "first_name": "Ana",
                            "last_name": "Souza",
                            "address_line_1": "Rua A, 10",
                            "country": "BR",
                            "ownership_percentage": 60
                          }],
                          "tos_id": "to_000000000001"
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"id":"re_000000000001","customer_id":"re_000000000001"}
                        """, MediaType.APPLICATION_JSON));

        BlindPayCreateCustomerRequest request = BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.business)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email("finance@acme.test")
                .taxId("12345678000190")
                .addressLine1("Av. Ipiranga, 6681")
                .city("Porto Alegre")
                .stateProvinceRegion("RS")
                .country("BR")
                .postalCode("90619900")
                .legalName("Acme Importadora LTDA")
                .formationDate(LocalDate.of(2020, 4, 23))
                .businessType(BlindPayApi.Customer.BusinessType.corporation)
                .owners(List.of(BlindPayCreateCustomerRequest.Owner.builder()
                        .role(BlindPayApi.Customer.OwnerRole.beneficial_owner)
                        .firstName("Ana")
                        .lastName("Souza")
                        .addressLine1("Rua A, 10")
                        .country("BR")
                        .ownershipPercentage(60)
                        .build()))
                .tosId("to_000000000001")
                .build();

        // Act
        BlindPayCustomerCreatedResponse response = client.createCustomer(request, "company-123");

        // Assert
        assertThat(response.id()).isEqualTo("re_000000000001");
        assertThat(response.customerId()).isEqualTo("re_000000000001");
        server.verify();
    }

    @Test
    void omitsIdempotencyHeaderWhenKeyIsNotProvided() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers"))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess("{\"id\":\"re_000000000001\"}", MediaType.APPLICATION_JSON));

        client.createCustomer(individualCustomer(), null);

        server.verify();
    }

    @Test
    void fetchesCustomerKeepingUnknownStatusesAndIgnoringUnknownFields() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers/re_000000000001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess("""
                        {
                          "id": "re_000000000001",
                          "type": "business",
                          "kyc_type": "standard",
                          "kyc_status": "a_status_created_after_this_code",
                          "aml_status": "clear",
                          "email": "finance@acme.test",
                          "country": "BR",
                          "legal_name": "Acme Importadora LTDA",
                          "is_tos_accepted": true,
                          "limit": {"per_transaction": 100000, "daily": 200000, "monthly": 1000000},
                          "created_at": "2026-01-01T00:00:00Z",
                          "field_added_in_a_future_version": {"nested": true}
                        }
                        """, MediaType.APPLICATION_JSON));

        BlindPayCustomerResponse customer = client.getCustomer("re_000000000001");

        assertThat(customer.kycStatus()).isEqualTo("a_status_created_after_this_code");
        assertThat(customer.amlStatus()).isEqualTo("clear");
        assertThat(customer.isTosAccepted()).isTrue();
        assertThat(customer.limit().daily()).isEqualByComparingTo("200000");
        assertThat(customer.createdAt()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        server.verify();
    }

    // ---------------------------------------------------------------------
    // Bank accounts
    // ---------------------------------------------------------------------

    @Test
    void createsSwiftBankAccountKeepingNumberedFieldNames() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers/re_000000000001/bank-accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "beneficiary-42"))
                .andExpect(content().json("""
                        {
                          "type": "international_swift",
                          "name": "Shenzhen Supplier",
                          "recipient_relationship": "vendor_or_supplier",
                          "swift_code_bic": "BKCHCNBJ",
                          "swift_account_holder_name": "Shenzhen Supplier Co",
                          "swift_account_number_iban": "6217000010001234567",
                          "swift_beneficiary_address_line_1": "1 Shennan Road",
                          "swift_beneficiary_country": "CN",
                          "swift_bank_name": "Bank of China",
                          "swift_bank_address_line_1": "1 Fuxingmen Nei Dajie",
                          "swift_bank_country": "CN"
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"id":"ba_000000000001","type":"international_swift","name":"Shenzhen Supplier",
                         "status":"verifying","created_at":"2026-01-01T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        BlindPayBankAccountResponse response = client.createBankAccount("re_000000000001",
                BlindPayCreateBankAccountRequest.builder()
                        .type(BlindPayApi.BankAccount.Type.international_swift)
                        .name("Shenzhen Supplier")
                        .recipientRelationship("vendor_or_supplier")
                        .swiftCodeBic("BKCHCNBJ")
                        .swiftAccountHolderName("Shenzhen Supplier Co")
                        .swiftAccountNumberIban("6217000010001234567")
                        .swiftBeneficiaryAddressLine1("1 Shennan Road")
                        .swiftBeneficiaryCountry("CN")
                        .swiftBankName("Bank of China")
                        .swiftBankAddressLine1("1 Fuxingmen Nei Dajie")
                        .swiftBankCountry("CN")
                        .build(),
                "beneficiary-42");

        assertThat(response.id()).isEqualTo("ba_000000000001");
        assertThat(response.status()).isEqualTo("verifying");
        server.verify();
    }

    @Test
    void fetchesBankAccountOfCustomer() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers/re_000000000001/bank-accounts/ba_000000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"ba_000000000001","type":"pix","name":"Fornecedor","status":"approved"}
                        """, MediaType.APPLICATION_JSON));

        BlindPayBankAccountResponse response = client.getBankAccount("re_000000000001", "ba_000000000001");

        assertThat(response.type()).isEqualTo("pix");
        assertThat(response.status()).isEqualTo("approved");
        server.verify();
    }

    @Test
    void createsManagedWalletForCustomer() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers/re_000000000001/wallets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "company-wallet-polygon"))
                .andExpect(content().json("""
                        {
                          "network": "polygon",
                          "external_id": "company-id:polygon",
                          "name": "V-Stable polygon"
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "id": "bl_000000000001",
                          "name": "V-Stable polygon",
                          "external_id": "company-id:polygon",
                          "address": "0x123",
                          "network": "polygon",
                          "created_at": "2026-01-01T00:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        BlindPayWalletResponse wallet = client.createWallet(
                "re_000000000001",
                new BlindPayCreateWalletRequest(
                        BlindPayApi.BlockchainWallet.Network.polygon,
                        "company-id:polygon",
                        "V-Stable polygon"),
                "company-wallet-polygon");

        assertThat(wallet.id()).isEqualTo("bl_000000000001");
        assertThat(wallet.network()).isEqualTo(BlindPayApi.BlockchainWallet.Network.polygon);
        server.verify();
    }

    // ---------------------------------------------------------------------
    // Payout quote and payout
    // ---------------------------------------------------------------------

    @Test
    void createsPayoutQuoteAndMapsApprovalContract() {
        server.expect(once(), requestTo(INSTANCE_URL + "/quotes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "bank_account_id": "ba_000000000001",
                          "network": "base",
                          "token": "USDC",
                          "currency_type": "sender",
                          "request_amount": 100000,
                          "cover_fees": true
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "id": "qu_000000000001",
                          "expires_at": 1712958191,
                          "commercial_quotation": 495,
                          "blindpay_quotation": 485,
                          "receiver_amount": 485000,
                          "sender_amount": 100000,
                          "flat_fee": 50,
                          "contract": {
                            "abi": [{"name": "approve", "type": "function"}],
                            "address": "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                            "functionName": "approve",
                            "blindpayContractAddress": "0x2a7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                            "amount": "1000000000",
                            "network": {"name": "Base", "chainId": 8453}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        BlindPayQuoteResponse quote = client.createQuote(BlindPayQuoteRequest.builder()
                .bankAccountId("ba_000000000001")
                .network(BlindPayApi.BlockchainWallet.Network.base)
                .token(BlindPayApi.VirtualAccount.Token.USDC)
                .currencyType(BlindPayApi.Quote.CurrencyType.sender)
                .requestAmount(100_000L)
                .coverFees(true)
                .build(), "quote-transaction-7");

        assertThat(quote.id()).isEqualTo("qu_000000000001");
        assertThat(quote.expiresAt()).isEqualTo(1712958191L);
        assertThat(quote.receiverAmount()).isEqualByComparingTo("485000");
        assertThat(quote.contract().functionName()).isEqualTo("approve");
        assertThat(quote.contract().blindpayContractAddress()).startsWith("0x2a7D");
        assertThat(quote.contract().network().chainId()).isEqualTo(8453L);
        assertThat(quote.contract().abi().isArray()).isTrue();
        server.verify();
    }

    @Test
    void createsEvmPayoutFromExternalWallet() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payouts/evm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "transaction-7"))
                .andExpect(content().json("""
                        {"quote_id":"qu_000000000001","sender_wallet_address":"0xDD6a3aD0949396e57C7738ba8FC1A46A5a1C372C"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "id": "po_000000000001",
                          "status": "processing",
                          "sender_wallet_address": "0xDD6a3aD0949396e57C7738ba8FC1A46A5a1C372C",
                          "tracking_transaction": {"step": "processing"},
                          "tracking_payment": {"step": "on_hold"},
                          "tracking_complete": {"step": "on_hold"}
                        }
                        """, MediaType.APPLICATION_JSON));

        BlindPayPayoutResponse payout = client.createEvmPayout(BlindPayEvmPayoutRequest.fromExternalWallet(
                "qu_000000000001", "0xDD6a3aD0949396e57C7738ba8FC1A46A5a1C372C"), "transaction-7");

        assertThat(payout.id()).isEqualTo("po_000000000001");
        assertThat(payout.status()).isEqualTo("processing");
        assertThat(payout.trackingTransaction().step()).isEqualTo("processing");
        server.verify();
    }

    @Test
    void fetchesPayoutWithTrackingSteps() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payouts/po_000000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "po_000000000001",
                          "status": "completed",
                          "customer_id": "re_000000000001",
                          "bank_account_id": "ba_000000000001",
                          "quote_id": "qu_000000000001",
                          "network": "base",
                          "token": "USDC",
                          "currency": "BRL",
                          "sender_amount": 100000,
                          "receiver_amount": 485000,
                          "total_fee_amount": 1.5,
                          "tracking_transaction": {"step": "completed", "status": "found",
                                                   "transaction_hash": "0xabc"},
                          "tracking_payment": {"step": "completed", "provider_status": "sent",
                                               "estimated_time_of_arrival": "5_min",
                                               "completed_at": "2026-01-01T10:00:00.000Z"},
                          "tracking_complete": {"step": "completed", "status": "paid"},
                          "created_at": "2026-01-01T09:55:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        BlindPayPayoutResponse payout = client.getPayout("po_000000000001");

        assertThat(payout.status()).isEqualTo("completed");
        assertThat(payout.totalFeeAmount()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(payout.trackingTransaction().transactionHash()).isEqualTo("0xabc");
        assertThat(payout.trackingPayment().providerStatus()).isEqualTo("sent");
        assertThat(payout.trackingPayment().estimatedTimeOfArrival()).isEqualTo("5_min");
        assertThat(payout.trackingComplete().status()).isEqualTo("paid");
        server.verify();
    }

    // ---------------------------------------------------------------------
    // Payin quote and payin
    // ---------------------------------------------------------------------

    @Test
    void createsPayinQuoteForManagedWallet() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payin-quotes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "wallet_id": "bl_000000000001",
                          "payment_method": "pix",
                          "token": "USDC",
                          "currency_type": "sender",
                          "request_amount": 50000
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"id":"pq_000000000001","expires_at":1712958191,"commercial_quotation":495,
                         "blindpay_quotation":505,"receiver_amount":9900,"sender_amount":50000,"flat_fee":50}
                        """, MediaType.APPLICATION_JSON));

        BlindPayPayinQuoteResponse quote = client.createPayinQuote(BlindPayPayinQuoteRequest.builder()
                .walletId("bl_000000000001")
                .paymentMethod(BlindPayApi.Payin.PaymentMethod.pix)
                .token(BlindPayApi.VirtualAccount.Token.USDC)
                .currencyType(BlindPayApi.Quote.CurrencyType.sender)
                .requestAmount(50_000L)
                .build(), null);

        assertThat(quote.id()).isEqualTo("pq_000000000001");
        assertThat(quote.receiverAmount()).isEqualByComparingTo("9900");
        server.verify();
    }

    @Test
    void createsEvmPayinFromQuote() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payins/evm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "deposit-9"))
                .andExpect(content().json("{\"payin_quote_id\":\"pq_000000000001\"}", JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "id": "pi_000000000001",
                          "status": "processing",
                          "pix_code": "00020101021226790014br.gov.bcb.pix",
                          "payment_method": "pix",
                          "tracking_transaction": {"step": "processing"},
                          "tracking_payment": {"step": "on_hold"},
                          "tracking_complete": {"step": "on_hold"},
                          "blindpay_bank_details": {"beneficiary": {"name": "BlindPay, Inc."}}
                        }
                        """, MediaType.APPLICATION_JSON));

        BlindPayPayinResponse payin = client.createEvmPayin("pq_000000000001", "deposit-9");

        assertThat(payin.id()).isEqualTo("pi_000000000001");
        assertThat(payin.pixCode()).startsWith("000201");
        assertThat(payin.blindpayBankDetails().get("beneficiary").get("name").asString())
                .isEqualTo("BlindPay, Inc.");
        server.verify();
    }

    @Test
    void fetchesPayin() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payins/pi_000000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"pi_000000000001","status":"completed","sender_amount":50000,
                         "receiver_amount":9900,"currency":"BRL","token":"USDC",
                         "tracking_complete":{"step":"completed","transaction_hash":"0xdef"}}
                        """, MediaType.APPLICATION_JSON));

        BlindPayPayinResponse payin = client.getPayin("pi_000000000001");

        assertThat(payin.status()).isEqualTo("completed");
        assertThat(payin.trackingComplete().transactionHash()).isEqualTo("0xdef");
        server.verify();
    }

    // ---------------------------------------------------------------------
    // URI construction
    // ---------------------------------------------------------------------

    @Test
    void encodesPathVariablesSoAnIdCannotChangeTheCalledRoute() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers/re_1%2F..%2Fpayouts%3Fx%3D1"))
                .andRespond(withSuccess("{\"id\":\"re_1\"}", MediaType.APPLICATION_JSON));

        client.getCustomer("re_1/../payouts?x=1");

        server.verify();
    }

    @Test
    void acceptsBaseUrlWithTrailingSlash() {
        properties.setBaseUrl(BASE_URL + "/");
        server.expect(once(), requestTo(INSTANCE_URL + "/payouts/po_000000000001"))
                .andRespond(withSuccess("{\"id\":\"po_000000000001\"}", MediaType.APPLICATION_JSON));

        client.getPayout("po_000000000001");

        server.verify();
    }

    // ---------------------------------------------------------------------
    // Error handling
    // ---------------------------------------------------------------------

    @Test
    void mapsBusinessRejectionUsingOnlySafeFieldsOfTheErrorEnvelope() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payouts/evm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "code": "PAYOUTS_INSUFFICIENT_BALANCE",
                                  "message": "not_enough_balance",
                                  "description": "There is not enough balance to complete this payout.",
                                  "errors": ["tax_id 123.456.789-10 is invalid"],
                                  "trace_id": "0af7651916cd43dd8448eb211c80319c"
                                }
                                """));

        assertThatThrownBy(() -> client.createEvmPayout(
                BlindPayEvmPayoutRequest.fromManagedWallet("qu_000000000001", "bl_000000000001"), "tx-1"))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("Could not create the BlindPay payout: HTTP 400"
                            + " - PAYOUTS_INSUFFICIENT_BALANCE: There is not enough balance to complete this payout.");
                    assertThat(ex.getMessage()).doesNotContain("123.456.789-10", "not_enough_balance");
                    assertThat(ex.getProviderStatus()).isEqualTo(400);
                    assertThat(ex.getProviderErrorCode()).isEqualTo("PAYOUTS_INSUFFICIENT_BALANCE");
                    assertThat(ex.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.isRejectedByProvider()).isTrue();
                    assertThat(ex.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void treatsRetryableErrorCodeAsRetryableEvenWhenStatusIs4xx() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payouts/evm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYOUTS_ALLOWANCE_NOT_CONFIRMED\"}"));

        assertThatThrownBy(() -> client.createEvmPayout(BlindPayEvmPayoutRequest.fromExternalWallet(
                "qu_000000000001", "0xDD6a3aD0949396e57C7738ba8FC1A46A5a1C372C"), "tx-1"))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.isRejectedByProvider()).isFalse();
                });
        server.verify();
    }

    @Test
    void reducesNonJsonServerErrorToHttpStatusAndMarksItRetryable() {
        server.expect(once(), requestTo(INSTANCE_URL + "/payins/pi_000000000001"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>upstream down</html>"));

        assertThatThrownBy(() -> client.getPayin("pi_000000000001"))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("Could not fetch the BlindPay payin: HTTP 503");
                    assertThat(ex.getProviderErrorCode()).isNull();
                    assertThat(ex.isRetryable()).isTrue();
                });
        server.verify();
    }

    @Test
    void doesNotTreatInvalidApiKeyAsBusinessRejection() {
        server.expect(once(), requestTo(INSTANCE_URL + "/customers/re_000000000001"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"AUTH_UNAUTHORIZED\",\"description\":\"Authentication failed.\"}"));

        assertThatThrownBy(() -> client.getCustomer("re_000000000001"))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.isRejectedByProvider()).isFalse();
                });
        server.verify();
    }

    @Test
    void mapsCommunicationFailureAsRetryable() {
        server.expect(once(), requestTo(INSTANCE_URL + "/quotes"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.createQuote(quoteRequest(), null))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.getMessage())
                            .isEqualTo("Could not create the BlindPay payout quote: BlindPay is unreachable");
                    assertThat(ex.getProviderStatus()).isNull();
                    assertThat(ex.isRetryable()).isTrue();
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json"})
    void rejectsEmptyOrUnreadableSuccessResponse(String body) {
        server.expect(once(), requestTo(INSTANCE_URL + "/payouts/po_000000000001"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getPayout("po_000000000001"))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.getMessage()).startsWith("Could not fetch the BlindPay payout: BlindPay returned");
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.isRejectedByProvider()).isFalse();
                });
        server.verify();
    }

    // ---------------------------------------------------------------------
    // Fail fast: configuration and arguments are checked before any request
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"baseUrl", "apiKey", "instanceId"})
    void failsWithoutCallingBlindPayWhenConfigurationIsMissing(String missingProperty) {
        switch (missingProperty) {
            case "baseUrl" -> properties.setBaseUrl(" ");
            case "apiKey" -> properties.setApiKey(null);
            default -> properties.setInstanceId("");
        }

        assertThatThrownBy(() -> client.getPayout("po_000000000001"))
                .isInstanceOfSatisfying(BlindPayIntegrationException.class, ex -> {
                    assertThat(ex.getMessage()).endsWith("is not configured");
                    assertThat(ex.isRetryable()).isFalse();
                });
        server.verify();
    }

    @Test
    void rejectsInvalidArgumentsWithoutCallingBlindPay() {
        assertThatThrownBy(() -> client.createCustomer(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("customer request must be provided");
        assertThatThrownBy(() -> client.getBankAccount("re_000000000001", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankAccountId must be provided");
        assertThatThrownBy(() -> client.createEvmPayin(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payinQuoteId must be provided");
        assertThatThrownBy(() -> client.createQuote(quoteRequest(), "k".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey must have at most 255 characters");
        server.verify();
    }

    private BlindPayCreateCustomerRequest individualCustomer() {
        return BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.individual)
                .kycType(BlindPayApi.Customer.KycType.light)
                .email("ana@acme.test")
                .country("BR")
                .build();
    }

    private BlindPayQuoteRequest quoteRequest() {
        return BlindPayQuoteRequest.builder()
                .bankAccountId("ba_000000000001")
                .network(BlindPayApi.BlockchainWallet.Network.base)
                .token(BlindPayApi.VirtualAccount.Token.USDC)
                .currencyType(BlindPayApi.Quote.CurrencyType.sender)
                .requestAmount(1_000L)
                .build();
    }
}
