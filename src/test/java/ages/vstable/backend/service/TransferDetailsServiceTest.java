package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.MembershipStatus;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.TransferDetailsException;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.support.TestCompanyUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.costs().estimatedMarketCost()).isNull();
        assertThat(details.costs().spreadPercentage()).isNull();
        assertThat(details.estimatedSavings()).isNull();
    }

    @Test
    void getDetails_receipt_invertsSourceAndDestination() {
        ExportTransactionEntity receipt = ExportTransactionEntity.builder()
                .id(transferId)
                .companyId(companyId)
                .status(TransactionStatus.AWAITING_PAYMENT)
                .foreignCurrency("USD")
                .foreignAmount(new BigDecimal("1000.00"))
                .settlementAmountBrl(new BigDecimal("4977.50"))
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
        assertThat(details.destination().amount()).isEqualTo("4977.50");
        assertThat(details.costs().serviceFee().percentage()).isEqualTo("0.45");
        assertThat(details.destination().currency()).isEqualTo("BRL");
        assertThat(details.exchangeRate().rate()).isEqualTo("5.00");
        assertThat(details.receiptAvailable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false, 5100.00, 100.00, 5000.00, 2.00",
            "true, 4900.00, 100.00, 4900.00, 2.00",
            "false, 5000.00, 0.00, 5000.00, 0.00",
            "true, 5000.00, 0.00, 5000.00, 0.00",
            "false, 5000.00, null, 5000.00, null",
            "true, 5000.00, null, 5000.00, null",
            "false, null, 100.00, null, null",
            "true, null, 100.00, null, null"
    }, nullValues = "null")
    void getDetails_feeUsesGrossConversionAndIncomingDestinationKeepsNetSettlement(
            boolean incoming, String settlement, String fee, String expectedBrl, String percentage) {
        BaseTransactionEntity transaction = incoming ? new ExportTransactionEntity() : new ImportTransactionEntity();
        transaction.setId(transferId);
        transaction.setCompanyId(companyId);
        transaction.setStatus(TransactionStatus.SETTLED);
        transaction.setSettlementAmountBrl(settlement == null ? null : new BigDecimal(settlement));
        transaction.setServiceFeeBrl(fee == null ? null : new BigDecimal(fee));
        // Deliberately inconsistent: details must use recorded settlement, not recompute it.
        transaction.setForeignAmount(new BigDecimal("999.00"));
        transaction.setForeignCurrency("USD");
        transaction.setExchangeRate(new BigDecimal("7.00"));
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(transaction));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat((incoming ? details.destination() : details.source()).amount()).isEqualTo(expectedBrl);
        if (fee == null) {
            assertThat(details.costs().serviceFee()).isNull();
        } else {
            assertThat(details.costs().serviceFee().amount()).isEqualTo(fee);
            assertThat(details.costs().serviceFee().percentage()).isEqualTo(percentage);
        }
    }

    @Test
    void getDetails_unrecordedValues_stayNullInsteadOfZero() {
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
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void getDetails_withoutPrincipal_isRefusedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.getDetails(transferId, null))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COMPANY_ACCESS_REQUIRED"));
        verifyNoInteractions(transactionRepository);
    }

    @ParameterizedTest
    @CsvSource({"OWNER", "OPERATOR", "VIEWER"})
    void getDetails_activeMemberWithoutIndividualKyc_readsTheCompanyTransfer(CompanyRole role) {
        // Arrange: a transferência é da empresa; nenhum membro precisa de KYC individual para consultá-la.
        UserEntity member = TestCompanyUsers.member(companyId, role);
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));

        // Act
        TransferDetailsResponse details = service.getDetails(transferId, member);

        // Assert
        assertThat(details.id()).isEqualTo(transferId);
        assertThat(details.companyId()).isEqualTo(companyId);
    }

    @Test
    void getReceipt_memberOfAnotherCompany_cannotDownloadWithoutKycGate() {
        // Arrange: sem o gate de KYC, o isolamento continua vindo da empresa do vínculo.
        UserEntity outsider = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER);
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));

        // Act / Assert
        assertThatThrownBy(() -> service.getReceipt(transferId, outsider))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
        verify(pdfGenerator, never()).generate(any());
    }

    @Test
    void getDetails_transferOfAnotherCompany_looksLikeNotFound() {
        ImportTransactionEntity foreign = payment(TransactionStatus.SETTLED);
        foreign.setCompanyId(UUID.randomUUID());
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getDetails_usesTheCompanyOfTheMembershipInsteadOfTheAccount() {
        UUID membershipCompany = UUID.randomUUID();
        TestCompanyUsers.withMembership(user, membershipCompany, CompanyRole.VIEWER, MembershipStatus.ACTIVE);
        ImportTransactionEntity accountCompanyTransfer = payment(TransactionStatus.SETTLED);
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(accountCompanyTransfer));

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getDetails_removedMembership_isRefusedBeforeAnyLookup() {
        UserEntity removed = TestCompanyUsers.removedMember(companyId, CompanyRole.OPERATOR);

        assertThatThrownBy(() -> service.getDetails(transferId, removed))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COMPANY_ACCESS_REQUIRED"));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void getDetails_unknownTransfer_isNotFound() {
        when(transactionRepository.findById(transferId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetails(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getReceipt_settledTransfer_returnsPdfFromGenerator() {
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.SETTLED)));
        byte[] pdf = {'%', 'P', 'D', 'F'};
        when(pdfGenerator.generate(any(TransferDetailsResponse.class))).thenReturn(pdf);

        TransferDetailsService.Receipt receipt = service.getReceipt(transferId, user);

        assertThat(receipt.filename()).isEqualTo("comprovante-transferencia.pdf");
        assertThat(receipt.content()).isEqualTo(pdf);
    }

    @Test
    void getReceipt_transferNotSettled_isUnavailableAndGeneratesNothing() {
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(payment(TransactionStatus.PROCESSING)));

        assertThatThrownBy(() -> service.getReceipt(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RECEIPT_UNAVAILABLE"));
        verify(pdfGenerator, never()).generate(any());
    }

    @Test
    void getReceipt_transferOfAnotherCompany_isNotFound() {
        ImportTransactionEntity foreign = payment(TransactionStatus.SETTLED);
        foreign.setCompanyId(UUID.randomUUID());
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getReceipt(transferId, user))
                .isInstanceOfSatisfying(TransferDetailsException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
        verify(pdfGenerator, never()).generate(any());
    }

    @Test
    void getDetails_zeroConvertedBrlAndBlankCounterpartyFields_returnsNullPercentageAndNullCounterparty() {
        ExportTransactionEntity receipt = ExportTransactionEntity.builder()
                .id(transferId)
                .companyId(companyId)
                .status(TransactionStatus.SETTLED)
                .foreignCurrency("USD")
                .foreignAmount(new BigDecimal("100.00"))
                .settlementAmountBrl(new BigDecimal("-50.00"))
                .serviceFeeBrl(new BigDecimal("50.00"))
                .exchangeRate(new BigDecimal("5.4"))
                .externalPayerName("   ")
                .externalPayerEmail("")
                .build();
        when(transactionRepository.findById(transferId)).thenReturn(Optional.of(receipt));

        TransferDetailsResponse details = service.getDetails(transferId, user);

        assertThat(details.counterpartyName()).isNull();
        assertThat(details.counterpartyDetails()).isNull();
        assertThat(details.costs().serviceFee().amount()).isEqualTo("50.00");
        assertThat(details.costs().serviceFee().percentage()).isNull();
        assertThat(details.exchangeRate().rate()).isEqualTo("5.40");
    }

    @Test
    void receiptRecord_implementsValueBasedEqualsHashCodeAndToString() {
        byte[] contentA = {1, 2, 3};
        byte[] contentA2 = {1, 2, 3};
        byte[] contentB = {1, 2, 4};

        TransferDetailsService.Receipt receipt1 = new TransferDetailsService.Receipt("file-a.pdf", contentA);
        TransferDetailsService.Receipt receipt2 = new TransferDetailsService.Receipt("file-a.pdf", contentA2);
        TransferDetailsService.Receipt differentName = new TransferDetailsService.Receipt("file-b.pdf", contentA);
        TransferDetailsService.Receipt differentContent = new TransferDetailsService.Receipt("file-a.pdf", contentB);
        TransferDetailsService.Receipt nullContent = new TransferDetailsService.Receipt("file-a.pdf", null);

        assertThat(receipt1)
                .isEqualTo(receipt1)
                .isEqualTo(receipt2)
                .isNotEqualTo(differentName)
                .isNotEqualTo(differentContent)
                .isNotEqualTo(nullContent)
                .isNotEqualTo("not-a-receipt")
                .isNotEqualTo(null);

        int expectedHash = 31 * java.util.Objects.hashCode("file-a.pdf") + java.util.Arrays.hashCode(contentA);
        assertThat(receipt1.hashCode()).isEqualTo(expectedHash).isEqualTo(receipt2.hashCode()).isNotZero();
        assertThat(receipt1.toString()).isEqualTo("Receipt[filename=file-a.pdf, content=3 bytes]");
        assertThat(nullContent.toString()).isEqualTo("Receipt[filename=file-a.pdf, content=0 bytes]");
    }
}
