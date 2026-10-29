package ages.vstable.backend.service.transfer;

import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.QuoteRequestEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.exception.BlindPayComplianceException;
import ages.vstable.backend.exception.BlindPayIntegrationException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayEvmPayoutRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderQuoteRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.service.quote.BlindPayProvisioningService;
import ages.vstable.backend.service.quote.BlindPayQuoteProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Máquina de estados da execução BlindPay. Com origem BRL: cria o payin, espera a
 * confirmação dos fundos (webhook ou conciliação) e só então envia o payout, a partir da
 * carteira gerenciada da empresa. Com origem em token: envia o payout direto.
 *
 * <p>Regras de segurança:
 * <ul>
 *   <li>cada chamada de escrita usa uma chave de idempotência derivada da execução, então
 *       repetir uma chamada de resultado incerto nunca cria um segundo payin/payout;</li>
 *   <li>transições são feitas sob bloqueio de linha e só a partir do estado esperado, então
 *       webhook e conciliação concorrentes não executam a mesma etapa duas vezes;</li>
 *   <li>o payout só sai com uma cotação válida que não entregue menos do que o confirmado e
 *       que não gaste mais stablecoin do que o payin creditou; caso contrário a execução
 *       fica em REQUOTE_REQUIRED, aguardando nova confirmação do usuário;</li>
 *   <li>a conclusão só é registrada quando a BlindPay informa {@code completed}.</li>
 * </ul>
 * Chamadas à BlindPay ficam fora das transações de banco.
 */
@Slf4j
@Service
public class BlindPayExecutionProcessor {

    /** Folga mínima de validade para usar uma cotação: cobre a latência até a BlindPay. */
    static final Duration QUOTE_SAFETY_MARGIN = Duration.ofSeconds(15);
    static final int MAX_PAYOUT_QUOTE_ATTEMPTS = 5;

    static final String FAILURE_PAYIN_FAILED = "PAYIN_FAILED";
    static final String FAILURE_PAYIN_REFUNDED = "PAYIN_REFUNDED";
    static final String FAILURE_PAYOUT_FAILED = "PAYOUT_FAILED";
    static final String FAILURE_PAYOUT_REFUNDED = "PAYOUT_REFUNDED";
    /** Chamada de payout sem resposta: pode ter sido executada. */
    static final String FAILURE_PAYOUT_UNCERTAIN = "PAYOUT_UNCERTAIN";
    static final String FAILURE_PAYIN_UNCERTAIN = "PAYIN_UNCERTAIN";
    static final String FAILURE_QUOTE_ATTEMPTS = "PAYOUT_QUOTE_ATTEMPTS_EXCEEDED";
    static final String FAILURE_COMPLIANCE = "COMPLIANCE_NOT_APPROVED";
    static final String FAILURE_INVALID_PAYOUT = "INVALID_PAYOUT_REQUEST";

    private static final BigDecimal CENTS = BigDecimal.valueOf(100);
    private static final Set<TransferExecutionStatus> FUNDING_STATES = Set.of(
            TransferExecutionStatus.PENDING_SUBMISSION, TransferExecutionStatus.AWAITING_FUNDS);
    private static final Set<TransferExecutionStatus> PAYOUT_STATES = Set.of(
            TransferExecutionStatus.PAYOUT_SUBMITTING, TransferExecutionStatus.PAYOUT_PROCESSING);

    private final TransferExecutionRepository executionRepository;
    private final ProviderQuoteRepository providerQuoteRepository;
    private final CompanyRepository companyRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final BaseTransactionRepository baseTransactionRepository;
    private final BlindPayGateway blindPayGateway;
    private final BlindPayProvisioningService provisioningService;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public BlindPayExecutionProcessor(
            TransferExecutionRepository executionRepository,
            ProviderQuoteRepository providerQuoteRepository,
            CompanyRepository companyRepository,
            BeneficiaryRepository beneficiaryRepository,
            BaseTransactionRepository baseTransactionRepository,
            BlindPayGateway blindPayGateway,
            BlindPayProvisioningService provisioningService,
            PlatformTransactionManager transactionManager) {
        this(executionRepository, providerQuoteRepository, companyRepository, beneficiaryRepository,
                baseTransactionRepository, blindPayGateway, provisioningService, transactionManager,
                Clock.systemUTC());
    }

