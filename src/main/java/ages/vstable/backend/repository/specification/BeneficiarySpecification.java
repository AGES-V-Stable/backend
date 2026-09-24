package ages.vstable.backend.repository.specification;

import ages.vstable.backend.entity.BeneficiaryEntity;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BeneficiarySpecification {
    public static Specification<BeneficiaryEntity> filterBy(UUID companyId, String search, String document,
            String country) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (companyId != null) {
                predicates.add(cb.equal(root.get("company").get("id"), companyId));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("nickname")), "%" + search.toLowerCase() + "%"));
            }
            if (document != null && !document.isBlank()) {
                predicates.add(cb.equal(root.get("identificationDocument"), document));
            }
            if (country != null && !country.isBlank()) {
                predicates.add(cb.equal(root.get("country"), country));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

}
