package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Cost;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Costs;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.ExchangeRate;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Money;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.exception.TransferDetailsException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Detalhes e comprovante de uma transferência da empresa do usuário. Tudo vem dos dados
 * registrados na operação; nada é recalculado com cotação ou mercado atuais.
 *
 * <p>Convenções dos valores registrados:
 * {@code settlement_amount_brl} é o total liquidado em BRL, já com a taxa de serviço; o valor
 * convertido (origem no pagamento, destino no recebimento) é {@code settlement_amount_brl -
 * service_fee_brl}.
 */
@Service
@RequiredArgsConstructor
public class TransferDetailsService {

    static final String BRL = "BRL";
    static final String RECEIPT_FILENAME = "comprovante-transferencia.pdf";

    private final BaseTransactionRepository transactionRepository;
    private final AveniaKycVerificationRepository kycRepository;
    private final TransferReceiptProvider receiptProvider;

    /** PDF pronto para download. */
    public record Receipt(String filename, byte[] content) {
    }

    @Transactional(readOnly = true)
    public TransferDetailsResponse getDetails(UUID transferId, UserEntity user) {
        return toResponse(findOwned(transferId, user));
    }

    @Transactional(readOnly = true)
    public Receipt getReceipt(UUID transferId, UserEntity user) {
        BaseTransactionEntity transaction = findOwned(transferId, user);
        if (!isReceiptAvailable(transaction)) {
            throw TransferDetailsException.receiptUnavailable();
        }
        return new Receipt(RECEIPT_FILENAME, receiptProvider.generate(toResponse(transaction)));
    }

    /**
     * Só uma transferência da própria empresa do usuário verificado é devolvida. Usuário sem
     * empresa nunca cai em consulta global; transferência de outra empresa é indistinguível de
     * uma inexistente.
     */
    private BaseTransactionEntity findOwned(UUID transferId, UserEntity user) {
        if (user == null || user.getCompanyId() == null) {
            throw TransferDetailsException.companyAccessRequired();
        }
        if (!isVerified(user)) {
            throw TransferDetailsException.userNotVerified();
        }
        return transactionRepository.findById(transferId)
                .filter(transaction -> user.getCompanyId().equals(transaction.getCompanyId()))
                .orElseThrow(TransferDetailsException::transferNotFound);
    }

    private boolean isVerified(UserEntity user) {
        return kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .map(kyc -> kyc.getStatus() == ComplianceStatus.APPROVED)
                .orElse(false);
    }

    /** O comprovante só existe para operações concluídas, para não indicar conclusão indevida. */
    private boolean isReceiptAvailable(BaseTransactionEntity transaction) {
        return transaction.getStatus() == TransactionStatus.SETTLED;
    }

    private TransferDetailsResponse toResponse(BaseTransactionEntity transaction) {
        Type type = typeOf(transaction);
        Counterparty counterparty = counterpartyOf(transaction);
        BigDecimal convertedBrl = convertedBrl(transaction);
        Money foreign = new Money(money(transaction.getForeignAmount()), transaction.getForeignCurrency());
        Money brl = new Money(money(convertedBrl), BRL);
        boolean incoming = type == Type.RECEBIMENTO;

        return new TransferDetailsResponse(
                transaction.getId(),
                transaction.getCompanyId(),
                transaction.getCreatedAt(),
                type,
                transaction.getStatus(),
                counterparty.name(),
                counterparty.details(),
                incoming ? foreign : brl,
                incoming ? brl : foreign,
                fundingSourceOf(transaction),
                exchangeRate(transaction),
                new Costs(serviceFee(transaction.getServiceFeeBrl(), convertedBrl), null, null),
                null,
                isReceiptAvailable(transaction));
    }

    /** Importação é pagamento; exportação é recebimento; qualquer outra herança fica sem tipo. */
    private static Type typeOf(BaseTransactionEntity transaction) {
        if (transaction instanceof ImportTransactionEntity) {
            return Type.PAGAMENTO;
        }
        if (transaction instanceof ExportTransactionEntity) {
            return Type.RECEBIMENTO;
        }
        return null;
    }

    /** Quem recebe (pagamento) ou quem paga (recebimento); nulo quando não há registro. */
    private static Counterparty counterpartyOf(BaseTransactionEntity transaction) {
        if (transaction instanceof ImportTransactionEntity payment) {
            return beneficiaryCounterparty(payment.getBeneficiary());
        }
        if (transaction instanceof ExportTransactionEntity incoming) {
            return new Counterparty(
                    firstNonBlank(incoming.getExternalPayerName()),
                    firstNonBlank(incoming.getExternalPayerEmail()));
        }
        return Counterparty.NONE;
    }

    /** O apelido só vira detalhe quando difere do nome já exibido. */
    private static Counterparty beneficiaryCounterparty(BeneficiaryEntity beneficiary) {
        if (beneficiary == null) {
            return Counterparty.NONE;
        }
        String name = firstNonBlank(beneficiary.getAccountHolderName(), beneficiary.getNickname());
        String nickname = firstNonBlank(beneficiary.getNickname());
        return new Counterparty(name, nickname != null && !nickname.equals(name) ? nickname : null);
    }

    /** Método de transferência do pagamento; recebimentos não têm. */
    private static String fundingSourceOf(BaseTransactionEntity transaction) {
        if (transaction instanceof ImportTransactionEntity payment && payment.getTransferMethod() != null) {
            return payment.getTransferMethod().name();
        }
        return null;
    }

    private record Counterparty(String name, String details) {
        static final Counterparty NONE = new Counterparty(null, null);
    }

    /** Valor convertido em BRL, sem a taxa de serviço; nulo se o total liquidado não foi registrado. */
    private static BigDecimal convertedBrl(BaseTransactionEntity transaction) {
        if (transaction.getSettlementAmountBrl() == null) {
            return null;
        }
        BigDecimal fee = transaction.getServiceFeeBrl() == null ? BigDecimal.ZERO : transaction.getServiceFeeBrl();
        return transaction.getSettlementAmountBrl().subtract(fee);
    }

    private static ExchangeRate exchangeRate(BaseTransactionEntity transaction) {
        if (transaction.getExchangeRate() == null || transaction.getForeignCurrency() == null) {
            return null;
        }
        return new ExchangeRate(transaction.getForeignCurrency(), BRL, decimal(transaction.getExchangeRate()));
    }

    /** Percentual calculado sobre os valores registrados (taxa / valor convertido), em pontos percentuais. */
    private static Cost serviceFee(BigDecimal fee, BigDecimal convertedBrl) {
        if (fee == null) {
            return null;
        }
        String percentage = null;
        if (convertedBrl != null && convertedBrl.signum() > 0) {
            percentage = fee.multiply(BigDecimal.valueOf(100))
                    .divide(convertedBrl, 2, RoundingMode.HALF_UP)
                    .toPlainString();
        }
        return new Cost(money(fee), BRL, percentage);
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Sem zeros à direita, mas com ao menos duas casas ("5.420000" vira "5.42"). */
    private static String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 2 ? stripped.setScale(2, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
