package ages.vstable.backend.service.dashboard;

import ages.vstable.backend.dto.dashboard.DashboardSeriesResponse;
import ages.vstable.backend.dto.dashboard.DashboardSettlementResponse;
import ages.vstable.backend.dto.dashboard.DashboardSummaryResponse;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.DashboardMonthlyTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Métricas do dashboard de uma empresa ou, sem {@code companyId}, consolidadas. Métricas e séries
 * consideram só transações liquidadas, datadas pela liquidação ou, sem ela, pela criação (o fluxo
 * Avenia ainda não registra a liquidação). As somas são feitas no banco.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    static final String CURRENCY = "BRL";
    static final int MAX_PAGE_SIZE = 50;
    private static final int SCALE = 2;

    private final BaseTransactionRepository transactionRepository;
    private final CompanyRepository companyRepository;
    private final SavingsEstimator savingsEstimator;

    /** Garante que a empresa escolhida pelo administrador existe. */
    @Transactional(readOnly = true)
    public void requireCompany(UUID companyId) {
        if (companyId != null && !companyRepository.existsById(companyId)) {
            throw new NotFoundException("Company not found");
        }
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(UUID companyId, DashboardPeriod period) {
        List<DashboardMonthlyTotals> months = monthlyTotals(companyId, period);
        long incomplete = sumLong(months, DashboardMonthlyTotals::getSavingsMissing);
        BigDecimal spreadSum = sum(months, DashboardMonthlyTotals::getSpreadSum);
        long spreadCount = sumLong(months, DashboardMonthlyTotals::getSpreadCount);
        String averageSpread = spreadCount == 0 ? null
                : spreadSum.divide(BigDecimal.valueOf(spreadCount), SCALE, RoundingMode.HALF_UP).toPlainString();
        return new DashboardSummaryResponse(
                CURRENCY,
                period.startDate(),
                period.endDate(),
                money(sum(months, DashboardMonthlyTotals::getSavingsBrl)),
                savingsEstimator.referenceSpreadPercentage().setScale(SCALE, RoundingMode.HALF_UP).toPlainString(),
                averageSpread,
                incomplete > 0,
                incomplete);
    }

    @Transactional(readOnly = true)
    public DashboardSeriesResponse remittanceVolume(UUID companyId, DashboardPeriod period) {
        return series(period, monthlyTotals(companyId, period),
                DashboardMonthlyTotals::getVolumeBrl, DashboardMonthlyTotals::getVolumeMissing);
    }

    @Transactional(readOnly = true)
    public DashboardSeriesResponse savingsHistory(UUID companyId, DashboardPeriod period) {
        return series(period, monthlyTotals(companyId, period),
                DashboardMonthlyTotals::getSavingsBrl, DashboardMonthlyTotals::getSavingsMissing);
    }

    /** Transferências de qualquer status, da mais recente para a mais antiga. */
    @Transactional(readOnly = true)
    public Page<DashboardSettlementResponse> settlements(UUID companyId, DashboardPeriod period, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Page<BaseTransactionEntity> transactions = transactionRepository.findLatestForDashboard(
                companyId, period.from(), period.toExclusive(), PageRequest.of(page, size));
        Map<UUID, String> companyNames = companyNames(transactions);
        return transactions.map(transaction -> toSettlement(transaction, companyNames.get(transaction.getCompanyId())));
    }

    private List<DashboardMonthlyTotals> monthlyTotals(UUID companyId, DashboardPeriod period) {
        return transactionRepository.findMonthlyDashboardTotals(
                companyId, period.from(), period.toExclusive(), savingsEstimator.referenceSpreadPercentage());
    }

    /**
     * Sem nenhuma operação no período, devolve a série vazia; caso contrário, um ponto por mês do
     * período, com zero nos meses sem operação. Sem datas, o período vai do primeiro ao último mês com dados.
     */
    private DashboardSeriesResponse series(DashboardPeriod period, List<DashboardMonthlyTotals> months,
                                           Function<DashboardMonthlyTotals, BigDecimal> amount,
                                           ToLongFunction<DashboardMonthlyTotals> missing) {
        List<DashboardSeriesResponse.Point> points = new ArrayList<>();
        if (!months.isEmpty()) {
            Map<YearMonth, BigDecimal> amountByMonth = months.stream().collect(Collectors.toMap(
                    month -> YearMonth.parse(month.getMonth()),
                    month -> Objects.requireNonNullElse(amount.apply(month), BigDecimal.ZERO)));
            YearMonth first = period.startDate() != null
                    ? YearMonth.from(period.startDate()) : YearMonth.parse(months.getFirst().getMonth());
            YearMonth last = period.endDate() != null
                    ? YearMonth.from(period.endDate()) : YearMonth.parse(months.getLast().getMonth());
            for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
                points.add(new DashboardSeriesResponse.Point(
                        month.toString(), money(amountByMonth.getOrDefault(month, BigDecimal.ZERO))));
            }
        }
        return new DashboardSeriesResponse(
                CURRENCY,
                "MONTH",
                period.startDate(),
                period.endDate(),
                money(sum(months, amount)),
                points,
                sumLong(months, missing));
    }

    private DashboardSettlementResponse toSettlement(BaseTransactionEntity transaction, String companyName) {
        TransferDirection direction = null;
        String counterparty = null;
        String destination = null;
        if (transaction instanceof ImportTransactionEntity payment) {
            direction = TransferDirection.PAYMENT;
            BeneficiaryEntity beneficiary = payment.getBeneficiary();
            if (beneficiary != null) {
                counterparty = beneficiary.getNickname();
                destination = joinPresent(beneficiary.getCountry(), beneficiary.getBankName());
            }
        } else if (transaction instanceof ExportTransactionEntity receipt) {
            direction = TransferDirection.RECEIPT;
            counterparty = receipt.getExternalPayerName();
        }
        BigDecimal savings = savingsEstimator.estimate(transaction);
        return new DashboardSettlementResponse(
                transaction.getId(),
                transaction.getCompanyId(),
                companyName,
                transaction.getSettledAt(),
                transaction.getCreatedAt(),
                direction,
                counterparty,
                destination,
                plain(transaction.getForeignAmount()),
                transaction.getForeignCurrency(),
                plain(transaction.getSettlementAmountBrl()),
                plain(transaction.getExchangeRate()),
                plain(transaction.getServiceFeeBrl()),
                plain(savings),
                transaction.getStatus());
    }

    private Map<UUID, String> companyNames(Page<BaseTransactionEntity> transactions) {
        List<UUID> ids = transactions.stream().map(BaseTransactionEntity::getCompanyId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyRepository.findAllById(ids).stream().collect(Collectors.toMap(
                CompanyEntity::getId,
                company -> company.getTradeName() != null && !company.getTradeName().isBlank()
                        ? company.getTradeName() : company.getLegalName()));
    }

    private static String joinPresent(String... parts) {
        String joined = Stream.of(parts).filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" • "));
        return joined.isEmpty() ? null : joined;
    }

    private static BigDecimal sum(List<DashboardMonthlyTotals> months, Function<DashboardMonthlyTotals, BigDecimal> value) {
        return months.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long sumLong(List<DashboardMonthlyTotals> months, ToLongFunction<DashboardMonthlyTotals> value) {
        return months.stream().mapToLong(value).sum();
    }

    private static String money(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
