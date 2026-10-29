package ages.vstable.backend.service.transfer;

import ages.vstable.backend.dto.transfer.ConfirmOfferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferExecutionResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.ProviderQuoteEntity;
import ages.vstable.backend.entity.QuoteRequestEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.QuotePriceChangedException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.ProviderQuoteRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.service.quote.BlindPayQuoteProvider;
import ages.vstable.backend.service.quote.ProviderQuoteStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Confirmação de uma oferta da cotação agregada e execução pelo provedor vencedor.
 *
 * <p>Garantias:
 * <ul>
 *   <li>só a oferta vencedora ({@code SELECTED}) da empresa do usuário, ainda válida e com
 *       os mesmos valores exibidos, pode ser confirmada;</li>
 *   <li>cada oferta gera no máximo uma execução (unicidade em banco); repetir a confirmação
 *       devolve a execução existente em vez de executar de novo;</li>
 *   <li>a Avenia recota no momento da execução: se o preço piorou, nada é criado e o
 *       usuário precisa de uma nova cotação;</li>
 *   <li>a BlindPay segue a máquina de estados de {@link BlindPayExecutionProcessor}.</li>
 * </ul>
 */
@Slf4j
@Service
public class TransferExecutionService {

    static final Duration CONFIRMATION_MARGIN = Duration.ofSeconds(10);
    static final String FAILURE_PRICE_CHANGED = "PRICE_CHANGED";
    static final String FAILURE_PROVIDER_REJECTED = "PROVIDER_REJECTED";
    static final String FAILURE_UNCERTAIN = "PROVIDER_RESULT_UNKNOWN";

    private static final String BRL = "BRL";
    private static final JsonMapper METADATA_MAPPER = JsonMapper.builder().build();

    private final ProviderQuoteRepository providerQuoteRepository;
    private final TransferExecutionRepository executionRepository;
    private final ImportTransactionRepository importTransactionRepository;
    private final TransferService transferService;
    private final BlindPayExecutionProcessor blindPayProcessor;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public TransferExecutionService(
            ProviderQuoteRepository providerQuoteRepository,
            TransferExecutionRepository executionRepository,
            ImportTransactionRepository importTransactionRepository,
            TransferService transferService,
            BlindPayExecutionProcessor blindPayProcessor,
            PlatformTransactionManager transactionManager) {
        this(providerQuoteRepository, executionRepository, importTransactionRepository, transferService,
                blindPayProcessor, transactionManager, Clock.systemUTC());
    }

