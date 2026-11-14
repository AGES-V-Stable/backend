package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.dto.transfer.TransferResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaAppliedFee;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import ages.vstable.backend.logging.LogCapture;
import ages.vstable.backend.logging.OperationalLog;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ExportTransactionRepository;
import ages.vstable.backend.repository.ImportTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

        @Mock
        private AveniaTransferService aveniaTransferService;
        @Mock
        private AveniaKycVerificationRepository aveniaKycVerificationRepository;
        @Mock
        private AveniaSubAccountProvisioningService subAccountProvisioningService;
        @Mock
        private BaseTransactionRepository baseTransactionRepository;
        @Mock
        private ImportTransactionRepository importTransactionRepository;
        @Mock
        private ExportTransactionRepository exportTransactionRepository;
        @Mock
        private BeneficiaryRepository beneficiaryRepository;
        @Mock
        private CompanyRepository companyRepository;
        @Mock
        private TwoFactorAuthService twoFactorAuthService;

        @Mock
        private CompanyFinancialAccountService companyFinancialAccountService;

        private TransferService service;

        private final UUID companyId = UUID.randomUUID();
        private final Pageable pageable = PageRequest.of(0, 12);
        private final OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-24T14:00:00Z");
        private final OffsetDateTime settledAt = OffsetDateTime.parse("2026-08-24T15:00:00Z");

        @BeforeEach
        void setUp() {
                service = new TransferService(aveniaTransferService, beneficiaryRepository,
                                companyFinancialAccountService, baseTransactionRepository,
                                importTransactionRepository, exportTransactionRepository,
                                companyRepository, twoFactorAuthService);
        }

        private static TransferFilter noFilters() {
                return new TransferFilter(null, null, null, null, null, null, null, null, null);
        }

        private BaseTransactionEntity transaction(UUID id) {
                return BaseTransactionEntity.builder()
                                .id(id)
                                .companyId(companyId)
                                .status(TransactionStatus.SETTLED)
                                .foreignCurrency("USD")
                                .foreignAmount(new BigDecimal("1500.25"))
                                .settlementAmountBrl(new BigDecimal("7700.00"))
                                .exchangeRate(new BigDecimal("5.123400"))
                                .serviceFeeBrl(new BigDecimal("12.50"))
                                .createdAt(createdAt)
                                .settledAt(settledAt)
                                .build();
        }

        private CompanyEntity company() {
                CompanyEntity company = new CompanyEntity();
                company.setId(companyId);
                company.setLegalName("Empresa Ltda");
                return company;
        }

        @Test
        @SuppressWarnings("unchecked")
        void findTransfers_mapsPaymentsAndReceiptsWithCounterpartyAndCompany() {
                UUID paymentId = UUID.randomUUID();
                UUID receiptId = UUID.randomUUID();
                UUID beneficiaryId = UUID.randomUUID();

                when(baseTransactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                                .thenReturn(new PageImpl<>(List.of(transaction(paymentId), transaction(receiptId)),
                                                pageable, 2));
                when(importTransactionRepository.findAllById(anyIterable())).thenReturn(List.of(
                                ImportTransactionEntity.builder().id(paymentId).beneficiaryId(beneficiaryId)
                                                .transferMethod(TransferMethod.PIX).build()));
                when(exportTransactionRepository.findAllById(anyIterable())).thenReturn(List.of(
                                ExportTransactionEntity.builder().id(receiptId).externalPayerName("Acme Inc").build()));
                when(beneficiaryRepository.findAllById(anyIterable())).thenReturn(List.of(
                                BeneficiaryEntity.builder().id(beneficiaryId).nickname("atlas")
                                                .accountHolderName("Atlas LLC").build()));
                when(companyRepository.findAllById(anyIterable())).thenReturn(List.of(company()));

                Page<TransferResponse> page = service.findTransfers(noFilters(), pageable);

                assertThat(page.getTotalElements()).isEqualTo(2);
                TransferResponse payment = page.getContent().getFirst();
                assertThat(payment.getId()).isEqualTo(paymentId);
                assertThat(payment.getCompanyId()).isEqualTo(companyId);
                assertThat(payment.getBeneficiaryId()).isEqualTo(beneficiaryId);
                assertThat(payment.getDirection()).isEqualTo(TransferDirection.PAYMENT);
                assertThat(payment.getCounterpartyName()).isEqualTo("Atlas LLC");
                assertThat(payment.getTransferMethod()).isEqualTo(TransferMethod.PIX);
                assertThat(payment.getCompanyName()).isEqualTo("Empresa Ltda");
                assertThat(payment.getForeignCurrency()).isEqualTo("USD");
                assertThat(payment.getForeignAmount()).isEqualByComparingTo("1500.25");
                assertThat(payment.getSettlementAmountBrl()).isEqualByComparingTo("7700.00");
                assertThat(payment.getServiceFeeBrl()).isEqualByComparingTo("12.50");
                assertThat(payment.getExchangeRate()).isEqualByComparingTo("5.123400");
                assertThat(payment.getCreatedAt()).isEqualTo(createdAt);
                assertThat(payment.getSettledAt()).isEqualTo(settledAt);

                TransferResponse receipt = page.getContent().get(1);
                assertThat(receipt.getId()).isEqualTo(receiptId);
                assertThat(receipt.getDirection()).isEqualTo(TransferDirection.RECEIPT);
                assertThat(receipt.getCounterpartyName()).isEqualTo("Acme Inc");
                assertThat(receipt.getBeneficiaryId()).isNull();
        }

        @Test
        @SuppressWarnings("unchecked")
        void findTransfers_duplicateEntitiesInById_retainsFirstEntry() {
                UUID paymentId = UUID.randomUUID();
                CompanyEntity first = company();
                first.setTradeName("First Trade Name");
                CompanyEntity second = company();
                second.setTradeName("Second Trade Name");

                when(baseTransactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                                .thenReturn(new PageImpl<>(List.of(transaction(paymentId)), pageable, 1));
                when(importTransactionRepository.findAllById(anyIterable())).thenReturn(List.of());
                when(exportTransactionRepository.findAllById(anyIterable())).thenReturn(List.of());
                when(beneficiaryRepository.findAllById(anyIterable())).thenReturn(List.of());
                when(companyRepository.findAllById(anyIterable())).thenReturn(List.of(first, second));

                Page<TransferResponse> page = service.findTransfers(noFilters(), pageable);

                assertThat(page.getContent().getFirst().getCompanyName()).isEqualTo("First Trade Name");
        }

        @Test
        @SuppressWarnings("unchecked")
        void findTransfers_emptyPage_doesNotLoadRelatedRows() {
                when(baseTransactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                                .thenReturn(Page.empty(pageable));

                Page<TransferResponse> page = service.findTransfers(noFilters(), pageable);

                assertThat(page.getContent()).isEmpty();
                verifyNoInteractions(importTransactionRepository, exportTransactionRepository,
                                beneficiaryRepository, companyRepository);
        }

        @Test
        void findTransfers_startDateAfterEndDate_isRejected() {
                TransferFilter filter = new TransferFilter(null, null, null,
                                LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), null, null, null, null);

                assertThatThrownBy(() -> service.findTransfers(filter, pageable))
                                .isInstanceOf(IllegalArgumentException.class);
                verifyNoInteractions(baseTransactionRepository);
        }

        @Test
        void findTransfers_minAmountGreaterThanMax_isRejected() {
                TransferFilter filter = new TransferFilter(null, null, null, null, null,
                                new BigDecimal("100"), new BigDecimal("10"), null, null);

                assertThatThrownBy(() -> service.findTransfers(filter, pageable))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void findTransfers_negativeAmount_isRejected() {
                TransferFilter minNeg = new TransferFilter(null, null, null, null, null,
                                new BigDecimal("-1"), null, null, null);
                TransferFilter maxNeg = new TransferFilter(null, null, null, null, null,
                                null, new BigDecimal("-0.01"), null, null);

                assertThatThrownBy(() -> service.findTransfers(minNeg, pageable))
                                .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> service.findTransfers(maxNeg, pageable))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @SuppressWarnings("unchecked")
        void findTransfers_zeroAndEqualMinMaxAmounts_areAllowed() {
                when(baseTransactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                                .thenReturn(Page.empty(pageable));

                TransferFilter zeroFilter = new TransferFilter(null, null, null,
                                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
                                BigDecimal.ZERO, BigDecimal.ZERO, null, null);

                Page<TransferResponse> page = service.findTransfers(zeroFilter, pageable);

                assertThat(page.getContent()).isEmpty();
        }

        @Test
        void findById_unknownTransfer_throwsNotFound() {
                UUID id = UUID.randomUUID();
                when(baseTransactionRepository.findById(id)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.findById(id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void findById_returnsMappedTransfer() {
                UUID id = UUID.randomUUID();
                when(baseTransactionRepository.findById(id)).thenReturn(Optional.of(transaction(id)));
                when(importTransactionRepository.findAllById(anyIterable())).thenReturn(List.of());
                when(exportTransactionRepository.findAllById(anyIterable())).thenReturn(List.of());
                when(beneficiaryRepository.findAllById(anyIterable())).thenReturn(List.of());
                when(companyRepository.findAllById(anyIterable())).thenReturn(List.of(company()));

                TransferResponse response = service.findById(id);

                assertThat(response.getId()).isEqualTo(id);
                assertThat(response.getStatus()).isEqualTo(TransactionStatus.SETTLED);
                assertThat(response.getDirection()).isNull();
        }

        @Test
        void create_logsMilestoneOnSuccess() {
                UUID userId = UUID.randomUUID();
                UUID beneficiaryId = UUID.randomUUID();
                BeneficiaryEntity beneficiary = BeneficiaryEntity.builder()
                                .id(beneficiaryId)
                                .company(company())
                                .receivingMethod(ReceivingMethod.CRYPTO_WALLET)
                                .currency("USDC")
                                .blockchainNetwork(BlockchainNetwork.polygon)
                                .aveniaWalletId(UUID.randomUUID())
                                .aveniaSubAccountId("sub-1")
                                .build();
                when(beneficiaryRepository.findByIdAndCompanyId(beneficiaryId, companyId))
                                .thenReturn(Optional.of(beneficiary));
                // Sub-account belongs to the company, not to the currently logged-in member.
                when(companyFinancialAccountService.requireAveniaSubAccountId(companyId))
                                .thenReturn("sub-1");

                AveniaQuoteResponse quoteResponse = new AveniaQuoteResponse(
                                "qt-1", "BRL", "PIX", new BigDecimal("100.00"),
                                "USDC", "POLYGON", new BigDecimal("19.50"),
                                BigDecimal.ZERO, null, false, false, List.of(),
                                new BigDecimal("5.1282"), "BRL-USDC");
                AveniaTicketResponse ticketResponse = new AveniaTicketResponse(
                                UUID.randomUUID(), "000201", OffsetDateTime.now().plusMinutes(10),
                                "PAID", null, null);
                when(aveniaTransferService.createTransfer(any(), any()))
                                .thenReturn(new AveniaTransferResult(quoteResponse, ticketResponse));

                CreateTransferRequest request = new CreateTransferRequest();
                request.setBeneficiaryId(beneficiaryId);
                request.setAmount(new BigDecimal("100.00"));
                request.setAmountType(TransferAmountType.SOURCE);
                request.setPaymentMethod(TransferMethod.PIX);
                request.setTotpCode("123456");

                try (var capture = new LogCapture(OperationalLog.class)) {
                        CreateTransferResponse response = service.create(request, userId, companyId);

                        assertThat(response.status()).isEqualTo(TransactionStatus.SETTLED);
                        assertThat(capture.events()).hasSize(1);
                        Map<String, Object> fields = LogCapture.fields(capture.events().getFirst());
                        assertThat(fields).containsEntry("event", "transfer.creation.completed")
                                        .containsEntry("outcome", "success");
                }
        }

        @Test
        void quote_missingDestinationCurrencyAndBeneficiaryCurrency_throwsUnprocessableEntity() {
                UUID userId = UUID.randomUUID();
                UUID beneficiaryId = UUID.randomUUID();
                BeneficiaryEntity beneficiary = BeneficiaryEntity.builder()
                                .id(beneficiaryId)
                                .company(company())
                                .receivingMethod(ReceivingMethod.CRYPTO_WALLET)
                                .currency(null)
                                .blockchainNetwork(BlockchainNetwork.polygon)
                                .aveniaWalletId(UUID.randomUUID())
                                .aveniaSubAccountId("sub-1")
                                .build();
                when(beneficiaryRepository.findByIdAndCompanyId(beneficiaryId, companyId))
                                .thenReturn(Optional.of(beneficiary));

                TransferQuoteRequest request = new TransferQuoteRequest();
                request.setBeneficiaryId(beneficiaryId);
                request.setPaymentMethod(TransferMethod.PIX);
                request.setAmount(new BigDecimal("100.00"));
                request.setAmountType(TransferAmountType.SOURCE);
                request.setDestinationCurrency(null);

                assertThatThrownBy(() -> service.quote(request, userId, companyId))
                                .isInstanceOf(UnprocessableEntityException.class)
                                .hasMessageContaining("destinationCurrency");
        }

        @Test
        void buildBankAccountTicket_mapsSupportedRailsAndRejectsUnsupported() {
                UUID aveniaId = UUID.randomUUID();

                BeneficiaryEntity pixBeneficiary = BeneficiaryEntity.builder()
                                .aveniaId(aveniaId).paymentRail("pix").build();
                AveniaTicketRequest pixTicket = ReflectionTestUtils.invokeMethod(
                                service, "buildBankAccountTicket", AveniaTicketRequest.builder(), pixBeneficiary, "ref");
                assertThat(pixTicket).isNotNull();
                assertThat(pixTicket.getTicketBrlPixOutput()).isNotNull();

                BeneficiaryEntity achBeneficiary = BeneficiaryEntity.builder()
                                .aveniaId(aveniaId).paymentRail("ach").build();
                AveniaTicketRequest achTicket = ReflectionTestUtils.invokeMethod(
                                service, "buildBankAccountTicket", AveniaTicketRequest.builder(), achBeneficiary, "ref");
                assertThat(achTicket).isNotNull();
                assertThat(achTicket.getTicketUsdOutput()).isNotNull();

                BeneficiaryEntity wireBeneficiary = BeneficiaryEntity.builder()
                                .aveniaId(aveniaId).paymentRail("wire").build();
                AveniaTicketRequest wireTicket = ReflectionTestUtils.invokeMethod(
                                service, "buildBankAccountTicket", AveniaTicketRequest.builder(), wireBeneficiary, "ref");
                assertThat(wireTicket).isNotNull();
                assertThat(wireTicket.getTicketUsdWireOutput()).isNotNull();

                BeneficiaryEntity sepaBeneficiary = BeneficiaryEntity.builder()
                                .aveniaId(aveniaId).paymentRail("sepa").build();
                AveniaTicketRequest sepaTicket = ReflectionTestUtils.invokeMethod(
                                service, "buildBankAccountTicket", AveniaTicketRequest.builder(), sepaBeneficiary, "ref");
                assertThat(sepaTicket).isNotNull();
                assertThat(sepaTicket.getTicketEurSepaOutput()).isNotNull();
        }

        @Test
        void feeCalculation_handlesBoundariesAndValidationDirectly() {
                BigDecimal zeroPct = ReflectionTestUtils.invokeMethod(
                                service, "feePercentage", new BigDecimal("100.00"), BigDecimal.ZERO);
                assertThat(zeroPct).isEqualByComparingTo("0.00");

                BigDecimal hundredPct = ReflectionTestUtils.invokeMethod(
                                service, "feePercentage", new BigDecimal("100.00"), new BigDecimal("100.00"));
                assertThat(hundredPct).isEqualByComparingTo("100.00");

                assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                                service, "feePercentage", new BigDecimal("100.00"), new BigDecimal("100.01")))
                                .isInstanceOf(AveniaIntegrationException.class);

                AveniaQuoteResponse zeroAppliedFeeQuote = new AveniaQuoteResponse(
                                "qt-1", "BRL", "PIX", new BigDecimal("100.00"),
                                "USDC", "POLYGON", new BigDecimal("19.50"),
                                null, null, false, false,
                                List.of(new AveniaAppliedFee("FLAT", null, BigDecimal.ZERO, "BRL")),
                                new BigDecimal("5.12"), "BRL-USDC");
                TransferQuoteResponse mappedZeroFee = ReflectionTestUtils.invokeMethod(
                                service, "toTransferQuoteResponse", zeroAppliedFeeQuote);
                assertThat(mappedZeroFee).isNotNull();
                assertThat(mappedZeroFee.fee().amount()).isEqualByComparingTo("0.00");
                assertThat(mappedZeroFee.fee().percentage()).isEqualByComparingTo("0.00");
                assertThat(mappedZeroFee.fee().currency()).isEqualTo("BRL");

                AveniaQuoteResponse negativeAppliedFeeQuote = new AveniaQuoteResponse(
                                "qt-2", "BRL", "PIX", new BigDecimal("100.00"),
                                "USDC", "POLYGON", new BigDecimal("19.50"),
                                null, null, false, false,
                                List.of(new AveniaAppliedFee("FLAT", null, new BigDecimal("-0.01"), "BRL")),
                                new BigDecimal("5.12"), "BRL-USDC");
                assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                                service, "feeCurrency", negativeAppliedFeeQuote, "BRL"))
                                .isInstanceOf(AveniaIntegrationException.class);

                AveniaQuoteResponse lowercaseMarkupCurrencyQuote = new AveniaQuoteResponse(
                                "qt-3", "BRL", "PIX", new BigDecimal("100.00"),
                                "USDC", "POLYGON", new BigDecimal("19.50"),
                                new BigDecimal("2.50"), "brl", false, false, List.of(),
                                new BigDecimal("5.12"), "BRL-USDC");
                String markupCurrency = ReflectionTestUtils.invokeMethod(
                                service, "feeCurrency", lowercaseMarkupCurrencyQuote, "BRL");
                assertThat(markupCurrency).isEqualTo("brl");

                AveniaQuoteResponse zeroMarkupWithoutCurrencyQuote = new AveniaQuoteResponse(
                                "qt-4", "BRL", "PIX", new BigDecimal("100.00"),
                                "USDC", "POLYGON", new BigDecimal("19.50"),
                                BigDecimal.ZERO, null, false, false, List.of(),
                                new BigDecimal("5.12"), "BRL-USDC");
                String fallbackCurrency = ReflectionTestUtils.invokeMethod(
                                service, "feeCurrency", zeroMarkupWithoutCurrencyQuote, "BRL");
                assertThat(fallbackCurrency).isEqualTo("BRL");

                AveniaQuoteResponse negativeMarkupQuote = new AveniaQuoteResponse(
                                "qt-5", "BRL", "PIX", new BigDecimal("100.00"),
                                "USDC", "POLYGON", new BigDecimal("19.50"),
                                new BigDecimal("-1.00"), "BRL", false, false, List.of(),
                                new BigDecimal("5.12"), "BRL-USDC");
                assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                                service, "totalFeeAmount", negativeMarkupQuote, "BRL"))
                                .isInstanceOf(AveniaIntegrationException.class);
        }
}
