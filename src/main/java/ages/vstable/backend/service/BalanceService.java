package ages.vstable.backend.service;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
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

    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999.99");
    private final AveniaKycVerificationRepository kycRepository;
    private final SimulatedDepositProperties simulatedDeposits;

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID companyId) {
        CompanyEntity company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        BigDecimal balance = company.getAvailableBalanceBrl() == null
                ? BigDecimal.ZERO
                : company.getAvailableBalanceBrl();
        return new BalanceResponse(balance.setScale(SCALE).toPlainString());
    }

    @Transactional
    public BalanceResponse addBalance(UserEntity user, BigDecimal amount) {
        if (!simulatedDeposits.isEnabled()) {
            // Fora dos ambientes de teste a rota se comporta como inexistente: nenhum saldo fictício em produção.
            throw new NotFoundException("SIMULATED_DEPOSITS_DISABLED",
                    "O crédito simulado não está disponível neste ambiente.");
        }
        CompanyAccess.requireOwner(user);

        boolean verified = kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).map(kyc -> kyc.getStatus() == ComplianceStatus.APPROVED).orElse(false);

        if (!verified) {
            throw new ForbiddenException("KYC_NOT_APPROVED", "É necessário ter a identidade verificada.");
        }

        CompanyEntity company = companyRepository.findByIdForUpdate(user.getMembership().getCompanyId()).orElseThrow(() -> new NotFoundException("Company not found"));

        BigDecimal currentBalance = company.getAvailableBalanceBrl();
        if (currentBalance == null) {
            currentBalance = BigDecimal.ZERO;
        }

        BigDecimal updatedBalance = currentBalance.add(amount);
        if (updatedBalance.compareTo(MAX_BALANCE) > 0) {
            throw new ConflictException("BALANCE_LIMIT_EXCEEDED", "O novo saldo excedeu o limite permitido.");
        }

        company.setAvailableBalanceBrl(updatedBalance);
        return new BalanceResponse(updatedBalance.setScale(SCALE).toPlainString());

    }

}