    BlindPayExecutionProcessor(
            TransferExecutionRepository executionRepository,
            ProviderQuoteRepository providerQuoteRepository,
            CompanyRepository companyRepository,
            BeneficiaryRepository beneficiaryRepository,
            BaseTransactionRepository baseTransactionRepository,
            BlindPayGateway blindPayGateway,
            BlindPayProvisioningService provisioningService,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.executionRepository = executionRepository;
        this.providerQuoteRepository = providerQuoteRepository;
        this.companyRepository = companyRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.baseTransactionRepository = baseTransactionRepository;
        this.blindPayGateway = blindPayGateway;
        this.provisioningService = provisioningService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Primeira etapa de uma execução recém-registrada. */
    public void start(UUID executionId) {
        TransferExecutionEntity execution = load(executionId);
        switch (execution.getStatus()) {
            case PENDING_SUBMISSION -> startPayin(execution);
            case PAYOUT_SUBMITTING -> submitPayout(executionId);
            default -> { }
        }
    }

    /** Retoma uma execução parada (chamado pela conciliação periódica). */
    public void reconcile(UUID executionId) {
        TransferExecutionEntity execution = load(executionId);
        try {
            switch (execution.getStatus()) {
                case PENDING_SUBMISSION -> startPayin(execution);
                case AWAITING_FUNDS -> applyPayin(executionId, blindPayGateway.getPayin(execution.getExternalPayinId()));
                case FUNDS_RECEIVED -> advance(executionId);
                case PAYOUT_SUBMITTING -> submitPayout(executionId);
                case PAYOUT_PROCESSING -> applyPayout(executionId, blindPayGateway.getPayout(execution.getExternalPayoutId()));
                default -> { }
            }
        } catch (BlindPayIntegrationException ex) {
            recordFailure(executionId, ex.getProviderErrorCode(), ex.getMessage());
        }
    }

    /** Webhook payin.*: o corpo só identifica o payin; o estado vem de uma consulta à BlindPay. */
    public boolean onPayinEvent(String payinId) {
        TransferExecutionEntity execution = executionRepository.findByExternalPayinId(payinId).orElse(null);
        if (execution == null) {
            return false;
        }
        try {
            applyPayin(execution.getId(), blindPayGateway.getPayin(payinId));
        } catch (BlindPayIntegrationException ex) {
            log.warn("Could not sync BlindPay payin for execution {}: {}", execution.getId(), ex.getMessage());
            recordFailure(execution.getId(), ex.getProviderErrorCode(), ex.getMessage());
        }
        return true;
    }

    /** Webhook payout.*: o corpo só identifica o payout; o estado vem de uma consulta à BlindPay. */
    public boolean onPayoutEvent(String payoutId) {
        TransferExecutionEntity execution = executionRepository.findByExternalPayoutId(payoutId).orElse(null);
        if (execution == null) {
            return false;
        }
        try {
            applyPayout(execution.getId(), blindPayGateway.getPayout(payoutId));
        } catch (BlindPayIntegrationException ex) {
            log.warn("Could not sync BlindPay payout for execution {}: {}", execution.getId(), ex.getMessage());
            recordFailure(execution.getId(), ex.getProviderErrorCode(), ex.getMessage());
        }
        return true;
    }

    /**
     * O usuário aceitou a nova cotação de payout exibida em REQUOTE_REQUIRED. Os valores
     * informados precisam ser exatamente os da nova cotação; se ela já expirou, outra é
     * pedida e a execução volta a exigir confirmação.
     */
    public void acceptRequote(UUID executionId, UUID companyId, BigDecimal sourceAmount, BigDecimal targetAmount) {
        TransferExecutionStatus next = transactions.execute(status -> {
            executionRepository.findByIdAndCompanyId(executionId, companyId)
                    .orElseThrow(() -> new NotFoundException("Transfer not found"));
            TransferExecutionEntity execution = executionRepository.findForUpdate(executionId).orElseThrow();
            if (execution.getStatus() != TransferExecutionStatus.REQUOTE_REQUIRED) {
                throw new ConflictException("There is no pending re-quote for this transfer");
            }
            if (!sameAmount(sourceAmount, execution.getSourceAmount())
                    || !sameAmount(targetAmount, execution.getPayoutQuoteReceiverAmount())) {
                throw new UnprocessableEntityException("Confirmed amounts do not match the new quote");
            }
            if (isUsable(execution.getPayoutQuoteExpiresAt())) {
                execution.setTargetAmount(execution.getPayoutQuoteReceiverAmount());
                execution.setStatus(TransferExecutionStatus.PAYOUT_SUBMITTING);
            } else {
                execution.setStatus(TransferExecutionStatus.FUNDS_RECEIVED);
            }
            save(execution);
            return execution.getStatus();
        });
        if (next == TransferExecutionStatus.PAYOUT_SUBMITTING) {
            submitPayout(executionId);
        } else {
            advance(executionId);
        }
    }

    private void startPayin(TransferExecutionEntity execution) {
        UUID executionId = execution.getId();
        BlindPayPayinResponse payin;
        try {
            payin = blindPayGateway.createEvmPayin(
                    execution.getExternalPayinQuoteId(), idempotencyKey(execution, "payin"));
            if (payin == null || !hasText(payin.id())) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay payin response has no id");
            }
        } catch (BlindPayIntegrationException ex) {
            if (ex.isRejectedByProvider()) {
                // Ex.: cotação de payin expirada. Nenhum valor foi movimentado.
                transition(executionId, Set.of(TransferExecutionStatus.PENDING_SUBMISSION),
                        e -> fail(e, TransferExecutionStatus.FAILED, failureCode(ex, FAILURE_PAYIN_FAILED), ex.getMessage()));
            } else {
                recordFailure(executionId, FAILURE_PAYIN_UNCERTAIN, ex.getMessage());
            }
            return;
        }
        transition(executionId, Set.of(TransferExecutionStatus.PENDING_SUBMISSION), e -> {
            e.setExternalPayinId(payin.id());
            e.setFundingInstructions(payin.pixCode());
            e.setStatus(TransferExecutionStatus.AWAITING_FUNDS);
            clearFailure(e);
        });
        applyPayin(executionId, payin);
    }