    TransferExecutionService(
            ProviderQuoteRepository providerQuoteRepository,
            TransferExecutionRepository executionRepository,
            ImportTransactionRepository importTransactionRepository,
            TransferService transferService,
            BlindPayExecutionProcessor blindPayProcessor,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.providerQuoteRepository = providerQuoteRepository;
        this.executionRepository = executionRepository;
        this.importTransactionRepository = importTransactionRepository;
        this.transferService = transferService;
        this.blindPayProcessor = blindPayProcessor;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public TransferExecutionResponse confirm(UserEntity user, UUID offerId, ConfirmOfferRequest confirmation) {
        Registration registration;
        try {
            registration = transactions.execute(status -> register(user, offerId, confirmation));
        } catch (DataIntegrityViolationException ex) {
            // Confirmação concorrente da mesma oferta: a outra requisição já registrou a execução.
            return toResponse(executionRepository.findByProviderQuoteId(offerId)
                    .orElseThrow(() -> ex));
        }
        TransferExecutionEntity execution = registration.execution();
        if (!registration.created()) {
            return toResponse(execution);
        }
        if (execution.getProvider() == IntegrationProvider.AVENIA) {
            executeAvenia(execution, registration);
        } else {
            startBlindPay(execution.getId());
        }
        return toResponse(load(execution.getId()));
    }

    public TransferExecutionResponse find(UserEntity user, UUID executionId) {
        return toResponse(executionRepository.findByIdAndCompanyId(executionId, user.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Transfer not found")));
    }

    public TransferExecutionResponse confirmRequote(
            UserEntity user, UUID executionId, ConfirmOfferRequest confirmation) {
        blindPayProcessor.acceptRequote(executionId, user.getCompanyId(),
                confirmation.sourceAmount(), confirmation.targetAmount());
        return find(user, executionId);
    }

    private record Registration(
            TransferExecutionEntity execution, boolean created, QuoteAmountSide amountSide,
            CreateTransferRequest aveniaRequest) {
    }

    private Registration register(UserEntity user, UUID offerId, ConfirmOfferRequest confirmation) {
        ProviderQuoteEntity offer = providerQuoteRepository.findById(offerId)
                .orElseThrow(() -> new NotFoundException("Offer not found"));
        QuoteRequestEntity request = offer.getQuoteRequest();
        if (user.getCompanyId() == null || !user.getCompanyId().equals(request.getCompany().getId())) {
            throw new NotFoundException("Offer not found");
        }

        TransferExecutionEntity existing = executionRepository.findByProviderQuoteId(offerId).orElse(null);
        if (existing != null) {
            requireConfirmedAmounts(confirmation, existing.getSourceAmount(), offer.getTargetAmount());
            return new Registration(existing, false, request.getAmountSide(), null);
        }
        if (!ProviderQuoteStatus.SELECTED.equals(offer.getStatus())) {
            throw new UnprocessableEntityException("Only the best offer of a quote can be confirmed");
        }
        requireConfirmedAmounts(confirmation, offer.getSourceAmount(), offer.getTargetAmount());
        OffsetDateTime now = now();
        if (offer.getExpiresAt() == null || !offer.getExpiresAt().isAfter(now.plus(CONFIRMATION_MARGIN))) {
            throw new UnprocessableEntityException("The offer has expired; request a new quote");
        }
        BeneficiaryEntity beneficiary = request.getBeneficiary();
        if (!request.getCompany().getId().equals(beneficiary.getCompany().getId())) {
            throw new NotFoundException("Beneficiary not found");
        }

        TransferExecutionEntity execution = TransferExecutionEntity.builder()
                .providerQuoteId(offer.getId())
                .companyId(request.getCompany().getId())
                .requestedBy(user.getId())
                .beneficiaryId(beneficiary.getId())
                .provider(offer.getProvider())
                .status(TransferExecutionStatus.PENDING_SUBMISSION)
                .sourceCurrency(request.getSourceCurrency())
                .targetCurrency(request.getTargetCurrency())
                .sourceAmount(offer.getSourceAmount())
                .targetAmount(offer.getTargetAmount())
                .totalFee(offer.getTotalFee())
                .createdAt(now)
                .updatedAt(now)
                .build();
        CreateTransferRequest aveniaRequest = null;
        if (offer.getProvider() == IntegrationProvider.BLINDPAY) {
            prepareBlindPay(execution, offer, request, now);
        } else {
            aveniaRequest = aveniaRequest(request);
        }

        offer.setStatus(ProviderQuoteStatus.CONFIRMED);
        providerQuoteRepository.save(offer);
        executionRepository.saveAndFlush(execution);
        return new Registration(execution, true, request.getAmountSide(), aveniaRequest);
    }

    /**
     * Origem BRL: guarda as cotações de payin e de payout compostas na oferta; o payin é
     * criado primeiro. Origem em token: a própria cotação da oferta é a de payout, enviada
     * direto do saldo da carteira gerenciada.
     */
    private void prepareBlindPay(
            TransferExecutionEntity execution, ProviderQuoteEntity offer, QuoteRequestEntity request,
            OffsetDateTime now) {
        boolean fundedWithBrl = BRL.equalsIgnoreCase(request.getSourceCurrency());
        if (fundedWithBrl) {
            JsonNode metadata = metadata(offer);
            execution.setExternalPayinQuoteId(requiredText(metadata, BlindPayQuoteProvider.METADATA_FUNDING_QUOTE_ID));
            execution.setExternalPayoutQuoteId(requiredText(metadata, BlindPayQuoteProvider.METADATA_PAYOUT_QUOTE_ID));
            execution.setPayoutQuoteSenderAmount(new BigDecimal(
                    requiredText(metadata, BlindPayQuoteProvider.METADATA_PAYOUT_SENDER_AMOUNT)));
            execution.setPayoutQuoteReceiverAmount(offer.getTargetAmount());
            JsonNode payoutExpiresAt = metadata.get(BlindPayQuoteProvider.METADATA_PAYOUT_EXPIRES_AT);
            execution.setPayoutQuoteExpiresAt(payoutExpiresAt == null || !payoutExpiresAt.canConvertToLong()
                    ? offer.getExpiresAt()
                    : BlindPayQuoteProvider.fromEpochMillis(payoutExpiresAt.asLong()));
        } else {
            if (offer.getExternalQuoteId() == null || offer.getExternalQuoteId().isBlank()) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay offer has no quote id");
            }
            execution.setExternalPayoutQuoteId(offer.getExternalQuoteId());
            execution.setPayoutQuoteSenderAmount(offer.getSourceAmount());
            execution.setPayoutQuoteReceiverAmount(offer.getTargetAmount());
            execution.setPayoutQuoteExpiresAt(offer.getExpiresAt());
            execution.setStatus(TransferExecutionStatus.PAYOUT_SUBMITTING);
        }

        ImportTransactionEntity transaction = ImportTransactionEntity.builder()
                .companyId(execution.getCompanyId())
                .creatorUserId(execution.getRequestedBy())
                .status(BlindPayExecutionProcessor.transactionStatus(execution))
                .foreignCurrency(request.getTargetCurrency())
                .foreignAmount(offer.getTargetAmount().setScale(2, RoundingMode.DOWN))
                .settlementAmountBrl(fundedWithBrl ? offer.getSourceAmount().setScale(2, RoundingMode.UP) : null)
                .serviceFeeBrl(fundedWithBrl && offer.getTotalFee() != null
                        ? offer.getTotalFee().setScale(2, RoundingMode.UP) : null)
                .exchangeRate(offer.getExchangeRate() == null ? null
                        : offer.getExchangeRate().setScale(6, RoundingMode.HALF_UP))
                .beneficiaryId(execution.getBeneficiaryId())
                .transferMethod(fundedWithBrl ? transferMethod(request.getSourcePaymentMethod())
                        : TransferMethod.ACCOUNT_BALANCE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        importTransactionRepository.save(transaction);
        execution.setTransactionId(transaction.getId());
    }

    private void startBlindPay(UUID executionId) {
        try {
            blindPayProcessor.start(executionId);
        } catch (BlindPayIntegrationException ex) {
            // A execução já está registrada; a conciliação periódica retoma a etapa pendente.
            log.warn("BlindPay execution {} will be resumed by reconciliation: {}", executionId, ex.getMessage());
        }
    }

    /**
     * Executa pela rota existente da Avenia ({@link TransferService}), recusando a cotação
     * nova se ela for pior do que a confirmada. Se a falha acontece depois de pedir o ticket
     * e não é uma recusa explícita, não há como saber se o ticket existe: a execução vai para
     * conciliação manual em vez de ser repetida (a Avenia não aceita chave de idempotência).
     */
    private void executeAvenia(TransferExecutionEntity execution, Registration registration) {
        AtomicBoolean ticketRequested = new AtomicBoolean(false);
        try {
            TransferService.ExecutedTransfer result = transferService.createConfirmedOffer(
                    registration.aveniaRequest(), execution.getRequestedBy(), execution.getCompanyId(),
                    quote -> {
                        requireNotWorse(execution, registration.amountSide(), quote);
                        ticketRequested.set(true);
                    });
            updateAvenia(execution.getId(), e -> {
                e.setStatus(aveniaStatus(result.status()));
                e.setTransactionId(result.transactionId());
                e.setExternalTicketId(result.ticketId() == null ? null : result.ticketId().toString());
                if (e.getStatus() == TransferExecutionStatus.COMPLETED) {
                    e.setCompletedAt(now());
                }
            });
        } catch (QuotePriceChangedException ex) {
            updateAvenia(execution.getId(), e -> fail(e, TransferExecutionStatus.FAILED, FAILURE_PRICE_CHANGED, ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            boolean rejected = ex instanceof UnprocessableEntityException
                    || ex instanceof NotFoundException
                    || ex instanceof AveniaIntegrationException avenia && isRejection(avenia);
            if (!ticketRequested.get() || rejected) {
                updateAvenia(execution.getId(), e -> fail(e, TransferExecutionStatus.FAILED,
                        FAILURE_PROVIDER_REJECTED, ex.getMessage()));
            } else {
                log.error("Avenia ticket result is unknown for execution {}", execution.getId());
                updateAvenia(execution.getId(), e -> fail(e, TransferExecutionStatus.RECONCILIATION_REQUIRED,
                        FAILURE_UNCERTAIN, ex.getMessage()));
            }
            throw ex;
        }
    }

    private void requireNotWorse(
            TransferExecutionEntity execution, QuoteAmountSide amountSide, TransferQuoteResponse quote) {
        boolean worse = amountSide == QuoteAmountSide.SOURCE
                ? quote.destination().amount().compareTo(execution.getTargetAmount()) < 0
                : quote.source().amount().compareTo(execution.getSourceAmount()) > 0;
        if (worse) {
            throw new QuotePriceChangedException(
                    "The price changed since the offer was confirmed; request a new quote and confirm it again");
        }
    }

    private boolean isRejection(AveniaIntegrationException ex) {
        return !ex.isRetryable() && ex.getProviderStatus() != null
                && ex.getProviderStatus() >= 400 && ex.getProviderStatus() < 500;
    }

    private void updateAvenia(UUID executionId, java.util.function.Consumer<TransferExecutionEntity> change) {
        transactions.executeWithoutResult(status -> executionRepository.findForUpdate(executionId)
                .ifPresent(execution -> {
                    change.accept(execution);
                    execution.setUpdatedAt(now());
                    executionRepository.save(execution);
                }));
    }

    private static TransferExecutionStatus aveniaStatus(TransactionStatus status) {
        return switch (status) {
            case SETTLED -> TransferExecutionStatus.COMPLETED;
            case FAILED, CANCELED, EXPIRED -> TransferExecutionStatus.FAILED;
            default -> TransferExecutionStatus.SUBMITTED;
        };
    }

    private CreateTransferRequest aveniaRequest(QuoteRequestEntity request) {
        CreateTransferRequest transfer = new CreateTransferRequest();
        transfer.setAmount(request.getRequestedAmount());
        transfer.setAmountType(request.getAmountSide() == QuoteAmountSide.SOURCE
                ? ages.vstable.backend.entity.enums.TransferAmountType.SOURCE
                : ages.vstable.backend.entity.enums.TransferAmountType.DESTINATION);
        transfer.setSourceCurrency(request.getSourceCurrency());
        transfer.setDestinationCurrency(request.getTargetCurrency());
        transfer.setPaymentMethod(TransferMethod.PIX);
        transfer.setBeneficiaryId(request.getBeneficiary().getId());
        transfer.setDescription(request.getDescription() == null || request.getDescription().length() <= 68
                ? request.getDescription()
                : request.getDescription().substring(0, 68));
        return transfer;
    }

    private void requireConfirmedAmounts(ConfirmOfferRequest confirmation, BigDecimal source, BigDecimal target) {
        if (source == null || target == null
                || confirmation.sourceAmount().compareTo(source) != 0
                || confirmation.targetAmount().compareTo(target) != 0) {
            throw new UnprocessableEntityException("Confirmed amounts do not match the offer");
        }
    }

    private JsonNode metadata(ProviderQuoteEntity offer) {
        try {
            return METADATA_MAPPER.readTree(offer.getProviderMetadata() == null ? "{}" : offer.getProviderMetadata());
        } catch (JacksonException ex) {
            throw BlindPayIntegrationException.invalidResponse("BlindPay offer metadata is invalid");
        }
    }

    private String requiredText(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        if (value == null || value.isNull() || value.asString().isBlank()) {
            throw BlindPayIntegrationException.invalidResponse("BlindPay offer metadata has no " + field);
        }
        return value.asString();
    }

    private TransferMethod transferMethod(String sourcePaymentMethod) {
        return "ted".equals(sourcePaymentMethod.trim().toLowerCase(Locale.ROOT)) ? TransferMethod.TED : TransferMethod.PIX;
    }

    private void fail(TransferExecutionEntity execution, TransferExecutionStatus status, String code, String message) {
        execution.setStatus(status);
        execution.setFailureCode(code);
        execution.setFailureMessage(message == null || message.length() <= 500 ? message : message.substring(0, 500));
    }

    private TransferExecutionEntity load(UUID executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new NotFoundException("Transfer not found"));
    }

    TransferExecutionResponse toResponse(TransferExecutionEntity execution) {
        TransferExecutionResponse.Requote requote = execution.getStatus() == TransferExecutionStatus.REQUOTE_REQUIRED
                ? new TransferExecutionResponse.Requote(
                        new MoneyAmount(execution.getSourceAmount(), execution.getSourceCurrency()),
                        new MoneyAmount(execution.getPayoutQuoteReceiverAmount(), execution.getTargetCurrency()),
                        execution.getPayoutQuoteExpiresAt())
                : null;
        return new TransferExecutionResponse(
                execution.getId(),
                execution.getProviderQuoteId(),
                execution.getTransactionId(),
                execution.getProvider().name(),
                execution.getStatus(),
                new MoneyAmount(execution.getSourceAmount(), execution.getSourceCurrency()),
                new MoneyAmount(execution.getTargetAmount(), execution.getTargetCurrency()),
                execution.getTotalFee(),
                execution.getStatus() == TransferExecutionStatus.AWAITING_FUNDS ? execution.getFundingInstructions() : null,
                requote,
                execution.getFailureCode(),
                execution.getCreatedAt(),
                execution.getUpdatedAt());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
