package ages.vstable.backend;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BankAccountType;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.repository.AdministratorRepository;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.UserRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
@Profile("dev")
public class DevelopmentDataSeeder implements CommandLineRunner {

    private static final String USER_EMAIL = "dev.user@example.test";

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final AdministratorRepository administratorRepository;
    private final AveniaKycVerificationRepository kycRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final BaseTransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public DevelopmentDataSeeder(CompanyRepository companyRepository, UserRepository userRepository,
                                 AdministratorRepository administratorRepository,
                                 AveniaKycVerificationRepository kycRepository,
                                 BeneficiaryRepository beneficiaryRepository,
                                 BaseTransactionRepository transactionRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.seed.password:}") String password) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.administratorRepository = administratorRepository;
        this.kycRepository = kycRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(String @NonNull ... args) {
        // This user is the seed marker: all records commit together or roll back together.
        if (userRepository.existsByEmail(USER_EMAIL)) {
            return;
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("Set DEV_SEED_PASSWORD before starting with the dev profile to seed test data");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompanyEntity company = seedCompany(now);
        UserEntity user = seedUser(company, now);
        seedAdministrator(now);
        seedKyc(user, now);
        BeneficiaryEntity bankBeneficiary = seedBankBeneficiary(company, now);
        BeneficiaryEntity walletBeneficiary = seedWalletBeneficiary(company, now);
        seedTransactions(user, bankBeneficiary, walletBeneficiary, now);
    }

    private CompanyEntity seedCompany(OffsetDateTime now) {
        CompanyEntity company = CompanyEntity.builder()
                .legalName("V-Stable Development Company Ltda")
                .tradeName("V-Stable Demo")
                .cnpj("11222333000181")
                .country("Brasil")
                .zipCode("90000000")
                .city("Porto Alegre")
                .state("RS")
                .kybStatus(ComplianceStatus.APPROVED)
                .amlStatus(ComplianceStatus.APPROVED)
                .availableBalanceBrl(new BigDecimal("25000.00"))
                .createdAt(now)
                .updatedAt(now)
                .build();
        return companyRepository.saveAndFlush(company);
    }

    private UserEntity seedUser(CompanyEntity company, OffsetDateTime now) {
        UserEntity user = UserEntity.builder()
                .companyId(company.getId())
                .fullName("Development User")
                .email(USER_EMAIL)
                .passwordHash(passwordEncoder.encode(password))
                .passwordSalt(BCrypt.gensalt())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return userRepository.saveAndFlush(user);
    }

    private void seedAdministrator(OffsetDateTime now) {
        administratorRepository.save(AdministratorEntity.builder()
                .fullName("Development Administrator")
                .email("dev.admin@example.test")
                .passwordHash(passwordEncoder.encode(password))
                .passwordSalt(BCrypt.gensalt())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void seedKyc(UserEntity user, OffsetDateTime now) {
        kycRepository.save(AveniaKycVerificationEntity.builder()
                .userId(user.getId())
                .status(ComplianceStatus.APPROVED)
                .responsePayload("{}")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private BeneficiaryEntity seedBankBeneficiary(CompanyEntity company, OffsetDateTime now) {
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder()
                .company(company)
                .nickname("Demo supplier")
                .internalDescription("Synthetic bank beneficiary for local testing")
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .beneficiaryType("LEGAL_ENTITY")
                .accountHolderName("Demo Supplier Ltda")
                .identificationDocument("12345678000195")
                .bankName("Development Bank")
                .bankCode("000")
                .swiftBic("TESTBRSPXXX")
                .branchNumber("0001")
                .accountNumber("00000001")
                .accountType(BankAccountType.checking)
                .country("Brasil")
                .address("Demo Street, 100")
                .currency("USD")
                .createdAt(now)
                .updatedAt(now)
                .build();
        return beneficiaryRepository.saveAndFlush(beneficiary);
    }

    private BeneficiaryEntity seedWalletBeneficiary(CompanyEntity company, OffsetDateTime now) {
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder()
                .company(company)
                .nickname("Demo wallet")
                .internalDescription("Synthetic wallet beneficiary for local testing")
                .receivingMethod(ReceivingMethod.CRYPTO_WALLET)
                .beneficiaryType("LEGAL_ENTITY")
                .accountHolderName("Demo Wallet Company")
                .blockchainNetwork(BlockchainNetwork.polygon)
                .walletAddress("0x0000000000000000000000000000000000000000")
                .currency("USD")
                .createdAt(now)
                .updatedAt(now)
                .build();
        return beneficiaryRepository.saveAndFlush(beneficiary);
    }

    private void seedTransactions(UserEntity user, BeneficiaryEntity bankBeneficiary,
                                  BeneficiaryEntity walletBeneficiary, OffsetDateTime now) {
        ImportTransactionEntity settledPayment = new ImportTransactionEntity();
        populateTransaction(settledPayment, user, TransactionStatus.SETTLED, "1000.00", now.minusDays(7));
        settledPayment.setBeneficiaryId(bankBeneficiary.getId());
        settledPayment.setTransferMethod(TransferMethod.TED);
        settledPayment.setSettledAt(now.minusDays(6));
        settledPayment.setUpdatedAt(now.minusDays(6));
        transactionRepository.save(settledPayment);

        ImportTransactionEntity processingPayment = new ImportTransactionEntity();
        populateTransaction(processingPayment, user, TransactionStatus.PROCESSING, "500.00", now.minusDays(1));
        processingPayment.setBeneficiaryId(walletBeneficiary.getId());
        processingPayment.setTransferMethod(TransferMethod.BLOCKCHAIN);
        transactionRepository.save(processingPayment);

        ExportTransactionEntity awaitingReceipt = new ExportTransactionEntity();
        populateTransaction(awaitingReceipt, user, TransactionStatus.AWAITING_PAYMENT, "2000.00", now);
        awaitingReceipt.setExternalBillingCode("DEV-SEED-INVOICE-001");
        awaitingReceipt.setExternalPayerName("Demo Overseas Customer");
        awaitingReceipt.setExternalPayerEmail("dev.customer@example.test");
        awaitingReceipt.setDueDate(now.toLocalDate().plusDays(14));
        awaitingReceipt.setReasonDescription("Development sample invoice");
        transactionRepository.save(awaitingReceipt);
    }

    private void populateTransaction(BaseTransactionEntity transaction, UserEntity user,
                                     TransactionStatus status, String amount, OffsetDateTime createdAt) {
        BigDecimal foreignAmount = new BigDecimal(amount);
        BigDecimal exchangeRate = new BigDecimal("5.00");
        BigDecimal convertedBrl = foreignAmount.multiply(exchangeRate);
        // Taxa de serviço de 0,45% sobre o valor convertido; o total liquidado inclui a taxa.
        BigDecimal serviceFee = convertedBrl.multiply(new BigDecimal("0.0045")).setScale(2, RoundingMode.HALF_UP);
        transaction.setCompanyId(user.getCompanyId());
        transaction.setCreatorUserId(user.getId());
        transaction.setStatus(status);
        transaction.setForeignCurrency("USD");
        transaction.setForeignAmount(foreignAmount);
        transaction.setSettlementAmountBrl(convertedBrl.add(serviceFee));
        transaction.setServiceFeeBrl(serviceFee);
        transaction.setEffectiveSpreadPercentage(new BigDecimal("0.01"));
        transaction.setExchangeRate(exchangeRate);
        transaction.setCreatedAt(createdAt);
        transaction.setUpdatedAt(createdAt);
    }
}