    void applyPayin(UUID executionId, BlindPayPayinResponse payin) {
        if (payin == null) {
            return;
        }
        switch (normalized(payin.status())) {
            case "completed" -> {
                BigDecimal funded = fromCents(payin.receiverAmount());
                if (funded == null || funded.signum() <= 0) {
                    throw BlindPayIntegrationException.invalidResponse("BlindPay payin has no receiver amount");
                }
                boolean received = transition(executionId, FUNDING_STATES, e -> {
                    e.setFundedAmount(funded);
                    e.setStatus(TransferExecutionStatus.FUNDS_RECEIVED);
                    clearFailure(e);
                });
                if (received) {
                    advance(executionId);
                }
            }
            case "failed" -> transition(executionId, FUNDING_STATES,
                    e -> fail(e, TransferExecutionStatus.FAILED, FAILURE_PAYIN_FAILED, "BlindPay payin failed"));
            case "refunded" -> transition(executionId, FUNDING_STATES,
                    e -> fail(e, TransferExecutionStatus.REFUNDED, FAILURE_PAYIN_REFUNDED, "BlindPay payin was refunded"));
            default -> { }
        }
    }

    /** Fundos recebidos: envia o payout com a cotação atual ou pede uma nova. */
    void advance(UUID executionId) {
        TransferExecutionStatus next = transactions.execute(status -> {
            TransferExecutionEntity execution = executionRepository.findForUpdate(executionId).orElseThrow();
            if (execution.getStatus() != TransferExecutionStatus.FUNDS_RECEIVED) {
                return null;
            }
            if (canSubmitCurrentPayoutQuote(execution)) {
                execution.setStatus(TransferExecutionStatus.PAYOUT_SUBMITTING);
            } else if (execution.getPayoutQuoteAttempts() >= MAX_PAYOUT_QUOTE_ATTEMPTS) {
                fail(execution, TransferExecutionStatus.RECONCILIATION_REQUIRED, FAILURE_QUOTE_ATTEMPTS,
                        "Funds are held in the BlindPay wallet; payout needs manual follow-up");
            } else {
                return TransferExecutionStatus.FUNDS_RECEIVED;
            }
            save(execution);
            return execution.getStatus();
        });
        if (next == TransferExecutionStatus.PAYOUT_SUBMITTING) {
            submitPayout(executionId);
        } else if (next == TransferExecutionStatus.FUNDS_RECEIVED) {
            requotePayout(executionId);
        }
    }

