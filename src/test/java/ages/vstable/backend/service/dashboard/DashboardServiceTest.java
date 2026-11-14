package ages.vstable.backend.service.dashboard;

import ages.vstable.backend.dto.dashboard.DashboardSeriesResponse;
import ages.vstable.backend.dto.dashboard.DashboardSettlementResponse;
import ages.vstable.backend.dto.dashboard.DashboardSummaryResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.DashboardMonthlyTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private BaseTransactionRepository transactionRepository;

    @Mock
    private CompanyRepository companyRepository;

    private DashboardService service;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        DashboardProperties properties = new DashboardProperties();
        properties.getSavings().setReferenceSpreadPercentage(new BigDecimal("4"));
        service = new DashboardService(transactionRepository, companyRepository, new SavingsEstimator(properties));
    }

    private record Totals(String month, BigDecimal volumeBrl, long volumeMissing, BigDecimal savingsBrl,
                          long savingsMissing, BigDecimal spreadSum, long spreadCount) implements DashboardMonthlyTotals {
        @Override public String getMonth() { return month; }
        @Override public BigDecimal getVolumeBrl() { return volumeBrl; }
        @Override public long getVolumeMissing() { return volumeMissing; }
        @Override public BigDecimal getSavingsBrl() { return savingsBrl; }
        @Override public long getSavingsMissing() { return savingsMissing; }
        @Override public BigDecimal getSpreadSum() { return spreadSum; }
        @Override public long getSpreadCount() { return spreadCount; }
    }

    private void givenMonths(DashboardPeriod period, Totals... months) {
        when(transactionRepository.findMonthlyDashboardTotals(
                companyId, period.from(), period.toExclusive(), new BigDecimal("4"))).thenReturn(List.of(months));
    }

    @Test
    void remittanceVolume_withoutDates_coversFromTheFirstToTheLastMonthWithData() {
        DashboardPeriod period = new DashboardPeriod(null, null);
        givenMonths(period,
                new Totals("2025-11", new BigDecimal("10"), 0, null, 0, null, 0),
                new Totals("2026-01", new BigDecimal("30.555"), 2, null, 0, null, 0));

        DashboardSeriesResponse series = service.remittanceVolume(companyId, period);

        assertThat(series.points()).extracting(DashboardSeriesResponse.Point::month)
                .containsExactly("2025-11", "2025-12", "2026-01");
        assertThat(series.points()).extracting(DashboardSeriesResponse.Point::amount)
                .containsExactly("10.00", "0.00", "30.56");
        assertThat(series.total()).isEqualTo("40.56");
        assertThat(series.incompleteOperations()).isEqualTo(2);
        assertThat(series.currency()).isEqualTo("BRL");
        assertThat(series.granularity()).isEqualTo("MONTH");
    }

    @Test
    void savingsHistory_isEmptyWhenThePeriodHasNoSettledOperations() {
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        givenMonths(period);

        DashboardSeriesResponse series = service.savingsHistory(companyId, period);

        assertThat(series.points()).isEmpty();
        assertThat(series.total()).isEqualTo("0.00");
        assertThat(series.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(series.endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void savingsHistory_treatsAMonthWithOnlyIncompleteOperationsAsZero() {
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        givenMonths(period, new Totals("2026-01", null, 1, null, 1, null, 0));

        DashboardSeriesResponse series = service.savingsHistory(companyId, period);

        assertThat(series.points()).extracting(DashboardSeriesResponse.Point::amount).containsExactly("0.00");
        assertThat(series.incompleteOperations()).isEqualTo(1);
    }

    @Test
    void summary_addsTheMonthsAndAveragesTheRecordedSpread() {
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
        givenMonths(period,
                new Totals("2026-01", null, 0, new BigDecimal("10.005"), 1, new BigDecimal("0.90"), 2),
                new Totals("2026-02", null, 0, new BigDecimal("-2.00"), 0, new BigDecimal("0.45"), 1));

        DashboardSummaryResponse summary = service.summary(companyId, period);

        assertThat(summary.amount()).isEqualTo("8.01");
        assertThat(summary.averageEffectiveSpreadPercentage()).isEqualTo("0.45");
        assertThat(summary.referenceSpreadPercentage()).isEqualTo("4.00");
        assertThat(summary.incomplete()).isTrue();
        assertThat(summary.incompleteOperations()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"-1, 5", "0, 0", "0, 51"})
    void settlements_rejectsInvalidPagination(int page, int size) {
        DashboardPeriod period = new DashboardPeriod(null, null);

        assertThatThrownBy(() -> service.settlements(companyId, period, page, size))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void settlements_mapsAPendingPaymentWithoutSavingsAndFallsBackToTheLegalName() {
        DashboardPeriod period = new DashboardPeriod(null, null);
        BeneficiaryEntity beneficiary = new BeneficiaryEntity();
        beneficiary.setNickname("Fornecedor");
        beneficiary.setCountry("US");
        ImportTransactionEntity payment = new ImportTransactionEntity();
        payment.setId(UUID.randomUUID());
        payment.setCompanyId(companyId);
        payment.setStatus(TransactionStatus.PROCESSING);
        payment.setSettlementAmountBrl(new BigDecimal("100.00"));
        payment.setServiceFeeBrl(new BigDecimal("1.00"));
        payment.setBeneficiary(beneficiary);
        CompanyEntity company = new CompanyEntity();
        company.setId(companyId);
        company.setLegalName("Razão Social Ltda");
        company.setTradeName(" ");
        Page<ages.vstable.backend.entity.BaseTransactionEntity> page = new PageImpl<>(List.of(payment), PageRequest.of(0, 5), 1);
        when(transactionRepository.findLatestForDashboard(eq(companyId), any(), any(), eq(PageRequest.of(0, 5))))
                .thenReturn(page);
        when(companyRepository.findAllById(List.of(companyId))).thenReturn(List.of(company));

        DashboardSettlementResponse settlement = service.settlements(companyId, period, 0, 5).getContent().getFirst();

        assertThat(settlement.savings()).isNull();
        assertThat(settlement.destination()).isEqualTo("US");
        assertThat(settlement.companyName()).isEqualTo("Razão Social Ltda");
        assertThat(settlement.status()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void requireCompany_rejectsAnUnknownCompanyAndAcceptsTheConsolidatedView() {
        UUID unknown = UUID.randomUUID();
        when(companyRepository.existsById(unknown)).thenReturn(false);

        assertThatThrownBy(() -> service.requireCompany(unknown)).isInstanceOf(NotFoundException.class);
        service.requireCompany(null);
    }

    @Test
    void summary_withZeroIncompleteAndZeroSpreadCount_marksCompleteAndNullAverageSpread() {
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        givenMonths(period,
                new Totals("2026-01", new BigDecimal("100.00"), 0, new BigDecimal("5.00"), 0, BigDecimal.ZERO, 0));

        DashboardSummaryResponse summary = service.summary(companyId, period);

        assertThat(summary.amount()).isEqualTo("5.00");
        assertThat(summary.averageEffectiveSpreadPercentage()).isNull();
        assertThat(summary.incomplete()).isFalse();
        assertThat(summary.incompleteOperations()).isZero();
    }

    @Test
    void settlements_acceptsMinAndMaxPageSizeBoundariesAndMapsExportAndTradeName() {
        DashboardPeriod period = new DashboardPeriod(null, null);
        UUID secondCompanyId = UUID.randomUUID();

        ages.vstable.backend.entity.ExportTransactionEntity exportTx = new ages.vstable.backend.entity.ExportTransactionEntity();
        exportTx.setId(UUID.randomUUID());
        exportTx.setCompanyId(companyId);
        exportTx.setStatus(TransactionStatus.SETTLED);
        exportTx.setExternalPayerName("Overseas Payer");

        ages.vstable.backend.entity.BaseTransactionEntity baseTx = new ages.vstable.backend.entity.BaseTransactionEntity();
        baseTx.setId(UUID.randomUUID());
        baseTx.setCompanyId(secondCompanyId);
        baseTx.setStatus(TransactionStatus.PROCESSING);

        CompanyEntity withTradeName = new CompanyEntity();
        withTradeName.setId(companyId);
        withTradeName.setLegalName("Legal One");
        withTradeName.setTradeName("Trade One");

        CompanyEntity withNullTradeName = new CompanyEntity();
        withNullTradeName.setId(secondCompanyId);
        withNullTradeName.setLegalName("Legal Two");
        withNullTradeName.setTradeName(null);

        Page<ages.vstable.backend.entity.BaseTransactionEntity> pageSize1 =
                new PageImpl<>(List.of(exportTx), PageRequest.of(0, 1), 1);
        Page<ages.vstable.backend.entity.BaseTransactionEntity> pageSize50 =
                new PageImpl<>(List.of(exportTx, baseTx), PageRequest.of(0, 50), 2);

        when(transactionRepository.findLatestForDashboard(eq(companyId), any(), any(), eq(PageRequest.of(0, 1))))
                .thenReturn(pageSize1);
        when(transactionRepository.findLatestForDashboard(eq(companyId), any(), any(), eq(PageRequest.of(0, 50))))
                .thenReturn(pageSize50);
        when(companyRepository.findAllById(List.of(companyId))).thenReturn(List.of(withTradeName));
        when(companyRepository.findAllById(List.of(companyId, secondCompanyId)))
                .thenReturn(List.of(withTradeName, withNullTradeName));

        Page<DashboardSettlementResponse> resultMin = service.settlements(companyId, period, 0, 1);
        assertThat(resultMin.getContent()).hasSize(1);
        assertThat(resultMin.getContent().getFirst().direction())
                .isEqualTo(ages.vstable.backend.entity.enums.TransferDirection.RECEIPT);
        assertThat(resultMin.getContent().getFirst().counterparty()).isEqualTo("Overseas Payer");
        assertThat(resultMin.getContent().getFirst().companyName()).isEqualTo("Trade One");

        Page<DashboardSettlementResponse> resultMax = service.settlements(companyId, period, 0, 50);
        assertThat(resultMax.getContent()).hasSize(2);
        assertThat(resultMax.getContent().get(1).direction()).isNull();
        assertThat(resultMax.getContent().get(1).counterparty()).isNull();
        assertThat(resultMax.getContent().get(1).companyName()).isEqualTo("Legal Two");
    }
}
