package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionDetailsResponseDTO;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock
    private BaseTransactionRepository transactionRepository;

    @InjectMocks
    private TransactionQueryService transactionQueryService;

    @Test
    void testGetDetails_Success() {
        UUID transactionId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        ExportTransactionEntity mockEntity = new ExportTransactionEntity();
        mockEntity.setId(transactionId);
        mockEntity.setCompanyId(companyId);
        mockEntity.setForeignAmount(new BigDecimal("1000"));
        mockEntity.setExchangeRate(new BigDecimal("5.0"));
        mockEntity.setEffectiveSpreadPercentage(new BigDecimal("0.02"));
        mockEntity.setExternalPayerName("Test Payer");

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(mockEntity));

        TransactionDetailsResponseDTO response = transactionQueryService.getDetails(transactionId, companyId);

        assertNotNull(response);
        assertEquals("RECEBIMENTO", response.getType());
        assertEquals("Test Payer", response.getCounterpartyName());
        
        assertEquals(new BigDecimal("100.00"), response.getEstimatedSavingsBrl());
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

