package ages.vstable.backend.repository.specification;

import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.TransferDirection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class TransferSpecification {

    /** Date filters are calendar days as seen by the (Brazilian) users of the platform. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    private TransferSpecification() {
    }

    public static Specification<BaseTransactionEntity> filterBy(TransferFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.companyId() != null) {
                predicates.add(cb.equal(root.get("companyId"), filter.companyId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.startDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        filter.startDate().atStartOfDay(BUSINESS_ZONE).toOffsetDateTime()));
            }
            if (filter.endDate() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"),
                        filter.endDate().plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime()));
            }
            if (filter.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("foreignAmount"), filter.minAmount()));
            }
            if (filter.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("foreignAmount"), filter.maxAmount()));
            }
            if (filter.direction() == TransferDirection.PAYMENT) {
                predicates.add(cb.exists(importOf(root, query, cb)));
            } else if (filter.direction() == TransferDirection.RECEIPT) {
                predicates.add(cb.exists(exportOf(root, query, cb)));
            }
            if (hasText(filter.search())) {
                predicates.add(cb.exists(companyMatching(root, query, cb, filter.search())));
            }
            if (hasText(filter.beneficiary())) {
                predicates.add(cb.or(
                        cb.exists(beneficiaryMatching(root, query, cb, filter.beneficiary())),
                        cb.exists(payerMatching(root, query, cb, filter.beneficiary()))));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Subquery<Integer> importOf(Root<BaseTransactionEntity> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<ImportTransactionEntity> imp = sub.from(ImportTransactionEntity.class);
        return sub.select(cb.literal(1)).where(cb.equal(imp.get("id"), root.get("id")));
    }

    private static Subquery<Integer> exportOf(Root<BaseTransactionEntity> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<ExportTransactionEntity> exp = sub.from(ExportTransactionEntity.class);
        return sub.select(cb.literal(1)).where(cb.equal(exp.get("id"), root.get("id")));
    }

    private static Subquery<Integer> companyMatching(Root<BaseTransactionEntity> root, CriteriaQuery<?> query,
                                                     CriteriaBuilder cb, String search) {
        String pattern = likePattern(search);
        String digits = search.replaceAll("\\D", "");

        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<CompanyEntity> company = sub.from(CompanyEntity.class);
        List<Predicate> matches = new ArrayList<>(List.of(
                cb.like(cb.lower(company.get("legalName")), pattern),
                cb.like(cb.lower(cb.coalesce(company.get("tradeName"), "")), pattern)));
        if (!digits.isEmpty()) {
            matches.add(cb.like(company.get("cnpj"), "%" + digits + "%"));
        }
        return sub.select(cb.literal(1)).where(
                cb.equal(company.get("id"), root.get("companyId")),
                cb.or(matches.toArray(new Predicate[0])));
    }

    private static Subquery<Integer> beneficiaryMatching(Root<BaseTransactionEntity> root, CriteriaQuery<?> query,
                                                         CriteriaBuilder cb, String name) {
        String pattern = likePattern(name);
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<ImportTransactionEntity> imp = sub.from(ImportTransactionEntity.class);
        Root<BeneficiaryEntity> beneficiary = sub.from(BeneficiaryEntity.class);
        return sub.select(cb.literal(1)).where(
                cb.equal(imp.get("id"), root.get("id")),
                cb.equal(beneficiary.get("id"), imp.get("beneficiaryId")),
                cb.or(
                        cb.like(cb.lower(beneficiary.get("nickname")), pattern),
                        cb.like(cb.lower(cb.coalesce(beneficiary.get("accountHolderName"), "")), pattern)));
    }

    private static Subquery<Integer> payerMatching(Root<BaseTransactionEntity> root, CriteriaQuery<?> query,
                                                   CriteriaBuilder cb, String name) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<ExportTransactionEntity> exp = sub.from(ExportTransactionEntity.class);
        return sub.select(cb.literal(1)).where(
                cb.equal(exp.get("id"), root.get("id")),
                cb.like(cb.lower(exp.get("externalPayerName")), likePattern(name)));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String likePattern(String value) {
        return "%" + value.trim().toLowerCase() + "%";
    }
}
