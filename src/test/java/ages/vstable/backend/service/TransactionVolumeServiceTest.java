package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.exception.InvalidDateRangeException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionVolumeServiceTest {

    @Mock
    private BaseTransactionRepository transactionRepository;

    @InjectMocks
    private TransactionVolumeService service;

    private final UUID companyId = UUID.randomUUID();

    private static final OffsetDateTime EARLIEST = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATEST = OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource({
            "0, 0.00",
            "10.5, 10.50",
            "412000, 412000.00",
            "1234567890123.45, 1234567890123.45"
    })
    void getSettledVolume_withoutDates_sumsTheWholeHistoryAsDecimalStringWithTwoPlaces(String stored, String expected) {
        when(transactionRepository.sumSettlementAmountBrlCreatedBetween(companyId, TransactionStatus.SETTLED, EARLIEST, LATEST))
                .thenReturn(new BigDecimal(stored));

        TransactionVolumeResponse response = service.getSettledVolume(companyId, null, null);

        assertThat(response.transactedVolumeBrl()).isEqualTo(expected);
        verify(transactionRepository).sumSettlementAmountBrlCreatedBetween(companyId, TransactionStatus.SETTLED, EARLIEST, LATEST);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void getSettledVolume_returnsZeroWhenThereAreNoSettledTransactions() {
        when(transactionRepository.sumSettlementAmountBrlCreatedBetween(companyId, TransactionStatus.SETTLED, EARLIEST, LATEST))
                .thenReturn(null);

        TransactionVolumeResponse response = service.getSettledVolume(companyId, null, null);

        assertThat(response.transactedVolumeBrl()).isEqualTo("0.00");
    }

    @Test
    void getSettledVolume_withStartDate_filtersFromTheStartOfThatDayInTheBusinessTimeZone() {
        OffsetDateTime startOfDay = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3));
        when(transactionRepository.sumSettlementAmountBrlCreatedBetween(companyId, TransactionStatus.SETTLED, startOfDay, LATEST))
                .thenReturn(new BigDecimal("75.30"));

        TransactionVolumeResponse response = service.getSettledVolume(companyId, LocalDate.of(2026, 10, 1), null);

        assertThat(response.transactedVolumeBrl()).isEqualTo("75.30");
        verify(transactionRepository).sumSettlementAmountBrlCreatedBetween(companyId, TransactionStatus.SETTLED, startOfDay, LATEST);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void getSettledVolume_withEndDate_includesTheWholeEndDayInTheBusinessTimeZone() {
        OffsetDateTime startOfNextDay = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3));
        when(transactionRepository.sumSettlementAmountBrlCreatedBetween(companyId, TransactionStatus.SETTLED, EARLIEST, startOfNextDay))
                .thenReturn(new BigDecimal("12.00"));

        TransactionVolumeResponse response = service.getSettledVolume(companyId, null, LocalDate.of(2026, 10, 31));

        assertThat(response.transactedVolumeBrl()).isEqualTo("12.00");
    }

    @Test
    void getSettledVolume_withoutCompany_sumsAllCompanies() {
        when(transactionRepository.sumSettlementAmountBrlCreatedBetween(null, TransactionStatus.SETTLED, EARLIEST, LATEST))
                .thenReturn(new BigDecimal("900.00"));

        TransactionVolumeResponse response = service.getSettledVolume(null, null, null);

        assertThat(response.transactedVolumeBrl()).isEqualTo("900.00");
    }

    @Test
    void getSettledVolume_withStartDateAfterEndDate_throwsWithoutQuerying() {
        assertThatThrownBy(() -> service.getSettledVolume(companyId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(InvalidDateRangeException.class);

        verifyNoMoreInteractions(transactionRepository);
    }
}
