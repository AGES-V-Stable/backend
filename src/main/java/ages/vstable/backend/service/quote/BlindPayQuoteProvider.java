package ages.vstable.backend.service.quote;

import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.external.blindpay.BlindPayApi;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class BlindPayQuoteProvider implements QuoteProvider {

    private static final BigDecimal CENTS = BigDecimal.valueOf(100);
    private static final String BRL = "BRL";

    private final BlindPayGateway blindPayGateway;
    private final BlindPayProvisioningService provisioningService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.BLINDPAY;
    }

    @Override
    public boolean supports(QuoteContext context) {
        if (context.request().direction() != QuoteDirection.PAYOUT
                || context.beneficiary().getReceivingMethod() != ReceivingMethod.BANK_ACCOUNT
                || !hasText(context.request().token())
                || !hasText(context.request().blockchainNetwork())) {
            return false;
        }
        boolean fundedWithToken = context.request().sourceCurrency()
                .equalsIgnoreCase(context.request().token());
        boolean fundedWithBrl = BRL.equalsIgnoreCase(context.request().sourceCurrency());
        if (!fundedWithToken && !fundedWithBrl) {
            return false;
        }
        if (!hasText(context.beneficiary().getCurrency())
                || !context.beneficiary().getCurrency().equalsIgnoreCase(context.request().targetCurrency())) {
            return false;
        }
        try {
            BlindPayApi.VirtualAccount.Token.valueOf(context.request().token().toUpperCase());
            BlindPayApi.BlockchainWallet.Network.valueOf(context.request().blockchainNetwork().toLowerCase());
            if (fundedWithBrl) {
                BlindPayApi.Payin.PaymentMethod paymentMethod = BlindPayApi.Payin.PaymentMethod.valueOf(
                        context.request().sourcePaymentMethod().toLowerCase());
                if (paymentMethod != BlindPayApi.Payin.PaymentMethod.pix
                        && paymentMethod != BlindPayApi.Payin.PaymentMethod.ted) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public ProviderQuoteResult quote(QuoteContext context, String idempotencyKey) {
        String bankAccountId = provisioningService.ensureBankAccount(context.company(), context.beneficiary());
        if (BRL.equalsIgnoreCase(context.request().sourceCurrency())) {
            return quoteFundedWithBrl(context, bankAccountId, idempotencyKey);
        }
        return quoteFundedWithToken(context, bankAccountId, idempotencyKey);
    }

    private ProviderQuoteResult quoteFundedWithToken(
            QuoteContext context, String bankAccountId, String idempotencyKey) {
        BlindPayQuoteResponse response = createPayoutQuote(
                context, bankAccountId, context.request().amount(), context.request().amountSide(), idempotencyKey);
        BigDecimal fees = payoutFees(response);
        return new ProviderQuoteResult(
                provider(),
                response.id(),
                fromCents(response.senderAmount()),
                fromCents(response.receiverAmount()),
                response.blindpayQuotation(),
                fromCents(fees),
                fromCents(fees),
                expiresAt(response),
                "{}");
    }

    private ProviderQuoteResult quoteFundedWithBrl(
            QuoteContext context, String bankAccountId, String idempotencyKey) {
        String walletId = provisioningService.ensureWallet(
                context.company(), context.request().blockchainNetwork());
        BlindPayPayinQuoteResponse fundingQuote;
        BlindPayQuoteResponse payoutQuote;

        if (context.request().amountSide() == QuoteAmountSide.SOURCE) {
            fundingQuote = createPayinQuote(
                    context,
                    walletId,
                    context.request().amount(),
                    QuoteAmountSide.SOURCE,
                    idempotencyKey + ":payin");
            payoutQuote = createPayoutQuote(
                    context,
                    bankAccountId,
                    fromCents(fundingQuote.receiverAmount()),
                    QuoteAmountSide.SOURCE,
                    idempotencyKey + ":payout");
        } else {
            payoutQuote = createPayoutQuote(
                    context,
                    bankAccountId,
                    context.request().amount(),
                    QuoteAmountSide.TARGET,
                    idempotencyKey + ":payout");
            fundingQuote = createPayinQuote(
                    context,
                    walletId,
                    fromCents(payoutQuote.senderAmount()),
                    QuoteAmountSide.TARGET,
                    idempotencyKey + ":payin");
        }

        BigDecimal sourceAmount = requirePositive(fromCents(fundingQuote.senderAmount()), "BlindPay sender amount");
        BigDecimal targetAmount = requirePositive(fromCents(payoutQuote.receiverAmount()), "BlindPay receiver amount");
        BigDecimal exchangeRate = targetAmount.divide(sourceAmount, 10, RoundingMode.HALF_UP);
        BigDecimal blindPayFees = fromCents(sum(payinFees(fundingQuote), payoutFees(payoutQuote)));

        return new ProviderQuoteResult(
                provider(),
                payoutQuote.id(),
                sourceAmount,
                targetAmount,
                exchangeRate,
                blindPayFees,
                blindPayFees,
                earliestExpiration(expiresAt(fundingQuote), expiresAt(payoutQuote)),
                compositeMetadata(fundingQuote.id(), payoutQuote.id(), walletId));
    }

    private BlindPayPayinQuoteResponse createPayinQuote(
            QuoteContext context,
            String walletId,
            BigDecimal amount,
            QuoteAmountSide amountSide,
            String idempotencyKey) {
        BlindPayApi.Payin.PaymentMethod paymentMethod;
        try {
            paymentMethod = BlindPayApi.Payin.PaymentMethod.valueOf(
                    context.request().sourcePaymentMethod().toLowerCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported BlindPay payin method: " + context.request().sourcePaymentMethod());
        }
        return blindPayGateway.createPayinQuote(
                BlindPayPayinQuoteRequest.builder()
                        .walletId(walletId)
                        .paymentMethod(paymentMethod)
                        .token(BlindPayApi.VirtualAccount.Token.valueOf(
                                context.request().token().toUpperCase()))
                        .currencyType(amountSide == QuoteAmountSide.SOURCE
                                ? BlindPayApi.Quote.CurrencyType.sender
                                : BlindPayApi.Quote.CurrencyType.receiver)
                        .requestAmount(toCents(amount))
                        .coverFees(context.request().coverFees())
                        .build(),
                idempotencyKey);
    }

    private BlindPayQuoteResponse createPayoutQuote(
            QuoteContext context,
            String bankAccountId,
            BigDecimal amount,
            QuoteAmountSide amountSide,
            String idempotencyKey) {
        return blindPayGateway.createQuote(
                BlindPayQuoteRequest.builder()
                        .bankAccountId(bankAccountId)
                        .network(BlindPayApi.BlockchainWallet.Network.valueOf(
                                context.request().blockchainNetwork().toLowerCase()))
                        .token(BlindPayApi.VirtualAccount.Token.valueOf(context.request().token().toUpperCase()))
                        .currencyType(amountSide == QuoteAmountSide.SOURCE
                                ? BlindPayApi.Quote.CurrencyType.sender
                                : BlindPayApi.Quote.CurrencyType.receiver)
                        .requestAmount(toCents(amount))
                        .coverFees(context.request().coverFees())
                        .description(context.request().description())
                        .build(),
                idempotencyKey);
    }

    private OffsetDateTime earliestExpiration(OffsetDateTime first, OffsetDateTime second) {
        if (first == null) {
            return second;
        }
        return second == null || first.isBefore(second) ? first : second;
    }

    private OffsetDateTime expiresAt(BlindPayQuoteResponse response) {
        return response.expiresAt() == null ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(response.expiresAt()), java.time.ZoneOffset.UTC);
    }

    private OffsetDateTime expiresAt(BlindPayPayinQuoteResponse response) {
        return response.expiresAt() == null ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(response.expiresAt()), java.time.ZoneOffset.UTC);
    }

    private BigDecimal payoutFees(BlindPayQuoteResponse response) {
        return sum(response.flatFee(), response.billingFeeAmount(), response.partnerFeeAmount());
    }

    private BigDecimal payinFees(BlindPayPayinQuoteResponse response) {
        return sum(response.flatFee(), response.billingFeeAmount(), response.partnerFeeAmount());
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return value;
    }

    private String compositeMetadata(String fundingQuoteId, String payoutQuoteId, String walletId) {
        return "{\"fundingProvider\":\"BLINDPAY\",\"fundingQuoteId\":\""
                + fundingQuoteId
                + "\",\"payoutProvider\":\"BLINDPAY\",\"payoutQuoteId\":\""
                + payoutQuoteId
                + "\",\"walletId\":\""
                + walletId
                + "\"}";
    }

    private long toCents(BigDecimal value) {
        return value.multiply(CENTS).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private BigDecimal fromCents(BigDecimal value) {
        return value == null ? null : value.divide(CENTS, 2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total.add(value);
            }
        }
        return total;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
