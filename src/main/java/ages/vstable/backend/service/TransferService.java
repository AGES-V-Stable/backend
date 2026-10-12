package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.ExchangeRateInfo;
import ages.vstable.backend.dto.transfer.FeeInfo;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.dto.transfer.TransferResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
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
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ExportTransactionRepository;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.specification.TransferSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fronteira entre o contrato público de transferências (dto/transfer, sem nenhum
 * identificador da Avenia) e a integração de baixo nível ({@link AveniaTransferService},
 * dto/external/avenia). Nenhum tipo de external/avenia deve escapar desta classe.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    // A cotação de exibição ainda não tem um beneficiário associado (esse dado só chega
    // em POST /transfers), então não há como saber o método de recebimento real. INTERNAL
    // representa a rota "saldo em conta" (ACCOUNT_BALANCE) só para fins de exibição do
    // valor — não confirmado contra o sandbox real.
    private static final String DEFAULT_PAYMENT_METHOD = "INTERNAL";

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
    private final ExportTransactionRepository exportTransactionRepository;
    private final CompanyRepository companyRepository;

    public TransferQuoteResponse quote(TransferQuoteRequest request, UUID currentUserId) {
        String sourceCurrency = resolveSourceCurrency(request.getSourceCurrency());
        String destinationCurrency = resolveDestinationCurrency(request.getDestinationCurrency());
        String subAccountId = resolveSubAccountId(currentUserId);

        AveniaQuoteRequest aveniaRequest = buildAveniaQuoteRequest(
                request.getAmount(), request.getAmountType(), sourceCurrency, DEFAULT_PAYMENT_METHOD,
                destinationCurrency, DEFAULT_PAYMENT_METHOD, subAccountId);
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
    public CreateTransferResponse create(
            CreateTransferRequest request, UUID currentUserId, UUID currentCompanyId) {
        if (currentCompanyId == null) {
            throw new NotFoundException("Beneficiário não encontrado");
        }
        BeneficiaryEntity beneficiary = beneficiaryRepository
                .findByIdAndCompanyId(request.getBeneficiaryId(), currentCompanyId)
                .orElseThrow(() -> new NotFoundException("Beneficiário não encontrado"));

        String sourceCurrency = resolveSourceCurrency(request.getSourceCurrency());
        if (!DEFAULT_SOURCE_CURRENCY.equals(sourceCurrency)) {
            throw new UnprocessableEntityException(
                    "sourceCurrency deve ser BRL: o saldo da empresa só é mantido nessa moeda");
        }
        String destinationCurrency = resolveDestinationCurrency(
                request.getDestinationCurrency(), beneficiary);
        validateRegisteredBeneficiary(beneficiary);
        String subAccountId = resolveSubAccountId(currentUserId);

        AveniaQuoteRequest quoteRequest = buildAveniaQuoteRequest(
                request.getAmount(), request.getAmountType(), sourceCurrency,
                inputPaymentMethod(request.getPaymentMethod()), destinationCurrency,
                outputPaymentMethod(beneficiary), subAccountId);
        AveniaTicketRequest ticketRequest = buildAveniaTicketRequest(
                beneficiary, request.getPaymentMethod(), request.getDescription());

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

        ImportTransactionEntity transaction = ImportTransactionEntity.builder()
                .companyId(beneficiary.getCompany().getId())
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
                .beneficiaryId(beneficiary.getId())
                .beneficiary(beneficiary)
                .transferMethod(request.getPaymentMethod())
                .build();
        // JOINED inheritance persists the base snapshot and payment detail with one shared ID.
        importTransactionRepository.save(transaction);
    }

    /**
     * Toda quote/ticket na Avenia precisa rodar na subconta INDIVIDUAL do usuário —
     * a mesma usada pelo KYC ({@link AveniaKycVerificationEntity#getAveniaSubAccountId()}).
     * Sem isso, a chamada cairia na conta principal da API key, misturando saldo/operações
     * de usuários diferentes. Reaproveita {@link AveniaSubAccountProvisioningService} para
     * criar a subconta sob demanda caso o KYC exista mas ainda não tenha uma provisionada.
     */
    private String resolveSubAccountId(UUID currentUserId) {
        AveniaKycVerificationEntity kyc = aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(currentUserId)
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Usuário não possui verificação KYC associada; conclua o KYC antes de realizar transferências"));
        return subAccountProvisioningService.ensureSubAccountId(kyc);
    }

    private AveniaQuoteRequest buildAveniaQuoteRequest(
            BigDecimal amount, TransferAmountType amountType,
            String sourceCurrency, String inputPaymentMethod,
            String destinationCurrency, String outputPaymentMethod, String subAccountId) {
        boolean isSource = amountType == TransferAmountType.SOURCE;
        return new AveniaQuoteRequest(
                sourceCurrency,
                inputPaymentMethod,
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
     * Monta somente a variante de destino compatível com o cadastro feito na Avenia.
     * Beneficiários registrados são referenciados apenas pelo ID: misturar o ID com dados
     * inline no mesmo bloco faz a Avenia rejeitar o ticket.
     */
    private AveniaTicketRequest buildAveniaTicketRequest(
            BeneficiaryEntity beneficiary, TransferMethod paymentMethod, String description) {
        AveniaTicketRequest.AveniaTicketRequestBuilder builder = AveniaTicketRequest.builder()
                .externalId(description);

        if (paymentMethod == TransferMethod.PIX) {
            builder.ticketBrlPixInput(new AveniaTicketRequest.BrlPixInput(null));
        }

        return switch (beneficiary.getReceivingMethod()) {
            case PIX_KEY -> builder.ticketBrlPixOutput(new AveniaTicketRequest.BrlPixOutput(
                    beneficiary.getAveniaId(),
                    null, description, null, null, null, null, null, null, null)).build();
            case CRYPTO_WALLET -> builder.ticketBlockchainOutput(new AveniaTicketRequest.BlockchainOutput(
                    beneficiary.getAveniaWalletId(),
                    null, null, null)).build();
            case BANK_ACCOUNT -> buildBankAccountTicket(builder, beneficiary, description);
        };
    }

    private AveniaTicketRequest buildBankAccountTicket(
            AveniaTicketRequest.AveniaTicketRequestBuilder builder,
            BeneficiaryEntity beneficiary,
            String description) {
        return switch (paymentRail(beneficiary)) {
            case "pix" -> builder.ticketBrlPixOutput(new AveniaTicketRequest.BrlPixOutput(
                    beneficiary.getAveniaId(), null, description, null, null, null, null, null, null, null)).build();
            case "ach" -> builder.ticketUsdOutput(new AveniaTicketRequest.UsdOutput(
                    beneficiary.getAveniaId(), description, null)).build();
            case "wire" -> builder.ticketUsdWireOutput(new AveniaTicketRequest.UsdOutput(
                    beneficiary.getAveniaId(), null, description)).build();
            case "sepa" -> builder.ticketEurSepaOutput(new AveniaTicketRequest.EurSepaOutput(
                    beneficiary.getAveniaId(), description)).build();
            case "international_swift", "swift" -> throw new UnprocessableEntityException(
                    "Transferências SWIFT exigem uma invoice processada pela Avenia; esse documento ainda não faz parte do contrato de transferência");
            default -> throw new UnprocessableEntityException(
                    "Método de recebimento bancário não suportado pela Avenia: " + beneficiary.getPaymentRail());
        };
    }

    private String inputPaymentMethod(TransferMethod paymentMethod) {
        return switch (paymentMethod) {
            case ACCOUNT_BALANCE -> "INTERNAL";
            case PIX -> "PIX";
            case TED -> throw new UnprocessableEntityException(
                    "TED não é um método de entrada suportado pela Avenia neste fluxo");
            case BLOCKCHAIN -> throw new UnprocessableEntityException(
                    "Pagamento por blockchain exige dados de assinatura que não fazem parte deste contrato");
        };
    }

    private String outputPaymentMethod(BeneficiaryEntity beneficiary) {
        return switch (beneficiary.getReceivingMethod()) {
            case PIX_KEY -> "PIX";
            case CRYPTO_WALLET -> beneficiary.getBlockchainNetwork().name().toUpperCase(Locale.ROOT);
            case BANK_ACCOUNT -> switch (paymentRail(beneficiary)) {
                case "pix" -> "PIX";
                case "ach" -> "ACH";
                case "wire" -> "WIRE";
                case "sepa" -> "SEPA";
                case "international_swift", "swift" -> "SWIFT";
                default -> throw new UnprocessableEntityException(
                        "Método de recebimento bancário não suportado pela Avenia: " + beneficiary.getPaymentRail());
            };
        };
    }

    private void validateRegisteredBeneficiary(BeneficiaryEntity beneficiary) {
        if (beneficiary.getReceivingMethod() == ReceivingMethod.CRYPTO_WALLET) {
            if (beneficiary.getAveniaWalletId() == null) {
                throw new UnprocessableEntityException(
                        "Beneficiário não possui carteira cadastrada na Avenia; cadastre-o novamente");
            }
            if (beneficiary.getBlockchainNetwork() == null) {
                throw new UnprocessableEntityException("Beneficiário não possui blockchainNetwork");
            }
        } else if (beneficiary.getAveniaId() == null) {
            throw new UnprocessableEntityException(
                    "Beneficiário não possui conta cadastrada na Avenia; cadastre-o novamente");
        }
    }

    private String paymentRail(BeneficiaryEntity beneficiary) {
        return isBlank(beneficiary.getPaymentRail())
                ? "international_swift"
                : beneficiary.getPaymentRail().trim().toLowerCase(Locale.ROOT);
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
            String resolved = resolveDestinationCurrency(destinationCurrency);
            if (!isBlank(beneficiary.getCurrency())
                    && !resolved.equalsIgnoreCase(beneficiary.getCurrency().trim())) {
                throw new UnprocessableEntityException(
                        "destinationCurrency não corresponde à moeda do beneficiário");
            }
            if (beneficiary.getReceivingMethod() == ReceivingMethod.PIX_KEY
                    && !DEFAULT_SOURCE_CURRENCY.equals(resolved)) {
                throw new UnprocessableEntityException("destinationCurrency deve ser BRL para beneficiário PIX");
            }
            return resolved;
        }
        if (!isBlank(beneficiary.getCurrency())) {
            return beneficiary.getCurrency().trim().toUpperCase();
        }
        if (beneficiary.getReceivingMethod() == ReceivingMethod.PIX_KEY) {
            return DEFAULT_SOURCE_CURRENCY;
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
        String feeCurrency = feeCurrency(response, source.currency());
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
        List<AveniaAppliedFee> appliedFees = response.appliedFees();
        if (appliedFees != null && !appliedFees.isEmpty()) {
            return appliedFees.stream()
                    .map(AveniaAppliedFee::amount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return response.markupAmount() == null ? BigDecimal.ZERO : response.markupAmount();
    }

    private String feeCurrency(AveniaQuoteResponse response, String sourceCurrency) {
        if (response.appliedFees() == null || response.appliedFees().isEmpty()) {
            return isBlank(response.markupCurrency()) ? sourceCurrency : response.markupCurrency();
        }
        String currency = response.appliedFees().stream()
                .map(AveniaAppliedFee::currency)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(sourceCurrency);
        boolean hasDifferentCurrency = response.appliedFees().stream()
                .map(AveniaAppliedFee::currency)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> !value.equalsIgnoreCase(currency));
        if (hasDifferentCurrency || !currency.equalsIgnoreCase(sourceCurrency)) {
            throw new AveniaIntegrationException(
                    "Avenia returned fees in a currency different from the source currency", 502, false, null);
        }
        return currency;
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> findTransfers(TransferFilter filter, Pageable pageable) {
        validate(filter);

        Page<BaseTransactionEntity> page =
                baseTransactionRepository.findAll(TransferSpecification.filterBy(filter), pageable);

        return new PageImpl<>(toResponses(page.getContent()), pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TransferResponse findById(UUID id) {
        BaseTransactionEntity transaction = baseTransactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transferência não encontrada"));
        return toResponses(List.of(transaction)).getFirst();
    }

    private void validate(TransferFilter filter) {
        if (filter.startDate() != null && filter.endDate() != null && filter.startDate().isAfter(filter.endDate())) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        if (filter.minAmount() != null && filter.minAmount().signum() < 0
                || filter.maxAmount() != null && filter.maxAmount().signum() < 0) {
            throw new IllegalArgumentException("Amount filters must not be negative");
        }
        if (filter.minAmount() != null && filter.maxAmount() != null
                && filter.minAmount().compareTo(filter.maxAmount()) > 0) {
            throw new IllegalArgumentException("minAmount must be less than or equal to maxAmount");
        }
    }

    /** Loads the related rows for a whole page at once (no per-row queries). */
    private List<TransferResponse> toResponses(List<BaseTransactionEntity> transactions) {
        if (transactions.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = transactions.stream().map(BaseTransactionEntity::getId).toList();
        Map<UUID, ImportTransactionEntity> imports = byId(importTransactionRepository.findAllById(ids),
                ImportTransactionEntity::getId);
        Map<UUID, ExportTransactionEntity> exports = byId(exportTransactionRepository.findAllById(ids),
                ExportTransactionEntity::getId);
        Map<UUID, BeneficiaryEntity> beneficiaries = byId(beneficiaryRepository.findAllById(
                imports.values().stream().map(ImportTransactionEntity::getBeneficiaryId).collect(Collectors.toSet())),
                BeneficiaryEntity::getId);
        Map<UUID, CompanyEntity> companies = byId(companyRepository.findAllById(
                transactions.stream().map(BaseTransactionEntity::getCompanyId).collect(Collectors.toSet())),
                CompanyEntity::getId);

        return transactions.stream()
                .map(transaction -> toResponse(transaction,
                        imports.get(transaction.getId()),
                        exports.get(transaction.getId()),
                        beneficiaries,
                        companies.get(transaction.getCompanyId())))
                .toList();
    }

    private TransferResponse toResponse(BaseTransactionEntity transaction,
                                        ImportTransactionEntity importDetail,
                                        ExportTransactionEntity exportDetail,
                                        Map<UUID, BeneficiaryEntity> beneficiaries,
                                        CompanyEntity company) {
        TransferResponse response = new TransferResponse();
        response.setId(transaction.getId());
        response.setCompanyId(transaction.getCompanyId());
        if (company != null) {
            response.setCompanyName(company.getTradeName() != null ? company.getTradeName() : company.getLegalName());
        }

        if (importDetail != null) {
            response.setDirection(TransferDirection.PAYMENT);
            response.setBeneficiaryId(importDetail.getBeneficiaryId());
            response.setTransferMethod(importDetail.getTransferMethod());
            BeneficiaryEntity beneficiary = beneficiaries.get(importDetail.getBeneficiaryId());
            if (beneficiary != null) {
                response.setCounterpartyName(beneficiary.getAccountHolderName() != null
                        ? beneficiary.getAccountHolderName()
                        : beneficiary.getNickname());
            }
        } else if (exportDetail != null) {
            response.setDirection(TransferDirection.RECEIPT);
            response.setCounterpartyName(exportDetail.getExternalPayerName());
        }

        response.setStatus(transaction.getStatus());
        response.setForeignCurrency(transaction.getForeignCurrency());
        response.setForeignAmount(transaction.getForeignAmount());
        response.setSettlementAmountBrl(transaction.getSettlementAmountBrl());
        response.setServiceFeeBrl(transaction.getServiceFeeBrl());
        response.setExchangeRate(transaction.getExchangeRate());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setSettledAt(transaction.getSettledAt());
        return response;
    }

    private static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> idOf) {
        return items.stream().collect(Collectors.toMap(idOf, Function.identity(), (first, second) -> first));
    }
}