    /**
     * A cotação original expirou ou não cabe no valor creditado. Pede uma nova sobre o valor
     * efetivamente creditado ({@code currency_type=sender}, sem somar taxas por fora, para
     * nunca gastar mais do que o payin trouxe). Se ela entregar ao menos o confirmado, o
     * payout segue; senão o usuário precisa confirmar o novo valor.
     */
    private void requotePayout(UUID executionId) {
        TransferExecutionEntity execution = load(executionId);
        PayoutContext context = payoutContext(execution);
        int attempt = execution.getPayoutQuoteAttempts() + 1;
        BlindPayQuoteResponse quote;
        try {
            String bankAccountId = provisioningService.ensureBankAccount(context.company(), context.beneficiary());
            quote = blindPayGateway.createQuote(BlindPayQuoteRequest.builder()
                            .bankAccountId(bankAccountId)
                            .network(BlindPayApi.BlockchainWallet.Network.valueOf(context.network()))
                            .token(BlindPayApi.VirtualAccount.Token.valueOf(context.token()))
                            .currencyType(BlindPayApi.Quote.CurrencyType.sender)
                            .requestAmount(toCents(execution.getFundedAmount()))
                            .coverFees(false)
                            .description(context.description())
                            .build(),
                    idempotencyKey(execution, "payout-quote:" + attempt));
        } catch (BlindPayComplianceException ex) {
            transition(executionId, Set.of(TransferExecutionStatus.FUNDS_RECEIVED),
                    e -> fail(e, TransferExecutionStatus.RECONCILIATION_REQUIRED, FAILURE_COMPLIANCE, ex.getMessage()));
            return;
        } catch (IllegalArgumentException ex) {
            // Ex.: valor creditado abaixo do mínimo de payout. Repetir não resolve.
            transition(executionId, Set.of(TransferExecutionStatus.FUNDS_RECEIVED),
                    e -> fail(e, TransferExecutionStatus.RECONCILIATION_REQUIRED, FAILURE_INVALID_PAYOUT, ex.getMessage()));
            return;
        } catch (BlindPayIntegrationException ex) {
            recordFailure(executionId, ex.getProviderErrorCode(), ex.getMessage());
            return;
        }

        BigDecimal sender = fromCents(quote == null ? null : quote.senderAmount());
        BigDecimal receiver = fromCents(quote == null ? null : quote.receiverAmount());
        if (quote == null || !hasText(quote.id()) || sender == null || sender.signum() <= 0
                || receiver == null || receiver.signum() <= 0) {
            recordFailure(executionId, null, "BlindPay returned an incomplete payout quote");
            return;
        }
        boolean submit = transition(executionId, Set.of(TransferExecutionStatus.FUNDS_RECEIVED), e -> {
            e.setPayoutQuoteAttempts(attempt);
            e.setExternalPayoutQuoteId(quote.id());
            e.setPayoutQuoteSenderAmount(sender);
            e.setPayoutQuoteReceiverAmount(receiver);
            e.setPayoutQuoteExpiresAt(BlindPayQuoteProvider.fromEpochMillis(quote.expiresAt()));
            clearFailure(e);
            e.setStatus(canSubmitCurrentPayoutQuote(e)
                    ? TransferExecutionStatus.PAYOUT_SUBMITTING
                    : TransferExecutionStatus.REQUOTE_REQUIRED);
        }) && load(executionId).getStatus() == TransferExecutionStatus.PAYOUT_SUBMITTING;
        if (submit) {
            submitPayout(executionId);
        }
    }

    void submitPayout(UUID executionId) {
        TransferExecutionEntity execution = load(executionId);
        if (execution.getStatus() != TransferExecutionStatus.PAYOUT_SUBMITTING) {
            return;
        }
        BlindPayPayoutResponse payout;
        try {
            PayoutContext context = payoutContext(execution);
            BlindPayProvisioningService.ManagedWallet wallet =
                    provisioningService.ensureManagedWallet(context.company(), context.network());
            payout = blindPayGateway.createEvmPayout(
                    BlindPayEvmPayoutRequest.fromManagedWallet(
                            execution.getExternalPayoutQuoteId(), wallet.address()),
                    idempotencyKey(execution, "payout:" + execution.getPayoutQuoteAttempts()));
            if (payout == null || !hasText(payout.id())) {
                throw BlindPayIntegrationException.invalidResponse("BlindPay payout response has no id");
            }
        } catch (BlindPayComplianceException ex) {
            payoutRejected(executionId, FAILURE_COMPLIANCE, ex.getMessage());
            return;
        } catch (BlindPayIntegrationException ex) {
            if (ex.isRejectedByProvider()) {
                payoutRejected(executionId, failureCode(ex, FAILURE_PAYOUT_FAILED), ex.getMessage());
            } else {
                // Sem resposta: o payout pode ter sido criado. A conciliação repete a chamada
                // com a mesma chave de idempotência, que devolve o payout original.
                recordFailure(executionId, FAILURE_PAYOUT_UNCERTAIN, ex.getMessage());
            }
            return;
        }
        transition(executionId, Set.of(TransferExecutionStatus.PAYOUT_SUBMITTING), e -> {
            e.setExternalPayoutId(payout.id());
            e.setStatus(TransferExecutionStatus.PAYOUT_PROCESSING);
            clearFailure(e);
        });
        applyPayout(executionId, payout);
    }

