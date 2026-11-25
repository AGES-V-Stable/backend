package ages.vstable.backend.service.exchangerate;

import ages.vstable.backend.entity.ExchangeRateSnapshotEntity;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.AveniaProperties;
import ages.vstable.backend.external.avenia.AveniaRequestSigner;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.repository.ExchangeRateSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Real reference service and client; only persistence and the outbound HTTP transport are stubbed. */
class ExchangeRateReferencePathTest {
    private static final Instant NOW = Instant.parse("2026-11-25T13:00:00Z");
    private static final OffsetDateTime FETCHED_AT = NOW.atOffset(ZoneOffset.UTC);
    private static final String REFERENCE_URI = "https://avenia.test/v2/account/quote/fixed-rate"
            + "?inputCurrency=BRL&inputPaymentMethod=PIX&outputCurrency=USDC"
            + "&outputPaymentMethod=POLYGON&inputThirdParty=false&outputThirdParty=false&inputAmount=1000";

    private final ExchangeRateSnapshotRepository repository = mock(ExchangeRateSnapshotRepository.class);
    private MockRestServiceServer transport;
    private AveniaClient client;
    private ExchangeRateService service;

    @BeforeEach
    void setUp() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        transport = MockRestServiceServer.bindTo(builder).build();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var properties = new AveniaProperties();
        properties.setBaseUrl("https://avenia.test");
        properties.setApiKey("test-only-reference-key");
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----");
        client = new AveniaClient(properties, new AveniaRequestSigner(), new ObjectMapper(), builder);
        var rates = new ExchangeRateProperties();
        var pair = new ExchangeRateProperties.Pair();
        pair.setBaseCurrency("BRL");
        pair.setQuoteCurrency("USDC");
        pair.setOutputPaymentMethod("POLYGON");
        rates.setPairs(List.of(pair));
        service = new ExchangeRateService(repository, client, rates, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void emptyCache_reachesSignedReferenceGetAndPersistsBasePriceWithoutExecutableToken() {
        when(repository.findAll()).thenReturn(List.of());
        transport.expect(requestTo(REFERENCE_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "test-only-reference-key"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("X-API-Signature")).isNotBlank())
                .andRespond(withSuccess("""
                        {"quoteToken":"not-exposed-by-reference-read","inputCurrency":"BRL","inputPaymentMethod":"PIX",
                         "outputCurrency":"USDC","outputPaymentMethod":"POLYGON","basePrice":"5.471234567"}
                        """, MediaType.APPLICATION_JSON));

        var response = service.currentRates();

        assertThat(response.ttlSeconds()).isEqualTo(7200);
        assertThat(response.rates()).singleElement().satisfies(rate -> {
            assertThat(rate.status()).isEqualTo(ExchangeRateStatus.CURRENT);
            assertThat(rate.rate()).isEqualTo("5.47123457");
            assertThat(rate.fetchedAt()).isEqualTo(FETCHED_AT);
        });
        verify(repository).upsert(any(UUID.class), eq("BRL"), eq("USDC"), eq(new BigDecimal("5.47123457")),
                eq("AVENIA"), eq(FETCHED_AT));

        // A successful reference read does not enable unscoped financial quotes or tickets at either layer.
        var unscopedQuote = new AveniaQuoteRequest("BRL", "PIX", "USDC", "POLYGON", BigDecimal.TEN,
                null, false, false, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> client.createQuote(unscopedQuote)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAccountId");
        var financial = new AveniaTransferService(client);
        assertThatThrownBy(() -> financial.createQuote(unscopedQuote)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAccountId");
        var ticket = AveniaTicketRequest.builder().quoteToken("test-only-token").build();
        assertThatThrownBy(() -> client.createTicket(ticket)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAccountId");
        assertThatThrownBy(() -> financial.createTransfer(unscopedQuote, ticket)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAccountId");
        transport.verify();
    }

    @Test
    void providerFailureWithSavedRate_returnsStaleAndDoesNotOverwriteCache() {
        when(repository.findAll()).thenReturn(List.of(ExchangeRateSnapshotEntity.builder()
                .baseCurrency("BRL").quoteCurrency("USDC").rate(new BigDecimal("5.40"))
                .fetchedAt(FETCHED_AT.minusHours(3)).build()));
        transport.expect(requestTo(REFERENCE_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var rate = service.currentRates().rates().getFirst();

        assertThat(rate.status()).isEqualTo(ExchangeRateStatus.STALE);
        assertThat(rate.rate()).isEqualTo("5.40000000");
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any());
        transport.verify();
    }

    @Test
    void mismatchedProviderRouteWithoutCache_returnsUnavailableWithoutSaving() {
        when(repository.findAll()).thenReturn(List.of());
        transport.expect(requestTo(REFERENCE_URI)).andRespond(withSuccess("""
                {"inputCurrency":"BRL","inputPaymentMethod":"PIX","outputCurrency":"USDT",
                 "outputPaymentMethod":"POLYGON","basePrice":"5.47"}
                """, MediaType.APPLICATION_JSON));

        var rate = service.currentRates().rates().getFirst();

        assertThat(rate.status()).isEqualTo(ExchangeRateStatus.UNAVAILABLE);
        assertThat(rate.rate()).isNull();
        assertThat(rate.fetchedAt()).isNull();
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any());
        transport.verify();
    }
}
