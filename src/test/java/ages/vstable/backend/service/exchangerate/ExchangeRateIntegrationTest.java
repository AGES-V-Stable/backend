package ages.vstable.backend.service.exchangerate;

import ages.vstable.backend.dto.exchangerate.ExchangeRatesResponse;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.repository.ExchangeRateSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Upsert, chave única e o fluxo do serviço num PostgreSQL real, com a Avenia mockada. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ExchangeRateIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    private AveniaTransferService aveniaTransferService;

    @Autowired
    private ExchangeRateSnapshotRepository repository;

    @Autowired
    private ExchangeRateService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM exchange_rate_snapshots");
    }

    @Test
    void upsert_createsTheRowThenReplacesItWithANewerRate() {
        // Arrange
        OffsetDateTime first = OffsetDateTime.parse("2026-11-12T10:00:00Z");

        // Act
        int inserted = repository.upsert(UUID.randomUUID(), "BRL", "USDC", new BigDecimal("5.40"), "AVENIA", first);
        int updated = repository.upsert(UUID.randomUUID(), "BRL", "USDC", new BigDecimal("5.47"), "AVENIA",
                first.plusHours(3));

        // Assert
        assertThat(inserted).isEqualTo(1);
        assertThat(updated).isEqualTo(1);
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getRate()).isEqualByComparingTo("5.47");
            assertThat(row.getFetchedAt()).isEqualTo(first.plusHours(3));
        });
    }

    @Test
    void upsert_neverReplacesARateWithAnOlderOne() {
        // Arrange
        OffsetDateTime newest = OffsetDateTime.parse("2026-11-12T10:00:00Z");
        repository.upsert(UUID.randomUUID(), "BRL", "USDC", new BigDecimal("5.47"), "AVENIA", newest);

        // Act
        int changed = repository.upsert(UUID.randomUUID(), "BRL", "USDC", new BigDecimal("5.10"), "AVENIA",
                newest.minusMinutes(1));

        // Assert
        assertThat(changed).isZero();
        assertThat(repository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getRate()).isEqualByComparingTo("5.47"));
    }

    @Test
    void table_rejectsASecondRowForTheSamePair() {
        // Arrange
        repository.upsert(UUID.randomUUID(), "BRL", "USDC", new BigDecimal("5.47"), "AVENIA",
                OffsetDateTime.parse("2026-11-12T10:00:00Z"));

        // Act + Assert
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO exchange_rate_snapshots (id, base_currency, quote_currency, rate, provider, fetched_at) "
                        + "VALUES (?, 'BRL', 'USDC', 5.5, 'AVENIA', now())", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void currentRates_persistsOnFirstReadAndServesTheCacheOnTheNext() {
        // Arrange: pares padrão de application.yaml (BRL→USDC e BRL→USDT).
        when(aveniaTransferService.createQuote(any())).thenReturn(quote("5.47"));

        // Act
        ExchangeRatesResponse first = service.currentRates();
        ExchangeRatesResponse second = service.currentRates();

        // Assert
        assertThat(first.rates()).extracting(rate -> rate.quoteCurrency() + ":" + rate.rate() + ":" + rate.status())
                .containsExactly("USDC:5.47000000:CURRENT", "USDT:5.47000000:CURRENT");
        assertThat(second.rates()).isEqualTo(first.rates());
        assertThat(repository.count()).isEqualTo(2);
        verify(aveniaTransferService, times(2)).createQuote(any());
    }

    @Test
    void currentRates_whenAveniaFailsAfterTtl_keepsTheSavedRowAndMarksItStale() {
        // Arrange
        OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3).truncatedTo(ChronoUnit.MICROS);
        repository.upsert(UUID.randomUUID(), "BRL", "USDC", new BigDecimal("5.40"), "AVENIA", old);
        when(aveniaTransferService.createQuote(any()))
                .thenThrow(AveniaIntegrationException.communication("Could not reach Avenia", null));

        // Act
        ExchangeRatesResponse response = service.currentRates();

        // Assert
        assertThat(response.rates()).extracting(rate -> rate.quoteCurrency() + ":" + rate.status())
                .containsExactly("USDC:STALE", "USDT:UNAVAILABLE");
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getRate()).isEqualByComparingTo("5.40");
            assertThat(row.getFetchedAt().toInstant()).isEqualTo(old.toInstant());
        });
    }

    private static AveniaQuoteResponse quote(String basePrice) {
        return new AveniaQuoteResponse("token", "BRL", "PIX", new BigDecimal("1000"), "USDC", "POLYGON",
                new BigDecimal("182.11"), BigDecimal.ZERO, null, false, false, List.of(),
                new BigDecimal(basePrice), "BRLUSDC");
    }
}