    /**
     * A BlindPay recusou o payout. Sem stablecoins creditadas por esta execução (origem em
     * token), nada saiu: falha. Com fundos na carteira, só é seguro recotar se nenhuma
     * tentativa anterior ficou sem resposta e a cotação já expirou; em qualquer outro caso
     * o payout pode ter sido feito, então a execução vai para conciliação manual.
     */
    private void payoutRejected(UUID executionId, String code, String message) {
        boolean requote = transition(executionId, Set.of(TransferExecutionStatus.PAYOUT_SUBMITTING), e -> {
            if (e.getFundedAmount() == null) {
                fail(e, TransferExecutionStatus.FAILED, code, message);
            } else if (!FAILURE_PAYOUT_UNCERTAIN.equals(e.getFailureCode())
                    && !isUsable(e.getPayoutQuoteExpiresAt())
                    && !FAILURE_COMPLIANCE.equals(code)) {
                e.setStatus(TransferExecutionStatus.FUNDS_RECEIVED);
                e.setFailureCode(truncate(code, 100));
                e.setFailureMessage(truncate(message, 500));
            } else {
                fail(e, TransferExecutionStatus.RECONCILIATION_REQUIRED, code, message);
            }
        }) && load(executionId).getStatus() == TransferExecutionStatus.FUNDS_RECEIVED;
        if (requote) {
            advance(executionId);
        }
    }

    void applyPayout(UUID executionId, BlindPayPayoutResponse payout) {
        if (payout == null) {
            return;
        }
        switch (normalized(payout.status())) {
            case "completed" -> transition(executionId, PAYOUT_STATES, e -> {
                e.setStatus(TransferExecutionStatus.COMPLETED);
                e.setCompletedAt(now());
                clearFailure(e);
            });
            case "failed" -> transition(executionId, PAYOUT_STATES,
                    e -> fail(e, TransferExecutionStatus.FAILED, FAILURE_PAYOUT_FAILED, "BlindPay payout failed"));
            case "refunded" -> transition(executionId, PAYOUT_STATES,
                    e -> fail(e, TransferExecutionStatus.REFUNDED, FAILURE_PAYOUT_REFUNDED, "BlindPay payout was refunded"));
            default -> { }
        }
    }

    private boolean canSubmitCurrentPayoutQuote(TransferExecutionEntity execution) {
        return hasText(execution.getExternalPayoutQuoteId())
                && isUsable(execution.getPayoutQuoteExpiresAt())
                && execution.getPayoutQuoteSenderAmount() != null
                && execution.getPayoutQuoteReceiverAmount() != null
                && execution.getFundedAmount() != null
                && execution.getPayoutQuoteSenderAmount().compareTo(execution.getFundedAmount()) <= 0
                && execution.getPayoutQuoteReceiverAmount().compareTo(execution.getTargetAmount()) >= 0;
    }

    private boolean isUsable(OffsetDateTime expiresAt) {
        return expiresAt != null && expiresAt.isAfter(now().plus(QUOTE_SAFETY_MARGIN));
    }

