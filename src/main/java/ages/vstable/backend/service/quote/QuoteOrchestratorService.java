package ages.vstable.backend.service.quote;

import ages.vstable.backend.logging.OperationalLog;

import ages.vstable.backend.dto.quote.*;
import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

@Service
public class QuoteOrchestratorService {

    private final List<QuoteProvider> providers;
    private final CompanyRepository companyRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final QuoteRequestRepository quoteRequestRepository;
    private final ProviderQuoteRepository providerQuoteRepository;

    private final Executor quoteProviderExecutor;

    @Value("${quotes.provider-timeout:10s}")
    private Duration providerTimeout;

    public QuoteOrchestratorService(
            List<QuoteProvider> providers,
            CompanyRepository companyRepository,
            BeneficiaryRepository beneficiaryRepository,
            QuoteRequestRepository quoteRequestRepository,
            ProviderQuoteRepository providerQuoteRepository,
            @Qualifier("quoteProviderExecutor") Executor quoteProviderExecutor
    ) {
        this.providers = providers;
        this.companyRepository = companyRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.quoteRequestRepository = quoteRequestRepository;
        this.providerQuoteRepository = providerQuoteRepository;
        this.quoteProviderExecutor = quoteProviderExecutor;
    }

    public QuoteResponse quote(UserEntity currentUser, QuoteRequest request) {
        long startedAt = System.nanoTime();
        CompanyEntity company = companyRepository.findById(currentUser.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found"));
        BeneficiaryEntity beneficiary = beneficiaryRepository
                .findByIdAndCompanyId(request.beneficiaryId(), currentUser.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Beneficiary not found"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        QuoteRequestEntity quoteRequest = quoteRequestRepository.save(QuoteRequestEntity.builder()
                .company(company)
                .requestedBy(currentUser)
                .beneficiary(beneficiary)
                .direction(request.direction())
                .sourceCurrency(request.sourceCurrency().toUpperCase())
                .targetCurrency(request.targetCurrency().toUpperCase())
                .sourcePaymentMethod(request.sourcePaymentMethod())
                .targetPaymentMethod(request.targetPaymentMethod())
                .requestedAmount(request.amount())
                .amountSide(request.amountSide())
                .token(normalizeUpper(request.token()))
                .blockchainNetwork(normalizeLower(request.blockchainNetwork()))
                .coverFees(request.coverFees())
                .description(request.description())
                .status("PROCESSING")
                .createdAt(now)
                .build());

        QuoteRequest normalized = new QuoteRequest(
                request.beneficiaryId(), request.direction(),
                request.sourceCurrency().toUpperCase(), request.targetCurrency().toUpperCase(),
                request.sourcePaymentMethod(), request.targetPaymentMethod(), request.amount(), request.amountSide(),
                normalizeUpper(request.token()), normalizeLower(request.blockchainNetwork()),
                request.coverFees(), request.description());
        QuoteContext context = new QuoteContext(
                quoteRequest.getId(), company, beneficiary, normalized, currentUser.getId());

        List<QuoteProvider> eligibleProviders = providers.stream()
                .filter(provider -> provider.supports(context))
                .toList();
        List<CompletableFuture<ProviderCall>> calls = eligibleProviders.stream()
                .map(provider -> CompletableFuture
                        .supplyAsync(() -> call(provider, context), quoteProviderExecutor)
                        .completeOnTimeout(ProviderCall.failure(provider.provider(), "TIMEOUT", "Provider timeout", providerTimeout.toMillis()),
                                providerTimeout.toMillis(), TimeUnit.MILLISECONDS))
                .toList();

        List<ProviderCall> results = calls.stream().map(CompletableFuture::join).toList();
        // Só a oferta vencedora fica SELECTED e pode ser confirmada: impede executar uma
        // oferta pior ou de um provedor que não venceu a comparação.
        ProviderCall best = results.stream()
                .filter(call -> call.result() != null && isUsable(call.result(), request))
                .min(Comparator.comparing(ProviderCall::result, bestOfferComparator(request.amountSide())))
                .orElse(null);

        QuoteOfferResponse bestOffer = null;
        int failures = 0;
        for (ProviderCall call : results) {
            ProviderQuoteEntity saved = providerQuoteRepository.save(toEntity(quoteRequest, call, now, call == best));
            if (call.result() == null) {
                failures++;
            } else if (call == best) {
                ProviderQuoteResult result = call.result();
                bestOffer = new QuoteOfferResponse(saved.getId(), result.sourceAmount(), result.targetAmount(),
                        result.exchangeRate(), result.totalFee(), result.expiresAt(), call.provider().name());
            }
        }

        quoteRequest.setStatus(bestOffer == null ? "FAILED" : failures == 0 ? "COMPLETED" : "PARTIAL");
        quoteRequestRepository.save(quoteRequest);
        long timeouts = results.stream().filter(call -> "TIMEOUT".equals(call.failureCode())).count();
        OperationalLog.quoteAggregation(bestOffer == null ? "failure" : failures == 0 ? "success" : "partial",
                startedAt, timeouts);
        if (bestOffer == null) {
            throw new UnprocessableEntityException("No quote is available for the requested operation");
        }
        return new QuoteResponse(quoteRequest.getId(), bestOffer);
    }

    /**
     * Valor de origem fixo: vence quem entrega mais ao destinatário. Valor de destino fixo:
     * vence o menor valor de origem, que já inclui as taxas cobradas do usuário.
     */
    private Comparator<ProviderQuoteResult> bestOfferComparator(QuoteAmountSide amountSide) {
        if (amountSide == QuoteAmountSide.SOURCE) {
            return Comparator.comparing(ProviderQuoteResult::targetAmount, Comparator.reverseOrder());
        }
        return Comparator.comparing(ProviderQuoteResult::sourceAmount);
    }

    private boolean isUsable(ProviderQuoteResult offer, QuoteRequest request) {
        return offer.sourceAmount() != null
                && offer.sourceAmount().signum() > 0
                && offer.targetAmount() != null
                && offer.targetAmount().signum() > 0
                && matchesRequestedAmount(offer, request)
                // Sem prazo de validade não há como garantir que o preço ainda vale na confirmação.
                && offer.expiresAt() != null
                && offer.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private boolean matchesRequestedAmount(ProviderQuoteResult offer, QuoteRequest request) {
        BigDecimal fixedAmount = request.amountSide() == QuoteAmountSide.SOURCE
                ? offer.sourceAmount()
                : offer.targetAmount();
        return fixedAmount.compareTo(request.amount()) == 0;
    }

    private ProviderCall call(QuoteProvider provider, QuoteContext context) {
        long startedAt = System.nanoTime();
        try {
            ProviderQuoteResult result = provider.quote(
                    context, "quote:" + context.quoteRequestId() + ":" + provider.provider().name().toLowerCase());
            return ProviderCall.success(result, elapsedMillis(startedAt));
        } catch (RuntimeException ex) {
            return ProviderCall.failure(provider.provider(), ex.getClass().getSimpleName(),
                    truncate(ex.getMessage()), elapsedMillis(startedAt));
        }
    }

    private ProviderQuoteEntity toEntity(
            QuoteRequestEntity request, ProviderCall call, OffsetDateTime createdAt, boolean selected) {
        ProviderQuoteResult result = call.result();
        return ProviderQuoteEntity.builder()
                .quoteRequest(request)
                .provider(call.provider())
                .externalQuoteId(result == null ? null : result.externalQuoteId())
                .idempotencyKey("quote:" + request.getId() + ":" + call.provider().name().toLowerCase())
                .status(result == null ? ProviderQuoteStatus.FAILED
                        : selected ? ProviderQuoteStatus.SELECTED : ProviderQuoteStatus.AVAILABLE)
                .sourceAmount(result == null ? null : result.sourceAmount())
                .targetAmount(result == null ? null : result.targetAmount())
                .exchangeRate(result == null ? null : result.exchangeRate())
                .providerFee(result == null ? null : result.providerFee())
                .serviceFee(java.math.BigDecimal.ZERO)
                .totalFee(result == null ? null : result.totalFee())
                .expiresAt(result == null ? null : result.expiresAt())
                .latencyMs(call.latencyMs())
                .failureCode(call.failureCode())
                .failureMessage(call.failureMessage())
                .providerMetadata(result == null || result.metadata() == null ? "{}" : result.metadata())
                .createdAt(createdAt)
                .build();
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String normalizeUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String normalizeLower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    private record ProviderCall(
            IntegrationProvider provider,
            ProviderQuoteResult result,
            String failureCode,
            String failureMessage,
            long latencyMs
    ) {
        private static ProviderCall success(ProviderQuoteResult result, long latencyMs) {
            return new ProviderCall(result.provider(), result, null, null, latencyMs);
        }

        private static ProviderCall failure(
                IntegrationProvider provider, String code, String message, long latencyMs) {
            return new ProviderCall(provider, null, code, message, latencyMs);
        }
    }
}
