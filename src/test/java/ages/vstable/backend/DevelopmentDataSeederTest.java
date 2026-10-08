package ages.vstable.backend;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.UserEntity;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevelopmentDataSeederTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdministratorRepository administratorRepository;
    @Mock
    private AveniaKycVerificationRepository kycRepository;
    @Mock
    private BeneficiaryRepository beneficiaryRepository;
    @Mock
    private BaseTransactionRepository transactionRepository;
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Test
    void run_unseededDatabase_createsLinkedDevelopmentDataWithEncodedPasswords() throws Exception {
        // Arrange
        String password = UUID.randomUUID() + "!";
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(companyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CompanyEntity company = invocation.getArgument(0);
            company.setId(companyId);
            return company;
        });
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });
        when(beneficiaryRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            BeneficiaryEntity beneficiary = invocation.getArgument(0);
            beneficiary.setId(UUID.randomUUID());
            return beneficiary;
        });

        // Act
        seeder(password).run();

        // Assert
        verify(userRepository).existsByEmail("dev.user@example.test");
        ArgumentCaptor<CompanyEntity> companyCaptor = ArgumentCaptor.forClass(CompanyEntity.class);
        verify(companyRepository).saveAndFlush(companyCaptor.capture());
        CompanyEntity company = companyCaptor.getValue();
        assertThat(company.getKybStatus()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(company.getAmlStatus()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(company.getAvailableBalanceBrl()).isEqualByComparingTo("25000.00");
        assertThat(company.getLegalName()).isNotBlank();
        assertThat(company.getCnpj()).matches("\\d{14}");
        assertThat(company.getCreatedAt()).isNotNull();
        assertThat(company.getUpdatedAt()).isNotNull();

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity user = userCaptor.getValue();
        assertThat(user.getCompanyId()).isEqualTo(companyId);
        assertThat(user.getEmail()).isEqualTo("dev.user@example.test");
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordSalt()).startsWith("$2").hasSize(29);
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();

        ArgumentCaptor<AdministratorEntity> adminCaptor = ArgumentCaptor.forClass(AdministratorEntity.class);
        verify(administratorRepository).save(adminCaptor.capture());
        AdministratorEntity admin = adminCaptor.getValue();
        assertThat(admin.getEmail()).isEqualTo("dev.admin@example.test");
        assertThat(passwordEncoder.matches(password, admin.getPasswordHash())).isTrue();
        assertThat(admin.getPasswordHash()).isNotEqualTo(user.getPasswordHash());
        assertThat(admin.getPasswordSalt()).startsWith("$2").hasSize(29);
        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getUpdatedAt()).isNotNull();
        verify(passwordEncoder, times(2)).encode(password);

        ArgumentCaptor<AveniaKycVerificationEntity> kycCaptor = ArgumentCaptor.forClass(AveniaKycVerificationEntity.class);
        verify(kycRepository).save(kycCaptor.capture());
        AveniaKycVerificationEntity kyc = kycCaptor.getValue();
        assertThat(kyc.getUserId()).isEqualTo(userId);
        assertThat(kyc.getStatus()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(kyc.getResponsePayload()).isEqualTo("{}");
        assertThat(kyc.getAveniaProcessId()).isNull();
        assertThat(kyc.getAveniaSubAccountId()).isNull();
        assertThat(kyc.getDocumentId()).isNull();
        assertThat(kyc.getLivenessId()).isNull();
        assertThat(kyc.getCreatedAt()).isNotNull();
        assertThat(kyc.getUpdatedAt()).isNotNull();

        ArgumentCaptor<BeneficiaryEntity> beneficiaryCaptor = ArgumentCaptor.forClass(BeneficiaryEntity.class);
        verify(beneficiaryRepository, times(2)).saveAndFlush(beneficiaryCaptor.capture());
        List<BeneficiaryEntity> beneficiaries = beneficiaryCaptor.getAllValues();
        assertThat(beneficiaries).extracting(BeneficiaryEntity::getReceivingMethod)
                .containsExactlyInAnyOrder(ReceivingMethod.BANK_ACCOUNT, ReceivingMethod.CRYPTO_WALLET);
        assertThat(beneficiaries).allSatisfy(beneficiary -> {
            assertThat(beneficiary.getCompany()).isSameAs(company);
            assertThat(beneficiary.getNickname()).isNotBlank();
            assertThat(beneficiary.getAveniaId()).isNull();
            assertThat(beneficiary.getAveniaWalletId()).isNull();
            assertThat(beneficiary.getCreatedAt()).isNotNull();
            assertThat(beneficiary.getUpdatedAt()).isNotNull();
        });

        ArgumentCaptor<BaseTransactionEntity> transactionCaptor = ArgumentCaptor.forClass(BaseTransactionEntity.class);
        verify(transactionRepository, times(3)).save(transactionCaptor.capture());
        List<BaseTransactionEntity> transactions = transactionCaptor.getAllValues();
        assertThat(transactions).allSatisfy(transaction -> {
            assertThat(transaction.getCompanyId()).isEqualTo(companyId);
            assertThat(transaction.getCreatorUserId()).isEqualTo(userId);
            assertThat(transaction.getForeignCurrency()).hasSize(3);
            assertThat(transaction.getForeignAmount()).isPositive();
            assertThat(transaction.getExchangeRate()).isPositive();
            assertThat(transaction.getSettlementAmountBrl()).isPositive();
            assertThat(transaction.getAveniaTicketId()).isNull();
            assertThat(transaction.getCreatedAt()).isNotNull();
            assertThat(transaction.getUpdatedAt()).isNotNull();
        });
        List<ImportTransactionEntity> payments = transactions.stream()
                .filter(ImportTransactionEntity.class::isInstance)
                .map(ImportTransactionEntity.class::cast)
                .toList();
        assertThat(payments).hasSize(2);
        assertThat(payments).extracting(BaseTransactionEntity::getStatus)
                .containsExactlyInAnyOrder(TransactionStatus.SETTLED, TransactionStatus.PROCESSING);
        assertThat(payments).allSatisfy(payment -> {
            BeneficiaryEntity beneficiary = beneficiaries.stream()
                    .filter(candidate -> candidate.getId().equals(payment.getBeneficiaryId()))
                    .findFirst().orElseThrow();
            if (payment.getStatus() == TransactionStatus.SETTLED) {
                assertThat(beneficiary.getReceivingMethod()).isEqualTo(ReceivingMethod.BANK_ACCOUNT);
                assertThat(payment.getTransferMethod()).isEqualTo(TransferMethod.TED);
                assertThat(payment.getSettledAt()).isNotNull();
            } else {
                assertThat(beneficiary.getReceivingMethod()).isEqualTo(ReceivingMethod.CRYPTO_WALLET);
                assertThat(payment.getTransferMethod()).isEqualTo(TransferMethod.BLOCKCHAIN);
                assertThat(payment.getSettledAt()).isNull();
            }
        });
        List<ExportTransactionEntity> receipts = transactions.stream()
                .filter(ExportTransactionEntity.class::isInstance)
                .map(ExportTransactionEntity.class::cast)
                .toList();
        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.getStatus()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);
            assertThat(receipt.getExternalBillingCode()).isNotBlank();
            assertThat(receipt.getExternalPayerName()).isNotBlank();
            assertThat(receipt.getDueDate()).isNotNull();
            assertThat(receipt.getReasonDescription()).isNotBlank();
        });
        verifyNoMoreInteractions(companyRepository, userRepository);
    }

    @Test
    void run_existingSeed_skipsWritesWithoutRequiringPassword() throws Exception {
        // Arrange
        when(userRepository.existsByEmail("dev.user@example.test")).thenReturn(true);

        // Act
        seeder("").run();

        // Assert
        verify(userRepository).existsByEmail("dev.user@example.test");
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(companyRepository, administratorRepository, kycRepository,
                beneficiaryRepository, transactionRepository, passwordEncoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void run_missingPassword_failsBeforeWriting(String password) {
        // Act / Assert
        assertThatThrownBy(() -> seeder(password).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV_SEED_PASSWORD");
        verify(userRepository).existsByEmail("dev.user@example.test");
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(companyRepository, administratorRepository, kycRepository,
                beneficiaryRepository, transactionRepository, passwordEncoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test", "prod"})
    void seeder_withoutDevelopmentProfile_isNotRegistered(String profile) {
        contextRunner().withInitializer(context -> {
            if (!profile.isEmpty()) {
                context.getEnvironment().setActiveProfiles(profile);
            }
        }).run(context -> assertThat(context).doesNotHaveBean(DevelopmentDataSeeder.class));
    }

    @Test
    void seeder_withDevelopmentProfile_isRegistered() {
        contextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasSingleBean(DevelopmentDataSeeder.class));
    }

    private DevelopmentDataSeeder seeder(String password) {
        return new DevelopmentDataSeeder(companyRepository, userRepository, administratorRepository,
                kycRepository, beneficiaryRepository, transactionRepository, passwordEncoder, password);
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DevelopmentDataSeeder.class)
                .withBean(CompanyRepository.class, () -> companyRepository)
                .withBean(UserRepository.class, () -> userRepository)
                .withBean(AdministratorRepository.class, () -> administratorRepository)
                .withBean(AveniaKycVerificationRepository.class, () -> kycRepository)
                .withBean(BeneficiaryRepository.class, () -> beneficiaryRepository)
                .withBean(BaseTransactionRepository.class, () -> transactionRepository)
                .withBean(PasswordEncoder.class, () -> passwordEncoder);
    }
}
