package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.dto.transfer.TransferResponse;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ExportTransactionRepository;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.specification.TransferSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Histórico de transferências a partir das transações persistidas localmente.
 * Somente leitura: criação/execução de pagamentos na Avenia/BlindPay ainda não
 * grava transações, então o histórico fica vazio até esse fluxo existir.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final BaseTransactionRepository baseTransactionRepository;
    private final ImportTransactionRepository importTransactionRepository;
    private final ExportTransactionRepository exportTransactionRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final CompanyRepository companyRepository;

    @Transactional(readOnly = true)
    public Page<TransferResponse> findTransfers(TransferFilter filter, Pageable pageable) {
        validate(filter);

        Page<BaseTransactionEntity> page =
                baseTransactionRepository.findAll(TransferSpecification.filterBy(filter), pageable);

        return new PageImpl<>(toResponses(page.getContent()), pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TransferResponse findById(UUID id) {
        BaseTransactionEntity transaction = baseTransactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transferência não encontrada"));
        return toResponses(List.of(transaction)).get(0);
    }

    private void validate(TransferFilter filter) {
        if (filter.startDate() != null && filter.endDate() != null && filter.startDate().isAfter(filter.endDate())) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        if (filter.minAmount() != null && filter.minAmount().signum() < 0
                || filter.maxAmount() != null && filter.maxAmount().signum() < 0) {
            throw new IllegalArgumentException("Amount filters must not be negative");
        }
        if (filter.minAmount() != null && filter.maxAmount() != null
                && filter.minAmount().compareTo(filter.maxAmount()) > 0) {
            throw new IllegalArgumentException("minAmount must be less than or equal to maxAmount");
        }
    }

    /** Loads the related rows for a whole page at once (no per-row queries). */
    private List<TransferResponse> toResponses(List<BaseTransactionEntity> transactions) {
        if (transactions.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = transactions.stream().map(BaseTransactionEntity::getId).toList();
        Map<UUID, ImportTransactionEntity> imports = byId(importTransactionRepository.findAllById(ids),
                ImportTransactionEntity::getTransactionId);
        Map<UUID, ExportTransactionEntity> exports = byId(exportTransactionRepository.findAllById(ids),
                ExportTransactionEntity::getTransactionId);
        Map<UUID, BeneficiaryEntity> beneficiaries = byId(beneficiaryRepository.findAllById(
                imports.values().stream().map(ImportTransactionEntity::getBeneficiaryId).collect(Collectors.toSet())),
                BeneficiaryEntity::getId);
        Map<UUID, CompanyEntity> companies = byId(companyRepository.findAllById(
                transactions.stream().map(BaseTransactionEntity::getCompanyId).collect(Collectors.toSet())),
                CompanyEntity::getId);

        return transactions.stream()
                .map(transaction -> toResponse(transaction,
                        imports.get(transaction.getId()),
                        exports.get(transaction.getId()),
                        beneficiaries,
                        companies.get(transaction.getCompanyId())))
                .toList();
    }

    private TransferResponse toResponse(BaseTransactionEntity transaction,
                                        ImportTransactionEntity importDetail,
                                        ExportTransactionEntity exportDetail,
                                        Map<UUID, BeneficiaryEntity> beneficiaries,
                                        CompanyEntity company) {
        TransferResponse response = new TransferResponse();
        response.setId(transaction.getId());
        response.setCompanyId(transaction.getCompanyId());
        if (company != null) {
            response.setCompanyName(company.getTradeName() != null ? company.getTradeName() : company.getLegalName());
        }

        if (importDetail != null) {
            response.setDirection(TransferDirection.PAYMENT);
            response.setBeneficiaryId(importDetail.getBeneficiaryId());
            response.setTransferMethod(importDetail.getTransferMethod());
            BeneficiaryEntity beneficiary = beneficiaries.get(importDetail.getBeneficiaryId());
            if (beneficiary != null) {
                response.setCounterpartyName(beneficiary.getAccountHolderName() != null
                        ? beneficiary.getAccountHolderName()
                        : beneficiary.getNickname());
            }
        } else if (exportDetail != null) {
            response.setDirection(TransferDirection.RECEIPT);
            response.setCounterpartyName(exportDetail.getExternalPayerName());
        }

        response.setStatus(transaction.getStatus());
        response.setForeignCurrency(transaction.getForeignCurrency());
        response.setForeignAmount(transaction.getForeignAmount());
        response.setSettlementAmountBrl(transaction.getSettlementAmountBrl());
        response.setServiceFeeBrl(transaction.getServiceFeeBrl());
        response.setExchangeRate(transaction.getExchangeRate());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setSettledAt(transaction.getSettledAt());
        return response;
    }

    private static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> idOf) {
        return items.stream().collect(Collectors.toMap(idOf, Function.identity(), (first, second) -> first));
    }
}
