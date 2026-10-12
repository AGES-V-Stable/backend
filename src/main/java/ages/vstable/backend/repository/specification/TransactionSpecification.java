package ages.vstable.backend.repository.specification;

import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TransactionSpecification {

    public static Specification<BaseTransactionEntity> filterTransactions(UUID companyId, TransactionFilterDTO filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (companyId != null) {
                predicates.add(cb.equal(root.get("companyId"), companyId));
            }

            if (filter != null) {
                if (filter.getStartDate() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.getStartDate()));
                }
                if (filter.getEndDate() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.getEndDate()));
                }
                if (filter.getStatus() != null && !filter.getStatus().isEmpty()) {
                    predicates.add(cb.equal(root.get("status").as(String.class), filter.getStatus()));
                }
                if (filter.getMinAmount() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("settlementAmountBrl"), filter.getMinAmount()));
                }
                if (filter.getMaxAmount() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("settlementAmountBrl"), filter.getMaxAmount()));
                }
                if (filter.getType() != null && !filter.getType().isEmpty()) {
                    if ("IMPORT".equalsIgnoreCase(filter.getType())) {
                        predicates.add(cb.equal(root.type(), cb.literal(ImportTransactionEntity.class)));
                    } else if ("EXPORT".equalsIgnoreCase(filter.getType())) {
                        predicates.add(cb.equal(root.type(), cb.literal(ExportTransactionEntity.class)));
                    }
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

