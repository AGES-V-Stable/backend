package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.TransferDetailsException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferDetailsServiceTest {

    @Mock
    private BaseTransactionRepository transactionRepository;

    @Mock
    private AveniaKycVerificationRepository kycRepository;

    @Mock
    private TransferReceiptProvider pdfGenerator;

    @InjectMocks
    private TransferDetailsService service;

    private final UUID companyId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();
    }

    private void verifiedUser() {
        when(kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(
                AveniaKycVerificationEntity.builder().userId(user.getId()).status(ComplianceStatus.APPROVED).build()));
    }

    private ImportTransactionEntity payment(TransactionStatus status) {
        BeneficiaryEntity beneficiary = BeneficiaryEntity.builder()
                .accountHolderName("Atlas Imports LLC").nickname("Atlas").build();
        return ImportTransactionEntity.builder()
                .id(transferId)
                .companyId(companyId)
                .status(status)
                .foreignCurrency("USD")
                .foreignAmount(new BigDecimal("23062.73"))
                .settlementAmountBrl(new BigDecimal("125562.50"))
                .serviceFeeBrl(new BigDecimal("562.50"))
                .exchangeRate(new BigDecimal("5.420000"))
                .createdAt(OffsetDateTime.of(2026, 8, 24, 14, 32, 0, 0, ZoneOffset.ofHours(-3)))
                .beneficiary(beneficiary)
                .transferMethod(TransferMethod.ACCOUNT_BALANCE)
                .build();
    }

    @Test
    void getDetails_payment_mapsRecordedValuesAsDecimalStrings() {
        verifiedUser();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.id()).isEqualTo(transferId);
        assertThat(details.type()).isEqualTo(Type.PAGAMENTO);
        assertThat(details.status()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(details.date()).isEqualTo(OffsetDateTime.of(2026, 8, 24, 14, 32, 0, 0, ZoneOffset.ofHours(-3)));
        assertThat(details.counterpartyName()).isEqualTo("Atlas Imports LLC");
        assertThat(details.counterpartyDetails()).isEqualTo("Atlas");
        assertThat(details.fundingSource()).isEqualTo("ACCOUNT_BALANCE");
        assertThat(details.source().amount()).isEqualTo("125000.00");
        assertThat(details.source().currency()).isEqualTo("BRL");
        assertThat(details.destination().amount()).isEqualTo("23062.73");
        assertThat(details.destination().currency()).isEqualTo("USD");
        assertThat(details.exchangeRate().fromCurrency()).isEqualTo("USD");
        assertThat(details.exchangeRate().toCurrency()).isEqualTo("BRL");
        assertThat(details.exchangeRate().rate()).isEqualTo("5.42");
        assertThat(details.costs().serviceFee().amount()).isEqualTo("562.50");
        assertThat(details.costs().serviceFee().currency()).isEqualTo("BRL");
        assertThat(details.costs().serviceFee().percentage()).isEqualTo("0.45");
        assertThat(details.receiptAvailable()).isTrue();
    }

    @Test
    void getDetails_withoutMarketReference_leavesMarketCostAndSavingsNull() {
        verifiedUser();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.costs().estimatedMarketCost()).isNull();
        assertThat(details.costs().spreadPercentage()).isNull();
        assertThat(details.estimatedSavings()).isNull();
    }

    @Test
    void getDetails_receipt_invertsSourceAndDestination() {
        verifiedUser();
        ExportTransactionEntity receipt = ExportTransactionEntity.builder()
                .id(transferId)
                .companyId(companyId)
                .status(TransactionStatus.AWAITING_PAYMENT)
                .foreignCurrency("USD")
                .foreignAmount(new BigDecimal("1000.00"))
                .settlementAmountBrl(new BigDecimal("5022.50"))
                .serviceFeeBrl(new BigDecimal("22.50"))
                .exchangeRate(new BigDecimal("5.00"))
                .externalPayerName("Demo Overseas Customer")
                .externalPayerEmail("customer@example.test")
                .build();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(receipt));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.type()).isEqualTo(Type.RECEBIMENTO);
        assertThat(details.counterpartyName()).isEqualTo("Demo Overseas Customer");
        assertThat(details.counterpartyDetails()).isEqualTo("customer@example.test");
        assertThat(details.fundingSource()).isNull();
        assertThat(details.source().amount()).isEqualTo("1000.00");
        assertThat(details.source().currency()).isEqualTo("USD");
        assertThat(details.destination().amount()).isEqualTo("5000.00");
        assertThat(details.destination().currency()).isEqualTo("BRL");
        assertThat(details.exchangeRate().rate()).isEqualTo("5.00");
        assertThat(details.receiptAvailable()).isFalse();
    }

    @Test
    void getDetails_unrecordedValues_stayNullInsteadOfZero() {
        verifiedUser();
        BaseTransactionEntity bare = ImportTransactionEntity.builder()
                .id(transferId)
                .companyId(companyId)
                .status(TransactionStatus.PROCESSING)
                .foreignCurrency("USD")
                .foreignAmount(new BigDecimal("100.00"))
                .build();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(bare));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.source().amount()).isNull();
        assertThat(details.destination().amount()).isEqualTo("100.00");
        assertThat(details.exchangeRate()).isNull();
        assertThat(details.costs().serviceFee()).isNull();
        assertThat(details.fundingSource()).isNull();
        assertThat(details.counterpartyName()).isNull();
    }

    @Test
    void getDetails_knownZeroFee_isReportedAsZeroNotNull() {
        verifiedUser();
        ImportTransactionEntity free = payment(TransactionStatus.SETTLED);
        free.setServiceFeeBrl(new BigDecimal("0.00"));
        free.setSettlementAmountBrl(new BigDecimal("125000.00"));
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(free));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.costs().serviceFee().amount()).isEqualTo("0.00");
        assertThat(details.costs().serviceFee().percentage()).isEqualTo("0.00");
    }

    @Test
    void getDetails_transactionWithoutPaymentOrReceiptDetail_hasNoType() {
        verifiedUser();
        BaseTransactionEntity base = BaseTransactionEntity.builder()
                .id(transferId).companyId(companyId).status(TransactionStatus.PROCESSING)
                .foreignCurrency("USD").foreignAmount(new BigDecimal("1.00")).build();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(base));

        assertThat(service.getDetails(transferId, user).type()).isNull();
    }

    @Test
    void getDetails_userWithoutCompany_isRefusedBeforeAnyLookup() {
        UserEntity noCompany = UserEntity.builder().id(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.getDetails(transferId, noCompany))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COMPANY_ACCESS_REQUIRED"));
        verifyNoInteractions(transactionRepository, kycRepository);
    }

    @Test
    void getDetails_withoutPrincipal_isRefusedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.getDetails(transferId, null))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COMPANY_ACCESS_REQUIRED"));
        verifyNoInteractions(transactionRepository, kycRepository);
    }

    @Test
    void getDetails_userWithoutApprovedKyc_isNotVerified() {
        when(kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(
                AveniaKycVerificationEntity.builder().userId(user.getId()).status(ComplianceStatus.PENDING).build()));

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_VERIFIED"));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void getDetails_userWithoutKycRecord_isNotVerified() {
        when(kycRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_VERIFIED"));
    }

    @Test
    void getDetails_transferOfAnotherCompany_looksLikeNotFound() {
        verifiedUser();
        ImportTransactionEntity foreign = payment(TransactionStatus.SETTLED);
        foreign.setCompanyId(UUID.randomUUID());
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getDetails_unknownTransfer_isNotFound() {
        verifiedUser();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getReceipt_settledTransfer_returnsPdfFromGenerator() {
        verifiedUser();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));
        byte[] pdf = {'%', 'P', 'D', 'F'};
        when(pdfGenerator.generate(any(TransferDetailsResponse.class))).thenReturn(pdf);

        TransferDetailsService.Receipt receipt = service.getReceipt(transferId, user);

        assertThat(receipt.filename()).isEqualTo("comprovante-transferencia.pdf");
        assertThat(receipt.content()).isEqualTo(pdf);
    }

    @Test
    void getReceipt_transferNotSettled_isUnavailableAndGeneratesNothing() {
        verifiedUser();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.PROCESSING)));

        assertThatThrownBy(() -> service.getReceipt(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RECEIPT_UNAVAILABLE"));
        verify(pdfGenerator, never()).generate(any());
    }

    @Test
    void getReceipt_transferOfAnotherCompany_isNotFound() {
        verifiedUser();
        ImportTransactionEntity foreign = payment(TransactionStatus.SETTLED);
        foreign.setCompanyId(UUID.randomUUID());
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getReceipt(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
        verify(pdfGenerator, never()).generate(any());
    }
}
