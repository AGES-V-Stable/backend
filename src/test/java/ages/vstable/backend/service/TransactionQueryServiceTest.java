package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionDetailsResponseDTO;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.exception.InvalidDateRangeException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.service.dashboard.DashboardProperties;
import ages.vstable.backend.service.dashboard.SavingsEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionQueryServiceTest {

    @Mock
    private BaseTransactionRepository transactionRepository;

    @Spy
    private SavingsEstimator savingsEstimator = savingsEstimator();

    @InjectMocks
    private TransactionQueryService transactionQueryService;

    private static SavingsEstimator savingsEstimator() {
        DashboardProperties properties = new DashboardProperties();
        properties.getSavings().setReferenceSpreadPercentage(new BigDecimal("4.00"));
        return new SavingsEstimator(properties);
    }

    private ExportTransactionEntity receipt(UUID transactionId, UUID companyId, TransactionStatus status) {
        ExportTransactionEntity entity = new ExportTransactionEntity();
        entity.setId(transactionId);
        entity.setCompanyId(companyId);
        entity.setStatus(status);
        entity.setForeignAmount(new BigDecimal("1000"));
        entity.setExchangeRate(new BigDecimal("5.0"));
        entity.setSettlementAmountBrl(new BigDecimal("5000.00"));
        entity.setServiceFeeBrl(new BigDecimal("10.00"));
        entity.setEffectiveSpreadPercentage(new BigDecimal("0.20"));
        entity.setExternalPayerName("Test Payer");
        return entity;
    }

    @Test
    void testGetDetails_Success() {
        UUID transactionId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(receipt(transactionId, companyId, TransactionStatus.SETTLED)));

        TransactionDetailsResponseDTO response = transactionQueryService.getDetails(transactionId, companyId);

        assertNotNull(response);
        assertEquals("RECEBIMENTO", response.getType());
        assertEquals("Test Payer", response.getCounterpartyName());
        // Mesma regra do dashboard: (5000 − 10) × 4% − 10.
        assertEquals(new BigDecimal("189.60"), response.getEstimatedSavingsBrl());
    }

    @Test
    void testGetDetails_NotSettledOrWithoutFee_HasNoEstimatedSavings() {
        UUID pendingId = UUID.randomUUID();
        UUID withoutFeeId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        ExportTransactionEntity withoutFee = receipt(withoutFeeId, companyId, TransactionStatus.SETTLED);
        withoutFee.setServiceFeeBrl(null);
        when(transactionRepository.findById(pendingId))
                .thenReturn(Optional.of(receipt(pendingId, companyId, TransactionStatus.PROCESSING)));
        when(transactionRepository.findById(withoutFeeId)).thenReturn(Optional.of(withoutFee));

        assertNull(transactionQueryService.getDetails(pendingId, companyId).getEstimatedSavingsBrl());
        assertNull(transactionQueryService.getDetails(withoutFeeId, companyId).getEstimatedSavingsBrl());
    }

    @Test
    void testGetHistory_StartAfterEnd_ThrowsInvalidDateRangeWithoutQuerying() {
        TransactionFilterDTO filter = new TransactionFilterDTO();
        filter.setStartDate(OffsetDateTime.parse("2026-02-01T00:00:00-03:00"));
        filter.setEndDate(OffsetDateTime.parse("2026-01-01T00:00:00-03:00"));

        assertThrows(InvalidDateRangeException.class,
                () -> transactionQueryService.getHistory(UUID.randomUUID(), filter, Pageable.unpaged()));

        verifyNoInteractions(transactionRepository);
    }

    @Test
    void testGetHistory_SameStartAndEnd_Queries() {
        OffsetDateTime instant = OffsetDateTime.parse("2026-01-15T12:00:00-03:00");
        TransactionFilterDTO filter = new TransactionFilterDTO();
        filter.setStartDate(instant);
        filter.setEndDate(instant);
        when(transactionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        Page<TransactionHistoryResponseDTO> result =
                transactionQueryService.getHistory(UUID.randomUUID(), filter, Pageable.unpaged());

        assertTrue(result.isEmpty());
        verify(transactionRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void testGetDetails_IdorProtection_ThrowsNotFound() {
        UUID transactionId = UUID.randomUUID();
        UUID ownerCompanyId = UUID.randomUUID();
        UUID attackerCompanyId = UUID.randomUUID();

        ExportTransactionEntity mockEntity = new ExportTransactionEntity();
        mockEntity.setId(transactionId);
        mockEntity.setCompanyId(ownerCompanyId);

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(mockEntity));

        assertThrows(NotFoundException.class, () -> {
            transactionQueryService.getDetails(transactionId, attackerCompanyId);
        });
    }
}

