package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
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

    @ParameterizedTest
    @CsvSource({
            "0, 0.00",
            "10.5, 10.50",
            "412000, 412000.00",
            "1234567890123.45, 1234567890123.45"
    })
    void getSettledVolume_withoutStartDate_sumsTheWholeHistoryAsDecimalStringWithTwoPlaces(String stored, String expected) {
        when(transactionRepository.sumSettlementAmountBrl(companyId, TransactionStatus.SETTLED)).thenReturn(new BigDecimal(stored));

        TransactionVolumeResponse response = service.getSettledVolume(companyId, null);

        assertThat(response.transactedVolumeBrl()).isEqualTo(expected);
        verify(transactionRepository).sumSettlementAmountBrl(companyId, TransactionStatus.SETTLED);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void getSettledVolume_returnsZeroWhenThereAreNoSettledTransactions() {
        when(transactionRepository.sumSettlementAmountBrl(companyId, TransactionStatus.SETTLED)).thenReturn(null);

        TransactionVolumeResponse response = service.getSettledVolume(companyId, null);

        assertThat(response.transactedVolumeBrl()).isEqualTo("0.00");
    }

    @Test
    void getSettledVolume_withStartDate_filtersFromTheStartOfThatDayInTheBusinessTimeZone() {
        OffsetDateTime startOfDay = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3));
        when(transactionRepository.sumSettlementAmountBrlSince(companyId, TransactionStatus.SETTLED, startOfDay)).thenReturn(new BigDecimal("75.30"));

        TransactionVolumeResponse response = service.getSettledVolume(companyId, LocalDate.of(2026, 10, 1));

        assertThat(response.transactedVolumeBrl()).isEqualTo("75.30");
        verify(transactionRepository).sumSettlementAmountBrlSince(companyId, TransactionStatus.SETTLED, startOfDay);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void getSettledVolume_withStartDate_returnsZeroWhenNothingMatches() {
        when(transactionRepository.sumSettlementAmountBrlSince(companyId, TransactionStatus.SETTLED,
                OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3)))).thenReturn(null);

        TransactionVolumeResponse response = service.getSettledVolume(companyId, LocalDate.of(2030, 1, 1));

        assertThat(response.transactedVolumeBrl()).isEqualTo("0.00");
    }
}
