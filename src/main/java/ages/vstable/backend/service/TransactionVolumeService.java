package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.service.dashboard.DashboardPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/** Volume transacionado: soma em BRL das transações liquidadas de uma empresa ou de todas. */
@Service
@RequiredArgsConstructor
public class TransactionVolumeService {

    private static final int SCALE = 2;

    private final BaseTransactionRepository transactionRepository;

    /**
     * Considera as transações criadas entre {@code startDate} e {@code endDate} (inclusivo), em dias de
     * America/Sao_Paulo; datas ausentes não limitam. Sem {@code companyId}, soma todas as empresas.
     */
    @Transactional(readOnly = true)
    public TransactionVolumeResponse getSettledVolume(UUID companyId, LocalDate startDate, LocalDate endDate) {
        DashboardPeriod period = new DashboardPeriod(startDate, endDate);
        BigDecimal total = transactionRepository.sumSettlementAmountBrlCreatedBetween(
                companyId, TransactionStatus.SETTLED, period.from(), period.toExclusive());
        BigDecimal volume = total == null ? BigDecimal.ZERO : total;
        return new TransactionVolumeResponse(volume.setScale(SCALE, RoundingMode.HALF_UP).toPlainString());
    }
}
