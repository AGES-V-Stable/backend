package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.dto.transfer.TransferResponse;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ExportTransactionEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.NotFoundException;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
    private BaseTransactionRepository baseTransactionRepository;
    @Mock
    private ImportTransactionRepository importTransactionRepository;
    @Mock
    private ExportTransactionRepository exportTransactionRepository;
    @Mock
    private BeneficiaryRepository beneficiaryRepository;
    @Mock
    private CompanyRepository companyRepository;

    private TransferService service;

    private final UUID companyId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 12);

    @BeforeEach
    void setUp() {
        service = new TransferService(baseTransactionRepository, importTransactionRepository,
                exportTransactionRepository, beneficiaryRepository, companyRepository);
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
                .exchangeRate(new BigDecimal("5.123400"))
                .serviceFeeBrl(new BigDecimal("12.50"))
                .createdAt(OffsetDateTime.now())
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
                .thenReturn(new PageImpl<>(List.of(transaction(paymentId), transaction(receiptId)), pageable, 2));
        when(importTransactionRepository.findAllById(anyIterable())).thenReturn(List.of(
                ImportTransactionEntity.builder().id(paymentId).beneficiaryId(beneficiaryId)
                        .transferMethod(TransferMethod.PIX).build()));
        when(exportTransactionRepository.findAllById(anyIterable())).thenReturn(List.of(
                ExportTransactionEntity.builder().id(receiptId).externalPayerName("Acme Inc").build()));
        when(beneficiaryRepository.findAllById(anyIterable())).thenReturn(List.of(
                BeneficiaryEntity.builder().id(beneficiaryId).nickname("atlas").accountHolderName("Atlas LLC").build()));
        when(companyRepository.findAllById(anyIterable())).thenReturn(List.of(company()));

        Page<TransferResponse> page = service.findTransfers(noFilters(), pageable);

        assertThat(page.getTotalElements()).isEqualTo(2);
        TransferResponse payment = page.getContent().getFirst();
        assertThat(payment.getId()).isEqualTo(paymentId);
        assertThat(payment.getBeneficiaryId()).isEqualTo(beneficiaryId);
        assertThat(payment.getDirection()).isEqualTo(TransferDirection.PAYMENT);
        assertThat(payment.getCounterpartyName()).isEqualTo("Atlas LLC");
        assertThat(payment.getTransferMethod()).isEqualTo(TransferMethod.PIX);
        assertThat(payment.getCompanyName()).isEqualTo("Empresa Ltda");
        assertThat(payment.getForeignAmount()).isEqualByComparingTo("1500.25");

        TransferResponse receipt = page.getContent().get(1);
        assertThat(receipt.getId()).isEqualTo(receiptId);
        assertThat(receipt.getDirection()).isEqualTo(TransferDirection.RECEIPT);
        assertThat(receipt.getCounterpartyName()).isEqualTo("Acme Inc");
        assertThat(receipt.getBeneficiaryId()).isNull();
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
        TransferFilter filter = new TransferFilter(null, null, null, null, null,
                new BigDecimal("-1"), null, null, null);

        assertThatThrownBy(() -> service.findTransfers(filter, pageable))
                .isInstanceOf(IllegalArgumentException.class);
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
}
