package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.repository.BaseTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Transactional
class TransactionQueryIntegrationTest {

    @Autowired
    private TransactionQueryService transactionQueryService;

    @Autowired
    private BaseTransactionRepository transactionRepository;

    private UUID companyId1;
    private UUID companyId2;
    private ExportTransactionEntity exportTx;
    private ImportTransactionEntity importTx;

    @BeforeEach
    void setUp() {
        companyId1 = UUID.randomUUID();
        companyId2 = UUID.randomUUID();

        exportTx = new ExportTransactionEntity();
        exportTx.setCompanyId(companyId1);
        exportTx.setStatus(TransactionStatus.SETTLED);
        exportTx.setSettlementAmountBrl(new BigDecimal("5000.00"));
        exportTx.setCreatedAt(LocalDateTime.now().minusDays(1));
        exportTx.setForeignAmount(new BigDecimal("1000.00"));
        exportTx.setExchangeRate(new BigDecimal("5.00"));
        exportTx.setEffectiveSpreadPercentage(new BigDecimal("0.02"));
        exportTx.setExternalPayerName("Payer 1");
        exportTx = transactionRepository.save(exportTx);

        importTx = new ImportTransactionEntity();
        importTx.setCompanyId(companyId1);
        importTx.setStatus(TransactionStatus.PENDING);
        importTx.setSettlementAmountBrl(new BigDecimal("10000.00"));
        importTx.setCreatedAt(LocalDateTime.now());
        importTx.setForeignAmount(new BigDecimal("2000.00"));
        importTx.setExchangeRate(new BigDecimal("5.00"));
        importTx.setEffectiveSpreadPercentage(new BigDecimal("0.02"));
        importTx.setBeneficiaryName("Beneficiary 1");
        importTx = transactionRepository.save(importTx);

        ExportTransactionEntity otherTx = new ExportTransactionEntity();
        otherTx.setCompanyId(companyId2);
        otherTx.setStatus(TransactionStatus.SETTLED);
        otherTx.setSettlementAmountBrl(new BigDecimal("1000.00"));
        otherTx.setCreatedAt(LocalDateTime.now());
        otherTx.setForeignAmount(new BigDecimal("200.00"));
        otherTx.setExchangeRate(new BigDecimal("5.00"));
        otherTx.setEffectiveSpreadPercentage(new BigDecimal("0.02"));
        otherTx.setExternalPayerName("Payer 2");
        transactionRepository.save(otherTx);
    }

    @Test
    void testGetHistory_NoFilters() {
        TransactionFilterDTO filter = new TransactionFilterDTO();
        Page<TransactionHistoryResponseDTO> page = transactionQueryService.getHistory(companyId1, filter, PageRequest.of(0, 10));

        assertEquals(2, page.getTotalElements());
    }

    @Test
    void testGetHistory_FilterByType() {
        TransactionFilterDTO filter = new TransactionFilterDTO();
        filter.setType("EXPORT");
        Page<TransactionHistoryResponseDTO> page = transactionQueryService.getHistory(companyId1, filter, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals("RECEBIMENTO", page.getContent().get(0).getType());
    }

    @Test
    void testGetHistory_FilterByAmount() {
        TransactionFilterDTO filter = new TransactionFilterDTO();
        filter.setMinAmount(new BigDecimal("8000.00"));
        Page<TransactionHistoryResponseDTO> page = transactionQueryService.getHistory(companyId1, filter, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals(importTx.getId(), page.getContent().get(0).getId());
    }

    @Test
    void testGetHistory_AdminWithoutCompanyId() {
        TransactionFilterDTO filter = new TransactionFilterDTO();
        Page<TransactionHistoryResponseDTO> page = transactionQueryService.getHistory(null, filter, PageRequest.of(0, 10));

        assertEquals(3, page.getTotalElements());
    }
}
