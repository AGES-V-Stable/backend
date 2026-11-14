package ages.vstable.backend.service.exchangerate;

import ages.vstable.backend.dto.exchangerate.ExchangeRateResponse;
import ages.vstable.backend.dto.exchangerate.ExchangeRatesResponse;
import ages.vstable.backend.entity.ExchangeRateSnapshotEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.repository.ExchangeRateSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T22:45:00Z");
    private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);
    private static final Executor DIRECT = Runnable::run;

    @Mock
    private ExchangeRateSnapshotRepository repository;

    @Mock
    private AveniaTransferService aveniaTransferService;

    private ExchangeRateProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ExchangeRateProperties();
        properties.setTtl(Duration.ofHours(2));
        properties.setProviderTimeout(Duration.ofSeconds(5));
        properties.setReferenceAmount(new BigDecimal("1000"));
        properties.setPairs(List.of(pair("BRL", "USDC")));
    }

    private ExchangeRateService service(Executor executor) {
        return new ExchangeRateService(repository, aveniaTransferService, properties, executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void withoutSavedRate_quotesAveniaPersistsAndReturnsCurrent() {
        // Arrange
        when(repository.findAll()).thenReturn(List.of());
        when(aveniaTransferService.createQuote(any())).thenReturn(quote("5.47"));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.ttlSeconds()).isEqualTo(7200);
        assertThat(response.rates()).containsExactly(
                new ExchangeRateResponse("BRL", "USDC", "5.47000000", NOW_UTC, ExchangeRateStatus.CURRENT));
        verify(repository).upsert(any(UUID.class), eq("BRL"), eq("USDC"), eq(new BigDecimal("5.47000000")),
                eq("AVENIA"), eq(NOW_UTC));
    }

    @Test
    void savedRateYoungerThanTtl_isReturnedWithoutCallingAvenia() {
        // Arrange
        OffsetDateTime fetchedAt = NOW_UTC.minusHours(1).minusMinutes(59);
        when(repository.findAll()).thenReturn(List.of(snapshot("BRL", "USDC", "5.4012345678", fetchedAt)));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates()).containsExactly(
                new ExchangeRateResponse("BRL", "USDC", "5.40123457", fetchedAt, ExchangeRateStatus.CURRENT));
        verifyNoInteractions(aveniaTransferService);
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void savedRateExactlyAtTtl_isStillCurrent() {
        // Arrange
        when(repository.findAll()).thenReturn(List.of(snapshot("BRL", "USDC", "5.40", NOW_UTC.minusHours(2))));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates().getFirst().status()).isEqualTo(ExchangeRateStatus.CURRENT);
        verifyNoInteractions(aveniaTransferService);
    }

    @Test
    void savedRateOlderThanTtl_isRefreshedFromAveniaAndUpdated() {
        // Arrange
        when(repository.findAll()).thenReturn(List.of(
                snapshot("BRL", "USDC", "5.40", NOW_UTC.minusHours(2).minusSeconds(1))));
        when(aveniaTransferService.createQuote(any())).thenReturn(quote("5.512345675"));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates()).containsExactly(
                new ExchangeRateResponse("BRL", "USDC", "5.51234568", NOW_UTC, ExchangeRateStatus.CURRENT));
        verify(repository).upsert(any(UUID.class), eq("BRL"), eq("USDC"), eq(new BigDecimal("5.51234568")),
                eq("AVENIA"), eq(NOW_UTC));
    }

    @Test
    void aveniaFailsWithSavedRate_returnsStaleAndKeepsTheSavedRow() {
        // Arrange
        OffsetDateTime fetchedAt = NOW_UTC.minusHours(5);
        when(repository.findAll()).thenReturn(List.of(snapshot("BRL", "USDC", "5.40", fetchedAt)));
        when(aveniaTransferService.createQuote(any()))
                .thenThrow(AveniaIntegrationException.response("Could not obtain a quote from Avenia", 503, null));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates()).containsExactly(
                new ExchangeRateResponse("BRL", "USDC", "5.40000000", fetchedAt, ExchangeRateStatus.STALE));
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aveniaFailsWithoutSavedRate_returnsUnavailableWithoutRate() {
        // Arrange
        when(repository.findAll()).thenReturn(List.of());
        when(aveniaTransferService.createQuote(any()))
                .thenThrow(new AveniaIntegrationException("Avenia returned an incomplete quote", 502, false, null));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates()).containsExactly(
                new ExchangeRateResponse("BRL", "USDC", null, null, ExchangeRateStatus.UNAVAILABLE));
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aveniaDoesNotAnswerWithinTimeout_isTreatedAsFailure() {
        // Arrange: o executor descarta a tarefa, então a cotação nunca chega.
        properties.setProviderTimeout(Duration.ofMillis(50));
        OffsetDateTime fetchedAt = NOW_UTC.minusHours(3);
        when(repository.findAll()).thenReturn(List.of(snapshot("BRL", "USDC", "5.40", fetchedAt)));

        // Act
        ExchangeRatesResponse response = service(task -> { }).currentRates();

        // Assert
        assertThat(response.rates().getFirst().status()).isEqualTo(ExchangeRateStatus.STALE);
        assertThat(response.rates().getFirst().fetchedAt()).isEqualTo(fetchedAt);
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void quote_isRequestedOnTheMainAccountWithTheReferenceAmountAndConfiguredRoute() {
        // Arrange
        when(repository.findAll()).thenReturn(List.of());
        when(aveniaTransferService.createQuote(any())).thenReturn(quote("5.47"));

        // Act
        service(DIRECT).currentRates();

        // Assert
        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        AveniaQuoteRequest request = captor.getValue();
        assertThat(request.inputCurrency()).isEqualTo("BRL");
        assertThat(request.inputPaymentMethod()).isEqualTo("PIX");
        assertThat(request.outputCurrency()).isEqualTo("USDC");
        assertThat(request.outputPaymentMethod()).isEqualTo("POLYGON");
        assertThat(request.inputAmount()).isEqualByComparingTo("1000");
        assertThat(request.outputAmount()).isNull();
        assertThat(request.subAccountId()).isNull();
    }

    @Test
    void eachPairIsResolvedIndependently_inTheConfiguredOrder() {
        // Arrange
        properties.setPairs(List.of(pair("BRL", "USDC"), pair("BRL", "USDT")));
        OffsetDateTime fetchedAt = NOW_UTC.minusMinutes(10);
        when(repository.findAll()).thenReturn(List.of(snapshot("BRL", "USDC", "5.40", fetchedAt)));
        when(aveniaTransferService.createQuote(any()))
                .thenThrow(AveniaIntegrationException.communication("Could not reach Avenia", null));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates()).extracting(ExchangeRateResponse::quoteCurrency, ExchangeRateResponse::status)
                .containsExactly(
                        tuple("USDC", ExchangeRateStatus.CURRENT),
                        tuple("USDT", ExchangeRateStatus.UNAVAILABLE));
        verify(repository, never()).upsert(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void pairCurrencies_areNormalizedToMatchTheSavedRow() {
        // Arrange
        properties.setPairs(List.of(pair(" brl ", "usdc")));
        when(repository.findAll()).thenReturn(List.of(snapshot("BRL", "USDC", "5.40", NOW_UTC.minusMinutes(1))));

        // Act
        ExchangeRatesResponse response = service(DIRECT).currentRates();

        // Assert
        assertThat(response.rates().getFirst().status()).isEqualTo(ExchangeRateStatus.CURRENT);
        verifyNoInteractions(aveniaTransferService);
    }

    private static ExchangeRateProperties.Pair pair(String base, String quote) {
        ExchangeRateProperties.Pair pair = new ExchangeRateProperties.Pair();
        pair.setBaseCurrency(base);
        pair.setQuoteCurrency(quote);
        pair.setInputPaymentMethod("PIX");
        pair.setOutputPaymentMethod("POLYGON");
        return pair;
    }

    private static ExchangeRateSnapshotEntity snapshot(String base, String quote, String rate, OffsetDateTime fetchedAt) {
        return ExchangeRateSnapshotEntity.builder()
                .id(UUID.randomUUID())
                .baseCurrency(base)
                .quoteCurrency(quote)
                .rate(new BigDecimal(rate))
                .provider(IntegrationProvider.AVENIA)
                .fetchedAt(fetchedAt)
                .build();
    }

    private static AveniaQuoteResponse quote(String basePrice) {
        return new AveniaQuoteResponse("token", "BRL", "PIX", new BigDecimal("1000"), "USDC", "POLYGON",
                new BigDecimal("182.11"), BigDecimal.ZERO, null, false, false, List.of(),
                new BigDecimal(basePrice), "BRLUSDC");
    }
}
