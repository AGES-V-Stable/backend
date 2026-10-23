package ages.vstable.backend.service;

import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** Saldo disponível de uma empresa. A empresa é sempre a do usuário autenticado. */
@Service
@RequiredArgsConstructor
public class BalanceService {

    private static final int SCALE = 2;

    private final CompanyRepository companyRepository;

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID companyId) {
        CompanyEntity company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        BigDecimal balance = company.getAvailableBalanceBrl() == null
                ? BigDecimal.ZERO
                : company.getAvailableBalanceBrl();
        return new BalanceResponse(balance.setScale(SCALE).toPlainString());
    }
}
