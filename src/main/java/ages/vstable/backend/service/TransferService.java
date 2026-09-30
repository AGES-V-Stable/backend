package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.ExchangeRateInfo;
import ages.vstable.backend.dto.transfer.FeeInfo;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaAppliedFee;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.ImportTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Fronteira entre o contrato público de transferências (dto/transfer, sem nenhum
 * identificador da Avenia) e a integração de baixo nível ({@link AveniaTransferService},
 * dto/external/avenia). Nenhum tipo de external/avenia deve escapar desta classe.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    // INTERNAL representa a rota "saldo em conta" (ACCOUNT_BALANCE): a entrada sai sempre do
    // saldo da subconta, e é também a saída usada para moedas fiduciárias enquanto o trilho
    // bancário (SWIFT/ACH/SEPA) não está definido — não confirmado contra o sandbox real.
    private static final String DEFAULT_PAYMENT_METHOD = "INTERNAL";

    // Stablecoins saem pela blockchain, e a Avenia exige a rede como outputPaymentMethod.
    // Polygon é a rede padrão quando o beneficiário (ou a cotação de exibição, que ainda
    // não conhece o beneficiário) não informa outra.
    private static final String DEFAULT_BLOCKCHAIN_OUTPUT = "POLYGON";
    private static final String DEFAULT_STABLECOIN = "USDC";
    private static final Set<String> STABLECOINS = Set.of("USDC", "USDCE", "USDT", "BRLA", "EURC");

    // Nenhuma entidade hoje guarda a moeda de operação da empresa; BRL é a única moeda
    // que aparece explicitamente hoje (CompanyEntity.availableBalanceBrl). Ver
    // docs/transfers-quote-and-creation.md seção 4 — decisão a revisitar com o time.
    private static final String DEFAULT_SOURCE_CURRENCY = "BRL";

    private final AveniaTransferService aveniaTransferService;
    private final BeneficiaryRepository beneficiaryRepository;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final AveniaSubAccountProvisioningService subAccountProvisioningService;
    private final BaseTransactionRepository baseTransactionRepository;
    private final ImportTransactionRepository importTransactionRepository;

    public TransferQuoteResponse quote(TransferQuoteRequest request, UUID currentUserId) {
        String sourceCurrency = resolveSourceCurrency(request.getSourceCurrency());
        String destinationCurrency = resolveDestinationCurrency(request.getDestinationCurrency());
        String subAccountId = resolveSubAccountId(currentUserId);

        AveniaQuoteRequest aveniaRequest = buildAveniaQuoteRequest(
                request.getAmount(), request.getAmountType(), sourceCurrency, destinationCurrency,
                outputPaymentMethodFor(destinationCurrency, null), subAccountId);
        AveniaQuoteResponse aveniaResponse = aveniaTransferService.createQuote(aveniaRequest);

        return toTransferQuoteResponse(aveniaResponse);
    }

    /**
     * Cria a transferência: gera uma cotação nova na Avenia (nunca reaproveita a de
     * exibição — é {@link AveniaTransferService#createTransfer} que garante isso) e, com o
     * quoteToken dela, cria o ticket. Se a cotação falhar, a exceção sobe antes de chegar
     * a criar qualquer ticket — e, portanto, antes de persistir qualquer coisa.
     *
     * {@code sourceCurrency} é restrito a BRL aqui (diferente de {@link #quote}): é a única
     * moeda em que a empresa tem saldo registrado ({@code CompanyEntity.availableBalanceBrl}),
     * e é dela que depende {@code settlement_amount_brl} ao persistir a transação. Ver
     * docs/transfers-persist-transaction.md seção 5.
     */
    @Transactional
    public CreateTransferResponse create(CreateTransferRequest request, UUID currentUserId) {
        BeneficiaryEntity beneficiary = beneficiaryRepository.findById(request.getBeneficiaryId())
                .orElseThrow(() -> new NotFoundException("Beneficiário não encontrado"));

        String sourceCurrency = resolveSourceCurrency(request.getSourceCurrency());
        if (!DEFAULT_SOURCE_CURRENCY.equals(sourceCurrency)) {
            throw new UnprocessableEntityException(
                    "sourceCurrency deve ser BRL: o saldo da empresa só é mantido nessa moeda");
        }
        String destinationCurrency = resolveDestinationCurrency(
                request.getDestinationCurrency(), beneficiary);
        if (beneficiary.getReceivingMethod() == ReceivingMethod.CRYPTO_WALLET
                && !STABLECOINS.contains(destinationCurrency)) {
            throw new UnprocessableEntityException(
                    "Carteira cripto só recebe stablecoin (USDC, USDT, BRLA ou EURC); moeda informada: "
                            + destinationCurrency);
        }
        String subAccountId = resolveSubAccountId(currentUserId);

        AveniaQuoteRequest quoteRequest = buildAveniaQuoteRequest(
                request.getAmount(), request.getAmountType(), sourceCurrency, destinationCurrency,
                outputPaymentMethodFor(destinationCurrency, beneficiary), subAccountId);
        AveniaTicketRequest ticketRequest = buildAveniaTicketRequest(beneficiary, request.getDescription());

        AveniaTransferResult result = aveniaTransferService.createTransfer(quoteRequest, ticketRequest);
        TransactionStatus status = mapAveniaTicketStatus(result.ticket().status());

        persistTransaction(beneficiary, request, result, status, currentUserId);

        return new CreateTransferResponse(status);
    }

    /**
     * Grava o snapshot da transferência recém-criada na Avenia. Não acompanha mudanças de
     * status posteriores ao ticket (ex.: UNPAID -> PAID) nem o hash da liquidação on-chain —
     * nenhum dos dois tem mecanismo de atualização implementado ainda (ver
     * docs/transfers-persist-transaction.md seção 6).
     */
    private void persistTransaction(
            BeneficiaryEntity beneficiary, CreateTransferRequest request, AveniaTransferResult result,
            TransactionStatus status, UUID currentUserId) {
        AveniaQuoteResponse quote = result.quote();
        BigDecimal feeAmount = totalFeeAmount(quote);
        BigDecimal feePercentage = feePercentage(quote.inputAmount(), feeAmount);
        OffsetDateTime now = OffsetDateTime.now();

        BaseTransactionEntity baseTransaction = BaseTransactionEntity.builder()
                .company(beneficiary.getCompany())
                .creatorUserId(currentUserId)
                .status(status)
                .foreignCurrency(quote.outputCurrency())
                .foreignAmount(quote.outputAmount())
                .settlementAmountBrl(quote.inputAmount().add(feeAmount))
                .serviceFeeBrl(feeAmount)
                .effectiveSpreadPercentage(feePercentage.setScale(4, RoundingMode.HALF_UP))
                .exchangeRate(quote.basePrice())
                .aveniaTicketId(result.ticket().id())
                .createdAt(now)
                .updatedAt(now)
                .build();
        baseTransaction = baseTransactionRepository.save(baseTransaction);

        ImportTransactionEntity importTransaction = new ImportTransactionEntity();
        importTransaction.setTransactionId(baseTransaction.getId());
        importTransaction.setBeneficiary(beneficiary);
        importTransaction.setTransferMethod(request.getPaymentMethod());
        importTransactionRepository.save(importTransaction);
    }

    /**
     * Toda quote/ticket na Avenia precisa rodar na subconta INDIVIDUAL do usuário —
     * a mesma usada pelo KYC ({@link AveniaKycVerificationEntity#getAveniaSubAccountId()}).
     * Sem isso, a chamada cairia na conta principal da API key, misturando saldo/operações
     * de usuários diferentes. Reaproveita {@link AveniaSubAccountProvisioningService} para
     * criar a subconta sob demanda caso o KYC exista mas ainda não tenha uma provisionada.
     */
    private String resolveSubAccountId(UUID currentUserId) {
        AveniaKycVerificationEntity kyc = aveniaKycVerificationRepository.findByUserId(currentUserId)
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Usuário não possui verificação KYC associada; conclua o KYC antes de realizar transferências"));
        return subAccountProvisioningService.ensureSubAccountId(kyc);
    }

    /**
     * Stablecoin sai pela rede da carteira do beneficiário (Polygon quando não há
     * beneficiário ou rede cadastrada); as demais moedas mantêm a saída INTERNAL.
     */
    private String outputPaymentMethodFor(String destinationCurrency, BeneficiaryEntity beneficiary) {
        if (!STABLECOINS.contains(destinationCurrency)) {
            return DEFAULT_PAYMENT_METHOD;
        }
        return beneficiary != null && beneficiary.getBlockchainNetwork() != null
                ? beneficiary.getBlockchainNetwork().name().toUpperCase(Locale.ROOT)
                : DEFAULT_BLOCKCHAIN_OUTPUT;
    }

    private AveniaQuoteRequest buildAveniaQuoteRequest(
            BigDecimal amount, TransferAmountType amountType,
            String sourceCurrency, String destinationCurrency, String outputPaymentMethod,
            String subAccountId) {
        boolean isSource = amountType == TransferAmountType.SOURCE;
        return new AveniaQuoteRequest(
                sourceCurrency,
                DEFAULT_PAYMENT_METHOD,
                destinationCurrency,
                outputPaymentMethod,
                isSource ? amount : null,
                isSource ? null : amount,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                subAccountId);
    }

    /**
     * Monta a variante de destino do ticket a partir do beneficiário local. A Avenia separa
     * o destino por trilho (PIX, conta bancária americana/europeia, SWIFT, blockchain) — hoje
     * só distinguimos por {@link ReceivingMethod}; dentro de BANK_ACCOUNT sempre usamos SWIFT
     * como trilho internacional genérico. NÃO confirmado contra o sandbox real: falta decidir
     * com o time como diferenciar USD ACH / EUR SEPA / SWIFT dentro de BANK_ACCOUNT.
     */
    private AveniaTicketRequest buildAveniaTicketRequest(BeneficiaryEntity beneficiary, String description) {
        AveniaTicketRequest.AveniaTicketRequestBuilder builder = AveniaTicketRequest.builder()
                .externalId(description);

        return switch (beneficiary.getReceivingMethod()) {
            case PIX_KEY -> builder.ticketBrlPixOutput(new AveniaTicketRequest.BrlPixOutput(
                    beneficiary.getAveniaId(),
                    beneficiary.getPixKey(),
                    null,
                    beneficiary.getAccountHolderName(),
                    beneficiary.getBankCode(),
                    beneficiary.getBranchNumber(),
                    beneficiary.getAccountNumber(),
                    beneficiary.getAccountType() != null ? beneficiary.getAccountType().name() : null,
                    beneficiary.getIdentificationDocument(),
                    null)).build();
            case CRYPTO_WALLET -> builder.ticketBlockchainOutput(new AveniaTicketRequest.BlockchainOutput(
                    beneficiary.getAveniaWalletId(),
                    beneficiary.getBlockchainNetwork() != null
                            ? beneficiary.getBlockchainNetwork().name().toUpperCase(Locale.ROOT)
                            : DEFAULT_BLOCKCHAIN_OUTPUT,
                    beneficiary.getWalletAddress(),
                    beneficiary.getWalletMemo())).build();
            case BANK_ACCOUNT -> builder.ticketSwiftOutput(new AveniaTicketRequest.SwiftOutput(
                    beneficiary.getAveniaId(), null)).build();
        };
    }

    /**
     * Avenia (UNPAID/PROCESSING/ON_HOLD/PAID/FAILED/PARTIAL_FAILED/CANCELED) usa nomes
     * diferentes do nosso transaction_status_enum — sem mapeamento, status desconhecidos
     * viram FAILED em vez de propagar um valor inválido para o frontend.
     */
    private TransactionStatus mapAveniaTicketStatus(String aveniaStatus) {
        if (aveniaStatus == null) {
            return TransactionStatus.PROCESSING;
        }
        return switch (aveniaStatus) {
            case "UNPAID" -> TransactionStatus.AWAITING_PAYMENT;
            case "PROCESSING" -> TransactionStatus.PROCESSING;
            case "ON_HOLD" -> TransactionStatus.HELD;
            case "PAID" -> TransactionStatus.SETTLED;
            case "PARTIAL_FAILED" -> TransactionStatus.PARTIAL_FAILURE;
            case "CANCELED" -> TransactionStatus.CANCELED;
            default -> TransactionStatus.FAILED;
        };
    }

    private String resolveSourceCurrency(String sourceCurrency) {
        return isBlank(sourceCurrency) ? DEFAULT_SOURCE_CURRENCY : sourceCurrency.trim().toUpperCase();
    }

    private String resolveDestinationCurrency(String destinationCurrency) {
        if (isBlank(destinationCurrency)) {
            throw new UnprocessableEntityException(
                    "Não foi possível determinar a moeda de destino da transferência; informe destinationCurrency");
        }
        return destinationCurrency.trim().toUpperCase();
    }

    /**
     * No fluxo de criação já existe um beneficiário cadastrado, que carrega sua própria
     * moeda de recebimento ({@code beneficiaries.currency}) — essa é a "informação
     * disponível para a operação" que a card pede para usar antes de exigir do frontend.
     * PIX é sempre BRL mesmo quando o beneficiário não tiver moeda cadastrada.
     */
    private String resolveDestinationCurrency(String destinationCurrency, BeneficiaryEntity beneficiary) {
        if (!isBlank(destinationCurrency)) {
            return resolveDestinationCurrency(destinationCurrency);
        }
        if (!isBlank(beneficiary.getCurrency())) {
            return beneficiary.getCurrency().trim().toUpperCase();
        }
        if (beneficiary.getReceivingMethod() == ReceivingMethod.PIX_KEY) {
            return DEFAULT_SOURCE_CURRENCY;
        }
        if (beneficiary.getReceivingMethod() == ReceivingMethod.CRYPTO_WALLET) {
            return DEFAULT_STABLECOIN;
        }
        return resolveDestinationCurrency(destinationCurrency);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Mapeamento do shape da Avenia (inputAmount/outputAmount/markupAmount/appliedFees/
     * basePrice) para os campos separados que o frontend consome, sem devolver quoteToken
     * nem qualquer outro identificador interno. Shape NÃO confirmado contra o sandbox real.
     */
    private TransferQuoteResponse toTransferQuoteResponse(AveniaQuoteResponse response) {
        MoneyAmount source = new MoneyAmount(response.inputAmount(), response.inputCurrency());
        MoneyAmount destination = new MoneyAmount(response.outputAmount(), response.outputCurrency());

        BigDecimal feeAmount = totalFeeAmount(response);
        String feeCurrency = response.markupCurrency() != null ? response.markupCurrency() : source.currency();
        BigDecimal feePercentage = feePercentage(source.amount(), feeAmount);

        MoneyAmount total = new MoneyAmount(source.amount().add(feeAmount), source.currency());

        ExchangeRateInfo exchangeRate = new ExchangeRateInfo(
                destination.currency(), source.currency(), response.basePrice());

        return new TransferQuoteResponse(
                source,
                destination,
                exchangeRate,
                new FeeInfo(feePercentage, feeAmount, feeCurrency),
                total);
    }

    private BigDecimal feePercentage(BigDecimal sourceAmount, BigDecimal feeAmount) {
        return feeAmount
                .divide(sourceAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal totalFeeAmount(AveniaQuoteResponse response) {
        if (response.markupAmount() != null) {
            return response.markupAmount();
        }
        List<AveniaAppliedFee> appliedFees = response.appliedFees();
        if (appliedFees == null || appliedFees.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return appliedFees.stream()
                .map(AveniaAppliedFee::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
