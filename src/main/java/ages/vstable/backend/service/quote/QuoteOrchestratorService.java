package ages.vstable.backend.service.quote;

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
        QuoteContext context = new QuoteContext(quoteRequest.getId(), company, beneficiary, normalized);

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
        List<QuoteOfferResponse> offers = new ArrayList<>();
        int failures = 0;
        for (ProviderCall call : results) {
            ProviderQuoteEntity saved = providerQuoteRepository.save(toEntity(quoteRequest, call, now));
            if (call.result() != null) {
                ProviderQuoteResult result = call.result();
                offers.add(new QuoteOfferResponse(saved.getId(), result.sourceAmount(), result.targetAmount(),
                        result.exchangeRate(), result.totalFee(), result.expiresAt()));
            } else {
                failures++;
            }
        }

        QuoteOfferResponse bestOffer = offers.stream()
                .filter(offer -> isUsable(offer, request))
                .min(bestOfferComparator(request.amountSide()))
                .orElse(null);
        quoteRequest.setStatus(bestOffer == null ? "FAILED" : failures == 0 ? "COMPLETED" : "PARTIAL");
        quoteRequestRepository.save(quoteRequest);
        if (bestOffer == null) {
            throw new UnprocessableEntityException("No quote is available for the requested operation");
        }
        return new QuoteResponse(quoteRequest.getId(), bestOffer);
    }

    private Comparator<QuoteOfferResponse> bestOfferComparator(QuoteAmountSide amountSide) {
        Comparator<QuoteOfferResponse> byEffectivePrice;
        if (amountSide == QuoteAmountSide.SOURCE) {
            byEffectivePrice = Comparator.comparing(
                    QuoteOfferResponse::targetAmount,
                    Comparator.reverseOrder());
        } else {
            byEffectivePrice = Comparator.comparing(QuoteOfferResponse::sourceAmount);
        }
        return byEffectivePrice;
    }

    private boolean isUsable(QuoteOfferResponse offer, QuoteRequest request) {
        return offer.sourceAmount() != null
                && offer.sourceAmount().signum() > 0
                && offer.targetAmount() != null
                && offer.targetAmount().signum() > 0
                && matchesRequestedAmount(offer, request)
                && (offer.expiresAt() == null
                    || offer.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private boolean matchesRequestedAmount(QuoteOfferResponse offer, QuoteRequest request) {
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

    private ProviderQuoteEntity toEntity(QuoteRequestEntity request, ProviderCall call, OffsetDateTime createdAt) {
        ProviderQuoteResult result = call.result();
        return ProviderQuoteEntity.builder()
                .quoteRequest(request)
                .provider(call.provider())
                .externalQuoteId(result == null ? null : result.externalQuoteId())
                .idempotencyKey("quote:" + request.getId() + ":" + call.provider().name().toLowerCase())
                .status(result == null ? "FAILED" : "AVAILABLE")
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
