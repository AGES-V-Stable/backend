package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.BaseTransactionEntity;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ImportTransactionEntity;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferAmountType;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.avenia.AveniaTransferService;
import ages.vstable.backend.external.avenia.dto.AveniaAppliedFee;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTransferResult;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.BaseTransactionRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.ImportTransactionRepository;
import ages.vstable.backend.repository.ExportTransactionRepository;
import ages.vstable.backend.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferCreationServiceTest {

    @Mock
    private AveniaTransferService aveniaTransferService;

    @Mock
    private BeneficiaryRepository beneficiaryRepository;

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
    private CompanyRepository companyRepository;

    private TransferService transferService;

    private static final UUID CURRENT_USER_ID = UUID.randomUUID();
    private static final UUID CURRENT_COMPANY_ID = UUID.randomUUID();
    private static final String SUB_ACCOUNT_ID = "sub-account-123";

    private void stubSubAccount() {
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(UUID.randomUUID())
                .userId(CURRENT_USER_ID)
                .aveniaSubAccountId(SUB_ACCOUNT_ID)
                .build();
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
    }

    private TransferQuoteRequest requestWith(BigDecimal amount, TransferAmountType type,
                                              String source, String destination) {
        TransferQuoteRequest request = new TransferQuoteRequest();
        request.setAmount(amount);
        request.setAmountType(type);
        request.setSourceCurrency(source);
        request.setDestinationCurrency(destination);
        return request;
    }

    private AveniaQuoteResponse aveniaResponse() {
        return new AveniaQuoteResponse(
                "quote-token-should-never-leak",
                "BRL", "INTERNAL", new BigDecimal("125000.00"),
                "USD", "INTERNAL", new BigDecimal("24235.14"),
                new BigDecimal("562.50"), "BRL",
                false, false,
                List.of(new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("562.50"), "BRL")),
                new BigDecimal("5.16"), "BRLAUSD");
    }

    private void setUpService() {
        transferService = new TransferService(
                aveniaTransferService, beneficiaryRepository,
                aveniaKycVerificationRepository, subAccountProvisioningService,
                baseTransactionRepository, importTransactionRepository, exportTransactionRepository, companyRepository);
    }

    /** Devolve a mesma entity recebida, com id preenchido — como faria um save() real. */
    private void stubTransactionPersistence() {
        when(importTransactionRepository.save(any())).thenAnswer(invocation -> {
            ImportTransactionEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
    }

    private CreateTransferRequest createRequestWith(UUID beneficiaryId, String destinationCurrency) {
        CreateTransferRequest request = new CreateTransferRequest();
        request.setAmount(new BigDecimal("125000.00"));
        request.setAmountType(TransferAmountType.SOURCE);
        request.setSourceCurrency("BRL");
        request.setDestinationCurrency(destinationCurrency);
        request.setPaymentMethod(TransferMethod.PIX);
        request.setBeneficiaryId(beneficiaryId);
        request.setDescription("Pagamento de importação");
        return request;
    }

    private CompanyEntity company() {
        return CompanyEntity.builder().id(CURRENT_COMPANY_ID).legalName("Empresa Teste Ltda").build();
    }

    private BeneficiaryEntity pixBeneficiary() {
        return BeneficiaryEntity.builder()
                .id(UUID.randomUUID())
                .company(company())
                .receivingMethod(ReceivingMethod.PIX_KEY)
                .aveniaId(UUID.randomUUID())
                .aveniaSubAccountId(SUB_ACCOUNT_ID)
                .pixKey("chave@empresa.com")
                .accountHolderName("Fornecedor Ltda")
                .identificationDocument("11222333000181")
                .build();
    }

    private BeneficiaryEntity cryptoBeneficiary() {
        return BeneficiaryEntity.builder()
                .id(UUID.randomUUID())
                .company(company())
                .receivingMethod(ReceivingMethod.CRYPTO_WALLET)
                .aveniaWalletId(UUID.randomUUID())
                .aveniaSubAccountId(SUB_ACCOUNT_ID)
                .blockchainNetwork(BlockchainNetwork.polygon)
                .walletAddress("0xabc123")
                .currency("USDC")
                .build();
    }

    private BeneficiaryEntity bankAccountBeneficiary() {
        return BeneficiaryEntity.builder()
                .id(UUID.randomUUID())
                .company(company())
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .aveniaId(UUID.randomUUID())
                .aveniaSubAccountId(SUB_ACCOUNT_ID)
                .country("Estados Unidos")
                .currency("USD")
                .paymentRail("wire")
                .build();
    }

    @Test
    void quote_valorDeOrigemInformado_montaCotacaoComInputAmountEDevolveBreakdownSemIdentificadoresDaAvenia() {
        setUpService();
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        TransferQuoteResponse response = transferService.quote(
                requestWith(new BigDecimal("125000.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputAmount()).isEqualByComparingTo("125000.00");
        assertThat(captor.getValue().outputAmount()).isNull();
        assertThat(captor.getValue().inputCurrency()).isEqualTo("BRL");
        assertThat(captor.getValue().outputCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().subAccountId()).isEqualTo(SUB_ACCOUNT_ID);

        assertThat(response.source()).isEqualTo(new MoneyAmount(new BigDecimal("125000.00"), "BRL"));
        assertThat(response.destination()).isEqualTo(new MoneyAmount(new BigDecimal("24235.14"), "USD"));
        assertThat(response.fee().amount()).isEqualByComparingTo("562.50");
        assertThat(response.fee().currency()).isEqualTo("BRL");
        assertThat(response.fee().percentage()).isEqualByComparingTo("0.45");
        assertThat(response.total().amount()).isEqualByComparingTo("125000.00");
        assertThat(response.total().currency()).isEqualTo("BRL");
        assertThat(response.exchangeRate().fromCurrency()).isEqualTo("USD");
        assertThat(response.exchangeRate().toCurrency()).isEqualTo("BRL");
        assertThat(response.exchangeRate().rate()).isEqualByComparingTo("5.16");
    }

    @Test
    void quote_valorDeDestinoInformado_montaCotacaoComOutputAmount() {
        setUpService();
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        transferService.quote(
                requestWith(new BigDecimal("24235.14"), TransferAmountType.DESTINATION, "BRL", "USD"), CURRENT_USER_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputAmount()).isNull();
        assertThat(captor.getValue().outputAmount()).isEqualByComparingTo("24235.14");
    }

    @Test
    void quote_semMoedasInformadas_usaBrlComoOrigemPadrao() {
        setUpService();
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, null, "USD"), CURRENT_USER_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputCurrency()).isEqualTo("BRL");
    }

    @Test
    void quote_moedaDeDestinoAusenteENaoDeterminavel_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null), CURRENT_USER_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createQuote(any());
    }

    @Test
    void quote_falhaNaAvenia_propagaAveniaIntegrationExceptionSemCapturar() {
        setUpService();
        stubSubAccount();
        when(aveniaTransferService.createQuote(any()))
                .thenThrow(AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void quote_usuarioSemVerificacaoKyc_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createQuote(any());
    }

    @Test
    void quote_appliedFeesTemPrecedenciaSobreMarkupAmountNoCalculoDoTotal() {
        setUpService();
        stubSubAccount();
        AveniaQuoteResponse response = new AveniaQuoteResponse(
                "token", "BRL", "INTERNAL", new BigDecimal("100.00"),
                "USD", "INTERNAL", new BigDecimal("20.00"),
                new BigDecimal("1.00"), "BRL", false, false,
                List.of(
                        new AveniaAppliedFee("Markup Fee", "desc", new BigDecimal("1.00"), "BRL"),
                        new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("2.00"), "BRL")),
                new BigDecimal("5.00"), "BRLUSD");
        when(aveniaTransferService.createQuote(any())).thenReturn(response);

        TransferQuoteResponse result = transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID);

        assertThat(result.fee().amount()).isEqualByComparingTo("3.00");
        assertThat(result.fee().percentage()).isEqualByComparingTo("3.00");
        assertThat(result.total().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void quote_taxaEmMoedaDiferenteDaOrigem_rejeitaRespostaSemSomarMoedas() {
        setUpService();
        stubSubAccount();
        AveniaQuoteResponse response = new AveniaQuoteResponse(
                "token", "BRL", "INTERNAL", new BigDecimal("100.00"),
                "USD", "INTERNAL", new BigDecimal("20.00"),
                BigDecimal.ZERO, "BRL", false, false,
                List.of(new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("2.00"), "USD")),
                new BigDecimal("5.00"), "BRLUSD");
        when(aveniaTransferService.createQuote(any())).thenReturn(response);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("different from the source currency");
    }

    @Test
    void quote_appliedFeeIncompleta_rejeitaResposta() {
        setUpService();
        stubSubAccount();
        AveniaQuoteResponse response = new AveniaQuoteResponse(
                "token", "BRL", "INTERNAL", new BigDecimal("100.00"),
                "USD", "INTERNAL", new BigDecimal("20.00"),
                BigDecimal.ZERO, "BRL", false, false,
                List.of(new AveniaAppliedFee("Out Fee", "desc", null, "BRL")),
                new BigDecimal("5.00"), "BRLUSD");
        when(aveniaTransferService.createQuote(any())).thenReturn(response);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("incomplete financial data");
    }

    @Test
    void quote_semAppliedFees_usaMarkupSomenteQuandoEstaNaMoedaDeOrigem() {
        setUpService();
        stubSubAccount();
        AveniaQuoteResponse response = new AveniaQuoteResponse(
                "token", "BRL", "INTERNAL", new BigDecimal("100.00"),
                "USD", "INTERNAL", new BigDecimal("20.00"),
                new BigDecimal("1.50"), "BRL", false, false,
                List.of(), new BigDecimal("5.00"), "BRLUSD");
        when(aveniaTransferService.createQuote(any())).thenReturn(response);

        TransferQuoteResponse result = transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USD"), CURRENT_USER_ID);

        assertThat(result.fee().amount()).isEqualByComparingTo("1.50");
        assertThat(result.fee().percentage()).isEqualByComparingTo("1.50");
        assertThat(result.total().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void create_beneficiarioPix_montaTicketBrlPixOutputComDadosDoBeneficiarioEPersisteTransacao() {
        setUpService();
        stubSubAccount();
        stubTransactionPersistence();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        UUID ticketId = UUID.randomUUID();
        AveniaTransferResult result = new AveniaTransferResult(
                aveniaResponse(), new AveniaTicketResponse(ticketId, null, null, "PROCESSING", null, null));
        when(aveniaTransferService.createTransfer(any(), any())).thenReturn(result);

        CreateTransferResponse response = transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(response.status()).isEqualTo(TransactionStatus.PROCESSING);

        ArgumentCaptor<AveniaQuoteRequest> quoteCaptor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        ArgumentCaptor<AveniaTicketRequest> ticketCaptor = ArgumentCaptor.forClass(AveniaTicketRequest.class);
        verify(aveniaTransferService).createTransfer(quoteCaptor.capture(), ticketCaptor.capture());

        // PIX sem moeda de destino informada nem cadastrada no beneficiário: assume BRL.
        assertThat(quoteCaptor.getValue().outputCurrency()).isEqualTo("BRL");
        assertThat(quoteCaptor.getValue().inputPaymentMethod()).isEqualTo("PIX");
        assertThat(quoteCaptor.getValue().outputPaymentMethod()).isEqualTo("PIX");
        assertThat(ticketCaptor.getValue().getTicketBrlPixInput()).isNotNull();
        assertThat(ticketCaptor.getValue().getTicketBrlPixOutput().beneficiaryBrlBankAccountId())
                .isEqualTo(beneficiary.getAveniaId());
        assertThat(ticketCaptor.getValue().getTicketBrlPixOutput().pixKey()).isNull();
        assertThat(ticketCaptor.getValue().getTicketBrlPixOutput().userName()).isNull();
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput()).isNull();
        assertThat(ticketCaptor.getValue().getTicketSwiftOutput()).isNull();
        assertThat(ticketCaptor.getValue().getExternalId()).isEqualTo("Pagamento de importação");

        ArgumentCaptor<ImportTransactionEntity> baseCaptor = ArgumentCaptor.forClass(ImportTransactionEntity.class);
        verify(importTransactionRepository).save(baseCaptor.capture());
        ImportTransactionEntity persistedBase = baseCaptor.getValue();
        assertThat(persistedBase.getCompanyId()).isEqualTo(beneficiary.getCompany().getId());
        assertThat(persistedBase.getCreatorUserId()).isEqualTo(CURRENT_USER_ID);
        assertThat(persistedBase.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(persistedBase.getForeignCurrency()).isEqualTo("USD");
        assertThat(persistedBase.getForeignAmount()).isEqualByComparingTo("24235.14");
        assertThat(persistedBase.getSettlementAmountBrl()).isEqualByComparingTo("125000.00");
        assertThat(persistedBase.getServiceFeeBrl()).isEqualByComparingTo("562.50");
        assertThat(persistedBase.getEffectiveSpreadPercentage()).isEqualByComparingTo("0.4500");
        assertThat(persistedBase.getExchangeRate()).isEqualByComparingTo("5.16");
        assertThat(persistedBase.getAveniaTicketId()).isEqualTo(ticketId);

        assertThat(persistedBase.getId()).isNotNull();
        assertThat(persistedBase.getBeneficiaryId()).isEqualTo(beneficiary.getId());
        assertThat(persistedBase.getBeneficiary()).isEqualTo(beneficiary);
        assertThat(persistedBase.getTransferMethod()).isEqualTo(TransferMethod.PIX);
        verify(baseTransactionRepository, never()).save(any());
    }

    @Test
    void create_beneficiarioCripto_montaTicketBlockchainOutputEUsaMoedaCadastradaNoBeneficiario() {
        setUpService();
        stubSubAccount();
        stubTransactionPersistence();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        AveniaTransferResult result = new AveniaTransferResult(
                aveniaResponse(), new AveniaTicketResponse(UUID.randomUUID(), null, null, "UNPAID", null, null));
        when(aveniaTransferService.createTransfer(any(), any())).thenReturn(result);

        CreateTransferResponse response = transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(response.status()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);

        ArgumentCaptor<AveniaQuoteRequest> quoteCaptor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        ArgumentCaptor<AveniaTicketRequest> ticketCaptor = ArgumentCaptor.forClass(AveniaTicketRequest.class);
        verify(aveniaTransferService).createTransfer(quoteCaptor.capture(), ticketCaptor.capture());

        // Sem moeda informada, usa a moeda cadastrada no beneficiário (não BRL).
        assertThat(quoteCaptor.getValue().outputCurrency()).isEqualTo("USDC");
        assertThat(quoteCaptor.getValue().outputPaymentMethod()).isEqualTo("POLYGON");
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput().beneficiaryWalletId())
                .isEqualTo(beneficiary.getAveniaWalletId());
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput().walletAddress()).isNull();
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput().walletChain()).isNull();
        assertThat(ticketCaptor.getValue().getTicketBrlPixOutput()).isNull();
    }

    @Test
    void create_beneficiarioContaBancariaWire_montaTicketUsdWireOutput() {
        setUpService();
        stubSubAccount();
        stubTransactionPersistence();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        AveniaTransferResult result = new AveniaTransferResult(
                aveniaResponse(), new AveniaTicketResponse(UUID.randomUUID(), null, null, "PAID", null, null));
        when(aveniaTransferService.createTransfer(any(), any())).thenReturn(result);

        CreateTransferResponse response = transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(response.status()).isEqualTo(TransactionStatus.SETTLED);

        ArgumentCaptor<AveniaTicketRequest> ticketCaptor = ArgumentCaptor.forClass(AveniaTicketRequest.class);
        verify(aveniaTransferService).createTransfer(any(), ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getTicketUsdWireOutput().beneficiaryUsdBankAccountId())
                .isEqualTo(beneficiary.getAveniaId());
    }

    @Test
    void create_beneficiarioSepa_usaSaldoInternoEMontaTicketEur() {
        setUpService();
        stubSubAccount();
        stubTransactionPersistence();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary();
        beneficiary.setPaymentRail("sepa");
        beneficiary.setCurrency("EUR");
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        when(aveniaTransferService.createTransfer(any(), any())).thenReturn(new AveniaTransferResult(
                aveniaResponse(), new AveniaTicketResponse(
                        UUID.randomUUID(), null, null, "PROCESSING", null, null)));
        CreateTransferRequest request = createRequestWith(beneficiary.getId(), null);
        request.setPaymentMethod(TransferMethod.ACCOUNT_BALANCE);

        transferService.create(request, CURRENT_USER_ID, CURRENT_COMPANY_ID);

        ArgumentCaptor<AveniaQuoteRequest> quoteCaptor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        ArgumentCaptor<AveniaTicketRequest> ticketCaptor = ArgumentCaptor.forClass(AveniaTicketRequest.class);
        verify(aveniaTransferService).createTransfer(quoteCaptor.capture(), ticketCaptor.capture());
        assertThat(quoteCaptor.getValue().inputPaymentMethod()).isEqualTo("INTERNAL");
        assertThat(quoteCaptor.getValue().outputPaymentMethod()).isEqualTo("SEPA");
        assertThat(ticketCaptor.getValue().getTicketBrlPixInput()).isNull();
        assertThat(ticketCaptor.getValue().getTicketEurSepaOutput().beneficiaryEurBankAccountId())
                .isEqualTo(beneficiary.getAveniaId());
    }

    @Test
    void create_beneficiarioSwift_semInvoiceBloqueiaAntesDaCotacao() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary();
        beneficiary.setPaymentRail("international_swift");
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        stubSubAccount();

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("invoice processada");

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_beneficiarioInexistente_lancaNotFoundExceptionSemChamarAvenia() {
        setUpService();
        UUID beneficiaryId = UUID.randomUUID();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiaryId, CURRENT_COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiaryId, "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(NotFoundException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_beneficiarioDeOutraEmpresa_retornaNotFoundSemChamarAvenia() {
        setUpService();
        UUID beneficiaryId = UUID.randomUUID();
        UUID anotherCompanyId = UUID.randomUUID();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiaryId, CURRENT_COMPANY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiaryId, "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(NotFoundException.class);

        verify(beneficiaryRepository, never()).findByIdAndCompanyId(beneficiaryId, anotherCompanyId);
        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_beneficiarioSemIdAvenia_retornaErroSemGerarCotacao() {
        setUpService();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        beneficiary.setAveniaId(null);
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("não possui conta cadastrada na Avenia");

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_beneficiarioLegadoSemSubconta_retornaErroSemGerarCotacao() {
        setUpService();
        stubSubAccount();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        beneficiary.setAveniaSubAccountId(null);
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("vínculo confiável");

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_beneficiarioDeOutraSubconta_retornaErroSemGerarCotacao() {
        setUpService();
        stubSubAccount();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        beneficiary.setAveniaSubAccountId("another-sub-account");
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("outra subconta Avenia");

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_moedaDeDestinoNaoInformadaNemCadastradaNemInferivel_lancaUnprocessableEntityException() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary();
        beneficiary.setCurrency(null);
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_sourceCurrencyDiferenteDeBrl_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        CreateTransferRequest request = createRequestWith(beneficiary.getId(), null);
        request.setSourceCurrency("USD");

        assertThatThrownBy(() -> transferService.create(request, CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
        verify(baseTransactionRepository, never()).save(any());
        verify(importTransactionRepository, never()).save(any());
    }

    @Test
    void create_falhaNaAvenia_propagaAveniaIntegrationExceptionSemCapturar() {
        setUpService();
        stubSubAccount();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        when(aveniaTransferService.createTransfer(any(), any()))
                .thenThrow(AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(AveniaIntegrationException.class);

        verify(baseTransactionRepository, never()).save(any());
        verify(importTransactionRepository, never()).save(any());
    }

    @Test
    void create_usuarioSemVerificacaoKyc_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }
}
