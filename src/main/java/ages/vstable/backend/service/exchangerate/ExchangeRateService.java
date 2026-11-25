package ages.vstable.backend.service.exchangerate;

import ages.vstable.backend.dto.exchangerate.ExchangeRateResponse;
import ages.vstable.backend.dto.exchangerate.ExchangeRatesResponse;
import ages.vstable.backend.entity.ExchangeRateSnapshotEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.external.avenia.AveniaReferenceRateGateway;
import ages.vstable.backend.external.avenia.dto.AveniaReferenceRateRequest;
import ages.vstable.backend.logging.OperationalLog;
import ages.vstable.backend.repository.ExchangeRateSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cotações de referência do dashboard (cicd#55), com cache global no banco atualizado na
 * leitura: o backend roda em Lambda, sem agendador nem memória entre invocações. Uma
 * cotação vencida é buscada de novo na Avenia; se a Avenia falhar, a última salva é
 * devolvida como STALE e nunca é sobrescrita.
 */
@Service
public class ExchangeRateService {

    static final int RATE_SCALE = 8;

    private final ExchangeRateSnapshotRepository repository;
    private final AveniaReferenceRateGateway referenceRateGateway;
    private final ExchangeRateProperties properties;
    private final Executor executor;
    private final Clock clock;

    @Autowired
    public ExchangeRateService(
            ExchangeRateSnapshotRepository repository,
            AveniaReferenceRateGateway referenceRateGateway,
            ExchangeRateProperties properties,
            @Qualifier("quoteProviderExecutor") Executor executor) {
        this(repository, referenceRateGateway, properties, executor, Clock.systemUTC());
    }

    // Construtor visível para testes determinísticos (controle do tempo do TTL)
    ExchangeRateService(
            ExchangeRateSnapshotRepository repository,
            AveniaReferenceRateGateway referenceRateGateway,
            ExchangeRateProperties properties,
            Executor executor,
            Clock clock) {
        this.repository = repository;
        this.referenceRateGateway = referenceRateGateway;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    public ExchangeRatesResponse currentRates() {
        // Na precisão do PostgreSQL (microssegundos), para a resposta igualar o que foi gravado.
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        Map<String, ExchangeRateSnapshotEntity> saved = repository.findAll().stream()
                .collect(Collectors.toMap(
                        snapshot -> key(snapshot.getBaseCurrency(), snapshot.getQuoteCurrency()),
                        Function.identity(), (first, second) -> first));

        // Todas as consultas à Avenia partem antes de esperar qualquer uma: o tempo total
        // fica limitado ao timeout de um par, não à soma deles.
        List<Lookup> lookups = properties.getPairs().stream()
                .map(pair -> {
                    ExchangeRateSnapshotEntity snapshot = saved.get(key(pair.getBaseCurrency(), pair.getQuoteCurrency()));
                    return new Lookup(pair, snapshot, isFresh(snapshot, now) ? null : fetchAsync(pair));
                })
                .toList();

        List<ExchangeRateResponse> rates = lookups.stream().map(lookup -> resolve(lookup, now)).toList();
        return new ExchangeRatesResponse(rates, properties.getTtl().toSeconds());
    }

    private ExchangeRateResponse resolve(Lookup lookup, OffsetDateTime now) {
        if (lookup.refresh() == null) {
            return fromSnapshot(lookup.snapshot(), ExchangeRateStatus.CURRENT);
        }
        ExchangeRateProperties.Pair pair = lookup.pair();
        String base = normalize(pair.getBaseCurrency());
        String quote = normalize(pair.getQuoteCurrency());
        try {
            BigDecimal rate = lookup.refresh().join();
            repository.upsert(UUID.randomUUID(), base, quote, rate, IntegrationProvider.AVENIA.name(), now);
            OperationalLog.exchangeRateRefresh(base + "/" + quote, "success");
            return new ExchangeRateResponse(base, quote, rate.toPlainString(), now, ExchangeRateStatus.CURRENT);
        } catch (CompletionException ex) {
            OperationalLog.exchangeRateRefresh(base + "/" + quote,
                    ex.getCause() instanceof TimeoutException ? "timeout" : "failure");
            return lookup.snapshot() == null
                    ? new ExchangeRateResponse(base, quote, null, null, ExchangeRateStatus.UNAVAILABLE)
                    : fromSnapshot(lookup.snapshot(), ExchangeRateStatus.STALE);
        }
    }

    private CompletableFuture<BigDecimal> fetchAsync(ExchangeRateProperties.Pair pair) {
        return CompletableFuture.supplyAsync(() -> fetchRate(pair), executor)
                .orTimeout(properties.getProviderTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Leitura global de referência sem token executável, ticket ou movimentação de saldo.
     * Cotações financeiras continuam exigindo a subconta autorizada da empresa.
     */
    private BigDecimal fetchRate(ExchangeRateProperties.Pair pair) {
        AveniaReferenceRateRequest request = new AveniaReferenceRateRequest(
                normalize(pair.getBaseCurrency()), pair.getInputPaymentMethod(),
                normalize(pair.getQuoteCurrency()), pair.getOutputPaymentMethod(),
                properties.getReferenceAmount());
        BigDecimal price = referenceRateGateway.getReferenceRate(request);
        // Taxas ausentes, negativas ou que arredondam para zero nunca são cotação atual.
        // O CompletionException do futuro mantém a última taxa como STALE, se existir.
        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException("Avenia returned an invalid exchange rate");
        }
        BigDecimal rounded = price.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0) {
            throw new IllegalStateException("Avenia returned a rate below supported precision");
        }
        return rounded;
    }

    private boolean isFresh(ExchangeRateSnapshotEntity snapshot, OffsetDateTime now) {
        return snapshot != null && !snapshot.getFetchedAt().plus(properties.getTtl()).isBefore(now);
    }

    private static ExchangeRateResponse fromSnapshot(ExchangeRateSnapshotEntity snapshot, ExchangeRateStatus status) {
        return new ExchangeRateResponse(
                snapshot.getBaseCurrency(),
                snapshot.getQuoteCurrency(),
                snapshot.getRate().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString(),
                snapshot.getFetchedAt().withOffsetSameInstant(ZoneOffset.UTC),
                status);
    }

    private static String key(String base, String quote) {
        return normalize(base) + "/" + normalize(quote);
    }

    private static String normalize(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }

    private record Lookup(
            ExchangeRateProperties.Pair pair,
            ExchangeRateSnapshotEntity snapshot,
            CompletableFuture<BigDecimal> refresh) {
    }
}
