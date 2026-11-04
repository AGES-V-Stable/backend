package ages.vstable.backend.service.dashboard;

import ages.vstable.backend.dto.dashboard.DashboardSeriesResponse;
import ages.vstable.backend.dto.dashboard.DashboardSettlementResponse;
import ages.vstable.backend.dto.dashboard.DashboardSummaryResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.service.TransactionVolumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Valida no PostgreSQL real as agregações do dashboard: agrupamento mensal no fuso de São Paulo,
 * data de referência, escopo por empresa e a fórmula de economia igual à do {@link SavingsEstimator}.
 * Ignorado quando não há Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DashboardIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("dashboard.savings.reference-spread-percentage", () -> "4.00");
    }

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private TransactionVolumeService transactionVolumeService;

    @Autowired
    private BaseTransactionRepository transactionRepository;

    @Autowired
    private SavingsEstimator savingsEstimator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyA;
    private UUID companyB;
    private UUID beneficiaryA;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM import_transactions");
        jdbcTemplate.update("DELETE FROM export_transactions");
        jdbcTemplate.update("DELETE FROM base_transactions");
        jdbcTemplate.update("DELETE FROM beneficiaries");
        jdbcTemplate.update("DELETE FROM companies");
        companyA = company("Empresa A Ltda", "Empresa A", "11.111.111/0001-11");
        companyB = company("Empresa B Ltda", null, "22.222.222/0001-22");
        beneficiaryA = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO beneficiaries (id, company_id, nickname, country, bank_name)
                VALUES (?, ?, 'Shenzhen AeroCrop', 'CN', 'Agricultural Bank of China')
                """, beneficiaryA, companyA);
    }

    @Test
    void remittanceVolume_groupsByMonthInSaoPauloTimeZone() {
        // 2026-03-01T02:00Z ainda é 28/02 em São Paulo; 03:00Z já é 01/03.
        payment(companyA, TransactionStatus.SETTLED, "100.00", "1.00", "2026-03-01T02:00:00Z", null);
        payment(companyA, TransactionStatus.SETTLED, "200.00", "2.00", "2026-03-01T03:00:00Z", null);

        DashboardSeriesResponse series = dashboardService.remittanceVolume(
                companyA, new DashboardPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31)));

        assertThat(series.points()).extracting(DashboardSeriesResponse.Point::month, DashboardSeriesResponse.Point::amount)
                .containsExactly(
                        tuple("2026-02", "100.00"),
                        tuple("2026-03", "200.00"));
        assertThat(series.total()).isEqualTo("300.00");
    }

    @Test
    void remittanceVolume_datesByCreationWhenSettlementWasNotRecorded_andFillsEmptyMonthsWithZero() {
        payment(companyA, TransactionStatus.SETTLED, "50.00", "1.00", null, "2026-01-10T12:00:00Z");

        DashboardSeriesResponse series = dashboardService.remittanceVolume(
                companyA, new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));

        assertThat(series.points()).extracting(DashboardSeriesResponse.Point::amount)
                .containsExactly("50.00", "0.00", "0.00");
    }

    @Test
    void remittanceVolume_countsOnlySettledTransactionsAndReturnsNoPointsWithoutThem() {
        payment(companyA, TransactionStatus.PROCESSING, "999.00", "1.00", null, "2026-01-10T12:00:00Z");
        payment(companyA, TransactionStatus.FAILED, "999.00", "1.00", null, "2026-01-11T12:00:00Z");

        DashboardSeriesResponse series = dashboardService.remittanceVolume(
                companyA, new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertThat(series.points()).isEmpty();
        assertThat(series.total()).isEqualTo("0.00");
    }

    @Test
    void metrics_scopeToTheCompanyOrConsolidateWithoutIt() {
        payment(companyA, TransactionStatus.SETTLED, "100.00", "1.00", "2026-01-10T12:00:00Z", null);
        receipt(companyB, TransactionStatus.SETTLED, "300.00", "3.00", "2026-01-11T12:00:00Z");
        DashboardPeriod january = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(dashboardService.remittanceVolume(companyA, january).total()).isEqualTo("100.00");
        assertThat(dashboardService.remittanceVolume(companyB, january).total()).isEqualTo("300.00");
        assertThat(dashboardService.remittanceVolume(null, january).total()).isEqualTo("400.00");
        assertThat(transactionVolumeService.getSettledVolume(null, null, null).transactedVolumeBrl()).isEqualTo("400.00");
        assertThat(transactionVolumeService.getSettledVolume(companyA, null, null).transactedVolumeBrl()).isEqualTo("100.00");
    }

    @Test
    void savings_aggregatedInTheDatabaseMatchesTheEstimatorAndSkipsIncompleteOperations() {
        payment(companyA, TransactionStatus.SETTLED, "1000.00", "10.00", "2026-01-10T12:00:00Z", null);
        payment(companyA, TransactionStatus.SETTLED, "333.33", "0.00", "2026-01-12T12:00:00Z", null);
        payment(companyA, TransactionStatus.SETTLED, null, "5.00", "2026-01-15T12:00:00Z", null);
        DashboardPeriod january = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        DashboardSummaryResponse summary = dashboardService.summary(companyA, january);

        BigDecimal expected = savingsEstimator.estimate(new BigDecimal("1000.00"), new BigDecimal("10.00"))
                .add(savingsEstimator.estimate(new BigDecimal("333.33"), new BigDecimal("0.00")));
        assertThat(summary.amount()).isEqualTo(expected.toPlainString()).isEqualTo("42.93");
        assertThat(summary.incomplete()).isTrue();
        assertThat(summary.incompleteOperations()).isEqualTo(1);
        assertThat(dashboardService.savingsHistory(companyA, january).points())
                .extracting(DashboardSeriesResponse.Point::amount).containsExactly("42.93");
    }

    @Test
    void savings_roundsEachOperationSoThePointsAddUpToTheTotalAndMatchTheTable() {
        // 100.13 × 4% = 4.0052 por operação: arredondar só a soma daria 8.01 em vez de 4.01 + 4.01.
        payment(companyA, TransactionStatus.SETTLED, "100.13", "0.00", "2026-01-10T12:00:00Z", null);
        payment(companyA, TransactionStatus.SETTLED, "100.13", "0.00", "2026-02-10T12:00:00Z", null);
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));

        DashboardSeriesResponse history = dashboardService.savingsHistory(companyA, period);

        assertThat(history.points()).extracting(DashboardSeriesResponse.Point::amount).containsExactly("4.01", "4.01");
        assertThat(history.total()).isEqualTo("8.02");
        assertThat(dashboardService.summary(companyA, period).amount()).isEqualTo("8.02");
        assertThat(dashboardService.settlements(companyA, period, 0, 5).getContent())
                .extracting(DashboardSettlementResponse::savings).containsExactly("4.01", "4.01");
    }

    @Test
    void metricsAndTable_preferTheSettlementDateOverTheCreationDate() {
        payment(companyA, TransactionStatus.SETTLED, "70.00", "0.00", "2026-03-10T12:00:00Z", "2026-01-10T12:00:00Z");
        payment(companyA, TransactionStatus.SETTLED, "20.00", "0.00", "2026-02-10T12:00:00Z", null);
        DashboardPeriod period = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        DashboardSeriesResponse volume = dashboardService.remittanceVolume(companyA, period);

        assertThat(volume.points()).extracting(DashboardSeriesResponse.Point::month, DashboardSeriesResponse.Point::amount)
                .containsExactly(tuple("2026-01", "0.00"), tuple("2026-02", "20.00"), tuple("2026-03", "70.00"));
        assertThat(dashboardService.settlements(companyA, period, 0, 5).getContent())
                .extracting(DashboardSettlementResponse::amountBrl).containsExactly("70.00", "20.00");
    }

    @Test
    void summary_returnsZeroWhenThereIsNoSavings() {
        DashboardSummaryResponse summary = dashboardService.summary(
                companyA, new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertThat(summary.amount()).isEqualTo("0.00");
        assertThat(summary.incomplete()).isFalse();
        assertThat(summary.averageEffectiveSpreadPercentage()).isNull();
    }

    @Test
    void period_includesTheWholeEndDayAndExcludesTheNextOne() {
        payment(companyA, TransactionStatus.SETTLED, "10.00", "0.00", "2026-02-01T02:59:59Z", null);
        payment(companyA, TransactionStatus.SETTLED, "20.00", "0.00", "2026-02-01T03:00:00Z", null);

        DashboardSeriesResponse january = dashboardService.remittanceVolume(
                companyA, new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertThat(january.total()).isEqualTo("10.00");
    }

    @Test
    void settlements_listAllStatusesFromTheMostRecent_withCounterpartyAndCompanyName() {
        payment(companyA, TransactionStatus.SETTLED, "100.00", "1.00", "2026-01-05T12:00:00Z", null);
        payment(companyA, TransactionStatus.PROCESSING, "200.00", "2.00", null, "2026-01-20T12:00:00Z");
        receipt(companyB, TransactionStatus.SETTLED, "300.00", "3.00", "2026-01-10T12:00:00Z");
        DashboardPeriod january = new DashboardPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        Page<DashboardSettlementResponse> all = dashboardService.settlements(null, january, 0, 5);
        Page<DashboardSettlementResponse> onlyA = dashboardService.settlements(companyA, january, 0, 1);

        assertThat(all.getContent()).extracting(DashboardSettlementResponse::amountBrl)
                .containsExactly("200.00", "300.00", "100.00");
        DashboardSettlementResponse latest = all.getContent().getFirst();
        assertThat(latest.direction()).isEqualTo(TransferDirection.PAYMENT);
        assertThat(latest.counterparty()).isEqualTo("Shenzhen AeroCrop");
        assertThat(latest.destination()).isEqualTo("CN • Agricultural Bank of China");
        assertThat(latest.companyName()).isEqualTo("Empresa A");
        assertThat(latest.savings()).isNull();
        DashboardSettlementResponse receipt = all.getContent().get(1);
        assertThat(receipt.direction()).isEqualTo(TransferDirection.RECEIPT);
        assertThat(receipt.counterparty()).isEqualTo("Pagador B");
        assertThat(receipt.companyName()).isEqualTo("Empresa B Ltda");
        assertThat(receipt.savings()).isEqualTo("8.88");
        assertThat(onlyA.getTotalElements()).isEqualTo(2);
        assertThat(onlyA.getTotalPages()).isEqualTo(2);
        assertThat(onlyA.getContent()).extracting(DashboardSettlementResponse::companyId).containsOnly(companyA);
    }

    @Test
    void transactionVolume_keepsFilteringByCreationDate() {
        payment(companyA, TransactionStatus.SETTLED, "10.00", "0.00", "2026-03-10T12:00:00Z", "2026-01-10T12:00:00Z");

        assertThat(transactionVolumeService.getSettledVolume(companyA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .transactedVolumeBrl()).isEqualTo("10.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    private UUID company(String legalName, String tradeName, String cnpj) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, legal_name, trade_name, cnpj) VALUES (?, ?, ?, ?)",
                id, legalName, tradeName, cnpj);
        return id;
    }

    private void payment(UUID companyId, TransactionStatus status, String amountBrl, String feeBrl,
                         String settledAt, String createdAt) {
        UUID id = base(companyId, status, amountBrl, feeBrl, settledAt, createdAt);
        jdbcTemplate.update("INSERT INTO import_transactions (transaction_id, beneficiary_id, transfer_method) "
                + "VALUES (?, ?, 'PIX'::transfer_method_enum)", id, beneficiaryA);
    }

    private void receipt(UUID companyId, TransactionStatus status, String amountBrl, String feeBrl, String settledAt) {
        UUID id = base(companyId, status, amountBrl, feeBrl, settledAt, null);
        jdbcTemplate.update("""
                INSERT INTO export_transactions (transaction_id, external_billing_code, external_payer_name, due_date, reason_description)
                VALUES (?, ?, 'Pagador B', DATE '2026-12-31', 'Venda')
                """, id, "INV-" + id);
    }

    private UUID base(UUID companyId, TransactionStatus status, String amountBrl, String feeBrl,
                      String settledAt, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO base_transactions (id, company_id, status, foreign_currency, foreign_amount,
                    settlement_amount_brl, service_fee_brl, exchange_rate, settled_at, created_at)
                VALUES (?, ?, ?::transaction_status_enum, 'USD', 10.00, ?, ?, 5.000000, ?, ?)
                """,
                id, companyId, status.name(),
                amountBrl == null ? null : new BigDecimal(amountBrl),
                feeBrl == null ? null : new BigDecimal(feeBrl),
                timestamp(settledAt),
                createdAt == null ? timestamp(settledAt) : timestamp(createdAt));
        return id;
    }

    private static OffsetDateTime timestamp(String instant) {
        return instant == null ? null : OffsetDateTime.parse(instant);
    }
}
