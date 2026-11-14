package ages.vstable.backend.service;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.balance.BalanceResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.MockTopUpReceiptEntity;
import ages.vstable.backend.dto.balance.MockTopUpResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.ForbiddenException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.MockTopUpReceiptRepository;
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
    private final MockTopUpReceiptRepository mockTopUpReceiptRepository;

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
        requireCreditAccess(user);
        CompanyEntity company = lockedCompany(user);
        return new BalanceResponse(applyCredit(company, amount).setScale(SCALE).toPlainString());
    }

    /**
     * Opcional durante a migração do frontend: requisições sem chave preservam o contrato
     * legado. Com chave, o lock da empresa serializa leitura, incremento e gravação do
     * recibo em uma única transação, inclusive entre instâncias Lambda diferentes.
     */
    @Transactional
    public MockTopUpResponse addMockTopUp(UserEntity user, BigDecimal amount, String requestKey) {
        requireCreditAccess(user);
        CompanyEntity company = lockedCompany(user);
        if (requestKey != null) {
            var previous = mockTopUpReceiptRepository.findByCompanyIdAndRequestKey(company.getId(), requestKey);
            if (previous.isPresent()) {
                MockTopUpReceiptEntity receipt = previous.get();
                if (receipt.getAmountBrl().compareTo(amount) != 0) {
                    throw new ConflictException("MOCK_TOPUP_IDEMPOTENCY_CONFLICT",
                            "Esta chave já foi utilizada com outro valor.");
                }
                return new MockTopUpResponse(receipt.getAmountBrl().setScale(SCALE).toPlainString(),
                        receipt.getResultingBalanceBrl().setScale(SCALE).toPlainString());
            }
        }

        BigDecimal result = applyCredit(company, amount);
        if (requestKey != null) {
            mockTopUpReceiptRepository.save(MockTopUpReceiptEntity.builder()
                    .id(UUID.randomUUID())
                    .companyId(company.getId())
                    .requestKey(requestKey)
                    .amountBrl(amount)
                    .resultingBalanceBrl(result)
                    .build());
        }
        return new MockTopUpResponse(amount.setScale(SCALE).toPlainString(),
                result.setScale(SCALE).toPlainString());
    }

    private void requireCreditAccess(UserEntity user) {
        if (!simulatedDeposits.isEnabled()) {
            throw new NotFoundException("SIMULATED_DEPOSITS_DISABLED",
                    "O crédito simulado não está disponível neste ambiente.");
        }
        CompanyAccess.requireOwner(user);

        boolean verified = kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .map(kyc -> kyc.getStatus() == ComplianceStatus.APPROVED).orElse(false);
        if (!verified) {
            throw new ForbiddenException("KYC_NOT_APPROVED", "É necessário ter a identidade verificada.");
        }
    }

    private CompanyEntity lockedCompany(UserEntity user) {
        return companyRepository.findByIdForUpdate(user.getMembership().getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found"));
    }

    private BigDecimal applyCredit(CompanyEntity company, BigDecimal amount) {
        BigDecimal currentBalance = company.getAvailableBalanceBrl();
        if (currentBalance == null) currentBalance = BigDecimal.ZERO;
        BigDecimal updatedBalance = currentBalance.add(amount);
        if (updatedBalance.compareTo(MAX_BALANCE) > 0) {
            throw new ConflictException("BALANCE_LIMIT_EXCEEDED", "O novo saldo excedeu o limite permitido.");
        }
        company.setAvailableBalanceBrl(updatedBalance);
        return updatedBalance;
    }

}