    /**
     * Aplica {@code change} sob bloqueio de linha se a execução estiver num dos estados
     * esperados. Devolve {@code false} quando outro processo já a moveu adiante.
     */
    private boolean transition(
            UUID executionId, Set<TransferExecutionStatus> expected, Consumer<TransferExecutionEntity> change) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            TransferExecutionEntity execution = executionRepository.findForUpdate(executionId).orElseThrow();
            if (!expected.contains(execution.getStatus())) {
                return false;
            }
            change.accept(execution);
            save(execution);
            return true;
        }));
    }

    /** Registra o erro sem mudar o estado: a conciliação tentará de novo. */
    private void recordFailure(UUID executionId, String code, String message) {
        transactions.executeWithoutResult(status -> executionRepository.findForUpdate(executionId)
                .filter(execution -> !execution.getStatus().isTerminal())
                .ifPresent(execution -> {
                    // Uma tentativa incerta continua incerta até ser resolvida.
                    if (!FAILURE_PAYOUT_UNCERTAIN.equals(execution.getFailureCode())
                            || execution.getStatus() != TransferExecutionStatus.PAYOUT_SUBMITTING) {
                        execution.setFailureCode(truncate(code, 100));
                    }
                    execution.setFailureMessage(truncate(message, 500));
                    save(execution);
                }));
    }

    private void save(TransferExecutionEntity execution) {
        OffsetDateTime now = now();
        execution.setUpdatedAt(now);
        executionRepository.save(execution);
        syncTransaction(execution, now);
    }

    /** Mantém o histórico de transações (base_transactions) coerente com a execução. */
    private void syncTransaction(TransferExecutionEntity execution, OffsetDateTime now) {
        if (execution.getTransactionId() == null) {
            return;
        }
        baseTransactionRepository.findById(execution.getTransactionId()).ifPresent(transaction -> {
            TransactionStatus status = transactionStatus(execution);
            transaction.setStatus(status);
            transaction.setForeignAmount(execution.getTargetAmount().setScale(2, RoundingMode.DOWN));
            transaction.setUpdatedAt(now);
            if (status == TransactionStatus.SETTLED && transaction.getSettledAt() == null) {
                transaction.setSettledAt(now);
            }
            baseTransactionRepository.save(transaction);
        });
    }

    static TransactionStatus transactionStatus(TransferExecutionEntity execution) {
        return switch (execution.getStatus()) {
            case PENDING_SUBMISSION, AWAITING_FUNDS -> TransactionStatus.AWAITING_PAYMENT;
            case FUNDS_RECEIVED, PAYOUT_SUBMITTING, PAYOUT_PROCESSING, SUBMITTED -> TransactionStatus.PROCESSING;
            case REQUOTE_REQUIRED, RECONCILIATION_REQUIRED -> TransactionStatus.HELD;
            case COMPLETED -> TransactionStatus.SETTLED;
            case FAILED -> TransactionStatus.FAILED;
            case REFUNDED -> TransactionStatus.CANCELED;
        };
    }

    private record PayoutContext(
            CompanyEntity company, BeneficiaryEntity beneficiary, String token, String network, String description) {
    }

    private PayoutContext payoutContext(TransferExecutionEntity execution) {
        return transactions.execute(status -> {
            QuoteRequestEntity request = providerQuoteRepository.findById(execution.getProviderQuoteId())
                    .orElseThrow()
                    .getQuoteRequest();
            return new PayoutContext(
                    companyRepository.findById(execution.getCompanyId()).orElseThrow(),
                    beneficiaryRepository.findById(execution.getBeneficiaryId()).orElseThrow(),
                    request.getToken().toUpperCase(Locale.ROOT),
                    request.getBlockchainNetwork().toLowerCase(Locale.ROOT),
                    request.getDescription());
        });
    }

    private TransferExecutionEntity load(UUID executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new NotFoundException("Transfer not found"));
    }

    private void fail(TransferExecutionEntity execution, TransferExecutionStatus status, String code, String message) {
        execution.setStatus(status);
        execution.setFailureCode(truncate(code, 100));
        execution.setFailureMessage(truncate(message, 500));
    }

    private void clearFailure(TransferExecutionEntity execution) {
        execution.setFailureCode(null);
        execution.setFailureMessage(null);
    }

    /** Chave estável por execução e etapa: repetir a chamada nunca duplica a operação. */
    static String idempotencyKey(TransferExecutionEntity execution, String step) {
        return "transfer-execution:" + execution.getId() + ":" + step;
    }

    private String failureCode(BlindPayIntegrationException ex, String fallback) {
        return hasText(ex.getProviderErrorCode()) ? ex.getProviderErrorCode() : fallback;
    }

    private static boolean sameAmount(BigDecimal confirmed, BigDecimal expected) {
        return confirmed != null && expected != null && confirmed.compareTo(expected) == 0;
    }

    private static String normalized(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    static BigDecimal fromCents(BigDecimal cents) {
        return cents == null ? null : cents.divide(CENTS, 2, RoundingMode.DOWN);
    }

    /** Arredonda para baixo: nunca pede um payout maior do que o valor creditado. */
    static long toCents(BigDecimal amount) {
        return amount.multiply(CENTS).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
