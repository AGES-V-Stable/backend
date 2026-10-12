package ages.vstable.backend.service;

import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private static final String QUOTE_TOKEN = "quote-token-should-never-leak";

    private void stubSubAccount() {
        AveniaKycVerificationEntity kyc = AveniaKycVerificationEntity.builder()
                .id(UUID.randomUUID())
                .userId(CURRENT_USER_ID)
                .aveniaSubAccountId(SUB_ACCOUNT_ID)
                .build();
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID)).thenReturn(Optional.of(kyc));
        when(subAccountProvisioningService.ensureSubAccountId(kyc)).thenReturn(SUB_ACCOUNT_ID);
    }

    private void stubBeneficiary(BeneficiaryEntity beneficiary) {
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiary.getId(), CURRENT_COMPANY_ID))
                .thenReturn(Optional.of(beneficiary));
    }

    private TransferQuoteRequest requestWith(BigDecimal amount, TransferAmountType type,
                                              String source, String destination, UUID beneficiaryId) {
        TransferQuoteRequest request = new TransferQuoteRequest();
        request.setAmount(amount);
        request.setAmountType(type);
        request.setSourceCurrency(source);
        request.setDestinationCurrency(destination);
        request.setBeneficiaryId(beneficiaryId);
        request.setPaymentMethod(TransferMethod.PIX);
        return request;
    }

    private AveniaQuoteResponse aveniaResponse() {
        return new AveniaQuoteResponse(
                QUOTE_TOKEN,
                "BRL", "PIX", new BigDecimal("125000.00"),
                "USDC", "POLYGON", new BigDecimal("24235.14"),
                new BigDecimal("562.50"), "BRL",
                false, false,
                List.of(new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("562.50"), "BRL")),
                new BigDecimal("5.16"), "BRLUSDC");
    }

    private AveniaQuoteResponse aveniaResponseWithFees(BigDecimal markupAmount, List<AveniaAppliedFee> fees) {
        return new AveniaQuoteResponse(
                "token", "BRL", "PIX", new BigDecimal("100.00"),
                "USDC", "POLYGON", new BigDecimal("20.00"),
                markupAmount, "BRL", false, false,
                fees, new BigDecimal("5.00"), "BRLUSDC");
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

    private BeneficiaryEntity bankAccountBeneficiary(String paymentRail, String currency) {
        return BeneficiaryEntity.builder()
                .id(UUID.randomUUID())
                .company(company())
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .aveniaId(UUID.randomUUID())
                .aveniaSubAccountId(SUB_ACCOUNT_ID)
                .country("Estados Unidos")
                .currency(currency)
                .paymentRail(paymentRail)
                .build();
    }

    // ---------------------------------------------------------------- cotação

    @Test
    void quote_valorDeOrigemInformado_montaCotacaoComInputAmountEDevolveBreakdownSemIdentificadoresDaAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        TransferQuoteResponse response = transferService.quote(
                requestWith(new BigDecimal("125000.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputAmount()).isEqualByComparingTo("125000.00");
        assertThat(captor.getValue().outputAmount()).isNull();
        assertThat(captor.getValue().inputCurrency()).isEqualTo("BRL");
        assertThat(captor.getValue().inputPaymentMethod()).isEqualTo("PIX");
        assertThat(captor.getValue().outputCurrency()).isEqualTo("USDC");
        assertThat(captor.getValue().outputPaymentMethod()).isEqualTo("POLYGON");
        assertThat(captor.getValue().subAccountId()).isEqualTo(SUB_ACCOUNT_ID);
        assertThat(captor.getValue().feeSettlement()).isEqualTo("IMMEDIATE");

        assertThat(response.source()).isEqualTo(new MoneyAmount(new BigDecimal("125000.00"), "BRL"));
        assertThat(response.destination()).isEqualTo(new MoneyAmount(new BigDecimal("24235.14"), "USDC"));
        assertThat(response.fee().amount()).isEqualByComparingTo("562.50");
        assertThat(response.fee().currency()).isEqualTo("BRL");
        assertThat(response.fee().percentage()).isEqualByComparingTo("0.45");
        assertThat(response.total().amount()).isEqualByComparingTo("125000.00");
        assertThat(response.total().currency()).isEqualTo("BRL");
        assertThat(response.exchangeRate().fromCurrency()).isEqualTo("USDC");
        assertThat(response.exchangeRate().toCurrency()).isEqualTo("BRL");
        assertThat(response.exchangeRate().rate()).isEqualByComparingTo("5.16");
        assertThat(response.toString())
                .doesNotContain(QUOTE_TOKEN)
                .doesNotContain(SUB_ACCOUNT_ID)
                .doesNotContain(beneficiary.getAveniaWalletId().toString());
    }

    @Test
    void quote_valorDeDestinoInformado_montaCotacaoComOutputAmount() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        transferService.quote(
                requestWith(new BigDecimal("24235.14"), TransferAmountType.DESTINATION, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputAmount()).isNull();
        assertThat(captor.getValue().outputAmount()).isEqualByComparingTo("24235.14");
    }

    @Test
    void quote_apenasMoedaDeDestinoInformada_infereBrlComoOrigem() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, null, "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputCurrency()).isEqualTo("BRL");
    }

    @Test
    void quote_semNenhumaMoeda_usaMoedaCadastradaNoBeneficiario() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());

        transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, null, null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);

        ArgumentCaptor<AveniaQuoteRequest> captor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        verify(aveniaTransferService).createQuote(captor.capture());
        assertThat(captor.getValue().inputCurrency()).isEqualTo("BRL");
        assertThat(captor.getValue().outputCurrency()).isEqualTo("USDC");
    }

    @Test
    void quote_semBeneficiario_rejeitaSemConsultarBeneficiarioNemAvenia() {
        setUpService();

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", null),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("beneficiaryId e paymentMethod");

        verifyNoInteractions(beneficiaryRepository, subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_semMetodoDePagamento_rejeitaSemConsultarBeneficiarioNemAvenia() {
        setUpService();
        TransferQuoteRequest request = requestWith(
                new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", UUID.randomUUID());
        request.setPaymentMethod(null);

        assertThatThrownBy(() -> transferService.quote(request, CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("beneficiaryId e paymentMethod");

        verifyNoInteractions(beneficiaryRepository, subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_moedaDeDestinoAusenteENaoDeterminavel_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary("wire", null);
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("destinationCurrency");

        verify(aveniaTransferService, never()).createQuote(any());
    }

    @Test
    void quote_beneficiarioPix_rejeitaBrlParaBrlPixSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("não oferece BRL via PIX para BRL via PIX");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @ParameterizedTest
    @CsvSource({
            "ach, USD, USD via ACH",
            "wire, USD, USD via WIRE",
            "sepa, EUR, EUR via SEPA"
    })
    void quote_beneficiarioContaBancariaSemRotaBrlDocumentada_rejeitaSemChamarAvenia(
            String paymentRail, String currency, String expectedRoute) {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary(paymentRail, currency);
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("não oferece BRL via PIX para " + expectedRoute)
                .hasMessageContaining("saldo em token");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @ParameterizedTest
    @CsvSource({
            "BRLA, tron",
            "USDT, gnosis",
            "EURC, polygon",
            "BTC, polygon"
    })
    void quote_tokenNaoSuportadoNaRedeDoBeneficiario_rejeitaSemChamarAvenia(
            String currency, BlockchainNetwork network) {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        beneficiary.setCurrency(currency);
        beneficiary.setBlockchainNetwork(network);
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("na rede");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_saldoEmConta_rejeitaSemProvisionarSubcontaNemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        TransferQuoteRequest request = requestWith(
                new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId());
        request.setPaymentMethod(TransferMethod.ACCOUNT_BALANCE);

        assertThatThrownBy(() -> transferService.quote(request, CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ACCOUNT_BALANCE");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_metodoTed_rejeitaSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        TransferQuoteRequest request = requestWith(
                new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId());
        request.setPaymentMethod(TransferMethod.TED);

        assertThatThrownBy(() -> transferService.quote(request, CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TED");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_beneficiarioSwift_bloqueiaAntesDaCotacao() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary("international_swift", "USD");
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("invoice processada");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_beneficiarioDeOutraEmpresa_retornaNotFoundSemChamarAvenia() {
        setUpService();
        UUID beneficiaryId = UUID.randomUUID();
        when(beneficiaryRepository.findByIdAndCompanyId(beneficiaryId, CURRENT_COMPANY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiaryId),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
    }

    @Test
    void quote_usuarioSemEmpresa_retornaNotFoundSemConsultarBeneficiario() {
        setUpService();

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", UUID.randomUUID()),
                CURRENT_USER_ID, null))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(beneficiaryRepository, aveniaTransferService);
    }

    @Test
    void quote_beneficiarioDeOutraSubconta_retornaErroSemGerarCotacao() {
        setUpService();
        stubSubAccount();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        beneficiary.setAveniaSubAccountId("another-sub-account");
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("outra subconta Avenia");

        verify(aveniaTransferService, never()).createQuote(any());
    }

    @Test
    void quote_falhaNaAvenia_propagaAveniaIntegrationExceptionSemCapturar() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any()))
                .thenThrow(AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(AveniaIntegrationException.class);
    }

    @Test
    void quote_usuarioSemVerificacaoKyc_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createQuote(any());
    }

    @Test
    void quote_appliedFeesTemPrecedenciaSobreMarkupAmountNoCalculoDoTotal() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponseWithFees(
                new BigDecimal("1.00"),
                List.of(
                        new AveniaAppliedFee("Markup Fee", "desc", new BigDecimal("1.00"), "BRL"),
                        new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("2.00"), "BRL"))));

        TransferQuoteResponse result = transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(result.fee().amount()).isEqualByComparingTo("3.00");
        assertThat(result.fee().percentage()).isEqualByComparingTo("3.00");
        assertThat(result.total().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void quote_taxaEmMoedaDiferenteDaOrigem_rejeitaRespostaSemSomarMoedas() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponseWithFees(
                BigDecimal.ZERO,
                List.of(new AveniaAppliedFee("Out Fee", "desc", new BigDecimal("2.00"), "USDC"))));

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("different from the source currency");
    }

    @Test
    void quote_appliedFeeIncompleta_rejeitaResposta() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponseWithFees(
                BigDecimal.ZERO,
                List.of(new AveniaAppliedFee("Out Fee", "desc", null, "BRL"))));

        assertThatThrownBy(() -> transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(AveniaIntegrationException.class)
                .hasMessageContaining("incomplete financial data");
    }

    @Test
    void quote_semAppliedFees_usaMarkupSomenteQuandoEstaNaMoedaDeOrigem() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        when(aveniaTransferService.createQuote(any()))
                .thenReturn(aveniaResponseWithFees(new BigDecimal("1.50"), List.of()));

        TransferQuoteResponse result = transferService.quote(
                requestWith(new BigDecimal("100.00"), TransferAmountType.SOURCE, "BRL", "USDC", beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(result.fee().amount()).isEqualByComparingTo("1.50");
        assertThat(result.fee().percentage()).isEqualByComparingTo("1.50");
        assertThat(result.total().amount()).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------- cotação x confirmação

    @Test
    void quoteECreate_mesmaOperacao_usamMesmaComposicaoEConfirmacaoGeraNovaCotacao() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        stubSubAccount();
        stubTransactionPersistence();
        when(aveniaTransferService.createQuote(any())).thenReturn(aveniaResponse());
        when(aveniaTransferService.createTransfer(any(), any())).thenReturn(new AveniaTransferResult(
                aveniaResponse(), new AveniaTicketResponse(UUID.randomUUID(), null, null, "UNPAID", null, null)));

        transferService.quote(
                requestWith(new BigDecimal("125000.00"), TransferAmountType.SOURCE, null, null, beneficiary.getId()),
                CURRENT_USER_ID, CURRENT_COMPANY_ID);
        CreateTransferRequest createRequest = createRequestWith(beneficiary.getId(), null);
        createRequest.setSourceCurrency(null);
        transferService.create(createRequest, CURRENT_USER_ID, CURRENT_COMPANY_ID);

        ArgumentCaptor<AveniaQuoteRequest> previewCaptor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        ArgumentCaptor<AveniaQuoteRequest> confirmationCaptor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        ArgumentCaptor<AveniaTicketRequest> ticketCaptor = ArgumentCaptor.forClass(AveniaTicketRequest.class);
        verify(aveniaTransferService, times(1)).createQuote(previewCaptor.capture());
        verify(aveniaTransferService).createTransfer(confirmationCaptor.capture(), ticketCaptor.capture());

        assertThat(confirmationCaptor.getValue()).isEqualTo(previewCaptor.getValue());
        // A confirmação não carrega o token da cotação exibida; AveniaTransferService gera
        // uma cotação nova e injeta o token dela no ticket.
        assertThat(ticketCaptor.getValue().getQuoteToken()).isNull();
    }

    // ---------------------------------------------------------------- criação

    @Test
    void create_beneficiarioCripto_pagaViaPixMontaTicketBlockchainOutputEPersisteTransacao() {
        setUpService();
        stubSubAccount();
        stubTransactionPersistence();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        UUID ticketId = UUID.randomUUID();
        AveniaTransferResult result = new AveniaTransferResult(
                aveniaResponse(), new AveniaTicketResponse(ticketId, null, null, "UNPAID", null, null));
        when(aveniaTransferService.createTransfer(any(), any())).thenReturn(result);

        CreateTransferRequest request = createRequestWith(beneficiary.getId(), null);
        request.setSourceCurrency(null);

        CreateTransferResponse response = transferService.create(
                request, CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(response.status()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);

        ArgumentCaptor<AveniaQuoteRequest> quoteCaptor = ArgumentCaptor.forClass(AveniaQuoteRequest.class);
        ArgumentCaptor<AveniaTicketRequest> ticketCaptor = ArgumentCaptor.forClass(AveniaTicketRequest.class);
        verify(aveniaTransferService).createTransfer(quoteCaptor.capture(), ticketCaptor.capture());

        // Sem moeda no request: origem BRL pelo saldo da empresa, destino pela moeda cadastrada.
        assertThat(quoteCaptor.getValue().inputCurrency()).isEqualTo("BRL");
        assertThat(quoteCaptor.getValue().inputPaymentMethod()).isEqualTo("PIX");
        assertThat(quoteCaptor.getValue().outputCurrency()).isEqualTo("USDC");
        assertThat(quoteCaptor.getValue().outputPaymentMethod()).isEqualTo("POLYGON");
        assertThat(quoteCaptor.getValue().feeSettlement()).isEqualTo("IMMEDIATE");
        assertThat(quoteCaptor.getValue().subAccountId()).isEqualTo(SUB_ACCOUNT_ID);
        assertThat(ticketCaptor.getValue().getTicketBrlPixInput()).isNotNull();
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput().beneficiaryWalletId())
                .isEqualTo(beneficiary.getAveniaWalletId());
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput().walletAddress()).isNull();
        assertThat(ticketCaptor.getValue().getTicketBlockchainOutput().walletChain()).isNull();
        assertThat(ticketCaptor.getValue().getTicketBrlPixOutput()).isNull();
        assertThat(ticketCaptor.getValue().getTicketSwiftOutput()).isNull();
        assertThat(ticketCaptor.getValue().getExternalId()).isEqualTo("Pagamento de importação");

        ArgumentCaptor<ImportTransactionEntity> baseCaptor = ArgumentCaptor.forClass(ImportTransactionEntity.class);
        verify(importTransactionRepository).save(baseCaptor.capture());
        ImportTransactionEntity persistedBase = baseCaptor.getValue();
        assertThat(persistedBase.getCompanyId()).isEqualTo(beneficiary.getCompany().getId());
        assertThat(persistedBase.getCreatorUserId()).isEqualTo(CURRENT_USER_ID);
        assertThat(persistedBase.getStatus()).isEqualTo(TransactionStatus.AWAITING_PAYMENT);
        assertThat(persistedBase.getForeignCurrency()).isEqualTo("USDC");
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
    void create_statusComHifen_mapeiaEstadosDocumentadosPelaAvenia() {
        setUpService();
        stubSubAccount();
        stubTransactionPersistence();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        when(aveniaTransferService.createTransfer(any(), any()))
                .thenReturn(new AveniaTransferResult(
                        aveniaResponse(), new AveniaTicketResponse(
                                UUID.randomUUID(), null, null, "ON-HOLD", null, null)))
                .thenReturn(new AveniaTransferResult(
                        aveniaResponse(), new AveniaTicketResponse(
                                UUID.randomUUID(), null, null, "PARTIAL-FAILED", null, null)))
                .thenReturn(new AveniaTransferResult(
                        aveniaResponse(), new AveniaTicketResponse(
                                UUID.randomUUID(), null, null, "PAID", null, null)));

        CreateTransferResponse held = transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID);
        CreateTransferResponse partialFailure = transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID);
        CreateTransferResponse settled = transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID);

        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(partialFailure.status()).isEqualTo(TransactionStatus.PARTIAL_FAILURE);
        assertThat(settled.status()).isEqualTo(TransactionStatus.SETTLED);
    }

    @Test
    void create_beneficiarioPix_rejeitaBrlParaBrlPixSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = pixBeneficiary();
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), "BRL"), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("não oferece BRL via PIX para BRL via PIX");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService, importTransactionRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "ach, USD",
            "wire, USD",
            "sepa, EUR"
    })
    void create_beneficiarioContaBancariaSemRotaBrlDocumentada_rejeitaSemChamarAvenia(
            String paymentRail, String currency) {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary(paymentRail, currency);
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("saldo em token");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService, importTransactionRepository);
    }

    @Test
    void create_saldoEmConta_rejeitaSemConverterBrlNemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary("sepa", "EUR");
        stubBeneficiary(beneficiary);
        CreateTransferRequest request = createRequestWith(beneficiary.getId(), null);
        request.setPaymentMethod(TransferMethod.ACCOUNT_BALANCE);

        assertThatThrownBy(() -> transferService.create(request, CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ACCOUNT_BALANCE");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService, importTransactionRepository);
    }

    @Test
    void create_beneficiarioSwift_semInvoiceBloqueiaAntesDaCotacao() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary("international_swift", "USD");
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("invoice processada");

        verifyNoInteractions(subAccountProvisioningService, aveniaTransferService);
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
        stubBeneficiary(beneficiary);

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
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        beneficiary.setAveniaSubAccountId(null);
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("vínculo confiável");

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_beneficiarioDeOutraSubconta_retornaErroSemGerarCotacao() {
        setUpService();
        stubSubAccount();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        beneficiary.setAveniaSubAccountId("another-sub-account");
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("outra subconta Avenia");

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_moedaDeDestinoNaoInformadaNemCadastradaNemInferivel_lancaUnprocessableEntityException() {
        setUpService();
        BeneficiaryEntity beneficiary = bankAccountBeneficiary("wire", null);
        stubBeneficiary(beneficiary);

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }

    @Test
    void create_sourceCurrencyDiferenteDeBrl_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        CreateTransferRequest request = createRequestWith(beneficiary.getId(), null);
        request.setSourceCurrency("USD");

        assertThatThrownBy(() -> transferService.create(request, CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
        verify(baseTransactionRepository, never()).save(any());
        verify(importTransactionRepository, never()).save(any());
    }

    @Test
    void create_falhaNaCotacaoDaAvenia_propagaAveniaIntegrationExceptionSemPersistir() {
        setUpService();
        stubSubAccount();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        when(aveniaTransferService.createTransfer(any(), any()))
                .thenThrow(AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(AveniaIntegrationException.class);

        verify(baseTransactionRepository, never()).save(any());
        verify(importTransactionRepository, never()).save(any());
    }

    @Test
    void create_usuarioSemVerificacaoKyc_lancaUnprocessableEntityExceptionSemChamarAvenia() {
        setUpService();
        BeneficiaryEntity beneficiary = cryptoBeneficiary();
        stubBeneficiary(beneficiary);
        when(aveniaKycVerificationRepository.findFirstByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.create(
                createRequestWith(beneficiary.getId(), null), CURRENT_USER_ID, CURRENT_COMPANY_ID))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(aveniaTransferService, never()).createTransfer(any(), any());
    }
}
