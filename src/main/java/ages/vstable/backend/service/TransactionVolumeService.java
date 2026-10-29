package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.repository.BaseTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Volume transacionado de uma empresa: soma em BRL das transações liquidadas. */
@Service
@RequiredArgsConstructor
public class TransactionVolumeService {

    private static final int SCALE = 2;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    private final BaseTransactionRepository transactionRepository;

    /** Sem {@code startDate} considera todo o histórico; com ela, só o que foi criado a partir desse dia. */
    @Transactional(readOnly = true)
    public TransactionVolumeResponse getSettledVolume(UUID companyId, LocalDate startDate) {
        BigDecimal total = startDate == null
                ? transactionRepository.sumSettlementAmountBrl(companyId, TransactionStatus.SETTLED)
                : transactionRepository.sumSettlementAmountBrlSince(
                        companyId, TransactionStatus.SETTLED, startDate.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime());
        BigDecimal volume = total == null ? BigDecimal.ZERO : total;
        return new TransactionVolumeResponse(volume.setScale(SCALE, RoundingMode.HALF_UP).toPlainString());
    }
}
