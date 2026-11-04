package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransactionDetailsResponseDTO;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.exception.InvalidDateRangeException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.specification.TransactionSpecification;
import ages.vstable.backend.service.dashboard.SavingsEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final BaseTransactionRepository transactionRepository;
    private final SavingsEstimator savingsEstimator;

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponseDTO> getHistory(UUID companyId, TransactionFilterDTO filter, Pageable pageable) {
        if (filter != null && filter.getStartDate() != null && filter.getEndDate() != null
                && filter.getStartDate().isAfter(filter.getEndDate())) {
            throw new InvalidDateRangeException();
        }
        Page<BaseTransactionEntity> transactions = transactionRepository.findAll(
                TransactionSpecification.filterTransactions(companyId, filter), pageable);

        return transactions.map(this::mapToHistoryDTO);
    }

    @Transactional(readOnly = true)
    public TransactionDetailsResponseDTO getDetails(UUID transactionId, UUID companyId) {
        BaseTransactionEntity entity = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction not found"));

        if (companyId != null && !entity.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Transaction not found");
        }

        return mapToDetailsDTO(entity);
    }

    private TransactionHistoryResponseDTO mapToHistoryDTO(BaseTransactionEntity entity) {
        String type = "DESCONHECIDO";
        String counterparty = "N/A";

        if (entity instanceof ImportTransactionEntity importEntity) {
            type = "PAGAMENTO";
            if (importEntity.getBeneficiary() != null) {
                counterparty = importEntity.getBeneficiary().getNickname();
            }
        } else if (entity instanceof ExportTransactionEntity exportEntity) {
            type = "RECEBIMENTO";
            counterparty = exportEntity.getExternalPayerName();
        }

        return TransactionHistoryResponseDTO.builder()
                .id(entity.getId())
                .companyId(entity.getCompanyId())
                .date(entity.getCreatedAt())
                .type(type)
                .amountBrl(entity.getSettlementAmountBrl())
                .amountForeign(entity.getForeignAmount())
                .foreignCurrency(entity.getForeignCurrency())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .counterparty(counterparty)
                .build();
    }

    private TransactionDetailsResponseDTO mapToDetailsDTO(BaseTransactionEntity entity) {
        String type = "DESCONHECIDO";
        String counterpartyName = "N/A";
        String counterpartyDetails = null;

        if (entity instanceof ImportTransactionEntity importEntity) {
            type = "PAGAMENTO";
            if (importEntity.getBeneficiary() != null) {
                counterpartyName = importEntity.getBeneficiary().getNickname();
                counterpartyDetails = "Conta: " + importEntity.getBeneficiary().getAccountHolderName();
            }
        } else if (entity instanceof ExportTransactionEntity exportEntity) {
            type = "RECEBIMENTO";
            counterpartyName = exportEntity.getExternalPayerName();
            counterpartyDetails = exportEntity.getExternalPayerEmail();
        }

        BigDecimal savings = savingsEstimator.estimate(entity);

        return TransactionDetailsResponseDTO.builder()
                .id(entity.getId())
                .companyId(entity.getCompanyId())
                .date(entity.getCreatedAt())
                .type(type)
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .amountForeign(entity.getForeignAmount())
                .foreignCurrency(entity.getForeignCurrency())
                .amountBrl(entity.getSettlementAmountBrl())
                .exchangeRate(entity.getExchangeRate())
                .spreadPercentage(entity.getEffectiveSpreadPercentage())
                .serviceFeeBrl(entity.getServiceFeeBrl())
                .estimatedSavingsBrl(savings)
                .counterpartyName(counterpartyName)
                .counterpartyDetails(counterpartyDetails)
                .build();
    }
}

