package ages.vstable.backend.sandbox;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.dto.quote.QuoteResponse;
import ages.vstable.backend.dto.transfer.ConfirmOfferRequest;
import ages.vstable.backend.dto.transfer.TransferExecutionResponse;
import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.BeneficiaryProviderAccountEntity;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.TransferExecutionEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.BlindPayOnboardingStatus;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.entity.enums.QuoteAmountSide;
import ages.vstable.backend.entity.enums.QuoteDirection;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.entity.enums.TransferExecutionStatus;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.repository.BeneficiaryProviderAccountRepository;
import ages.vstable.backend.repository.BeneficiaryRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import ages.vstable.backend.repository.ProviderQuoteRepository;
import ages.vstable.backend.repository.TransferExecutionRepository;
import ages.vstable.backend.repository.UserRepository;
import ages.vstable.backend.service.quote.QuoteOrchestratorService;
import ages.vstable.backend.service.transfer.BlindPayExecutionProcessor;
import ages.vstable.backend.service.transfer.TransferExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transferências completas na instância de DESENVOLVIMENTO da BlindPay, sem mocks: cotação
 * agregada, confirmação, payin, confirmação dos fundos e payout pelo código de produção.
 *
 * <p>Não roda no {@code ./gradlew test}: use {@code ./gradlew blindpaySandboxTest} com as
 * variáveis de docs/blindpay-transferencias-sandbox.md. Só aceita rede de teste e USDB, que
 * uma instância de produção recusa, então nunca movimenta dinheiro real. Os webhooks não
 * chegam a uma máquina local; o teste avança as execuções pela mesma conciliação usada em
 * produção quando um webhook se perde.
 *
 * <p>Valores de desenvolvimento documentados pela BlindPay: {@code request_amount} 66600
 * resulta em {@code failed} e 77700 em {@code refunded}; outros valores completam.
 */
@Tag("blindpay-sandbox")
@EnabledIfEnvironmentVariable(named = "BLINDPAY_SANDBOX_CUSTOMER_ID", matches = ".+")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "transfers.reconciliation.enabled=false")
class BlindPaySandboxTransferTest {

    private static final Set<String> TEST_NETWORKS = Set.of(
            "base_sepolia", "sepolia", "arbitrum_sepolia", "polygon_amoy");
    private static final Duration TIMEOUT = Duration.ofMinutes(4);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private static final String CUSTOMER_ID = env("BLINDPAY_SANDBOX_CUSTOMER_ID", null);
    private static final String BANK_ACCOUNT_ID = env("BLINDPAY_SANDBOX_BANK_ACCOUNT_ID", null);
    private static final String PAYMENT_RAIL = env("BLINDPAY_SANDBOX_PAYMENT_RAIL", "wire");
    private static final String TARGET_CURRENCY = env("BLINDPAY_SANDBOX_TARGET_CURRENCY", "USD");
    private static final String NETWORK = env("BLINDPAY_SANDBOX_NETWORK", "base_sepolia");
    private static final String TOKEN = env("BLINDPAY_SANDBOX_TOKEN", "USDB");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.4");

    /** Um único cliente BlindPay só pode estar vinculado a uma empresa (índice único). */
    private static Fixture fixture;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private QuoteOrchestratorService quoteService;
    @Autowired private TransferExecutionService executionService;
    @Autowired private BlindPayExecutionProcessor processor;
    @Autowired private BlindPayGateway blindPayGateway;
    @Autowired private TransferExecutionRepository executionRepository;
    @Autowired private ProviderQuoteRepository providerQuoteRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BeneficiaryRepository beneficiaryRepository;
    @Autowired private ProviderAccountRepository providerAccountRepository;
    @Autowired private BeneficiaryProviderAccountRepository beneficiaryProviderAccountRepository;

    private record Fixture(UserEntity user, BeneficiaryEntity beneficiary) {
    }

    @BeforeEach
    void requireDevelopmentInstanceAndSeed() {
        assertThat(BANK_ACCOUNT_ID).as("BLINDPAY_SANDBOX_BANK_ACCOUNT_ID").isNotBlank();
        assertThat(TEST_NETWORKS).as("sandbox must use a test network").contains(NETWORK);
        assertThat(TOKEN).as("sandbox must use the development token").isEqualTo("USDB");
        if (fixture == null) {
            fixture = seed();
        }
    }

    @Test
    void brlSourceAmount_payinThenPayout_completes() {
        TransferExecutionResponse confirmed = quoteAndConfirm(QuoteAmountSide.SOURCE, "100.00");

        assertThat(confirmed.status()).isEqualTo(TransferExecutionStatus.AWAITING_FUNDS);
        assertThat(confirmed.fundingInstructions()).isNotBlank();
        TransferExecutionEntity finished = awaitTerminal(confirmed.executionId());
        assertThat(finished.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
        assertThat(finished.getExternalPayoutId()).startsWith("po_");
        assertThat(finished.getFundedAmount()).isPositive();
    }

    @Test
    void payinFailure_neverSendsPayout() {
        TransferExecutionEntity finished = awaitTerminal(quoteAndConfirm(QuoteAmountSide.SOURCE, "666.00").executionId());

        assertThat(finished.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(finished.getExternalPayoutId()).isNull();
    }

    @Test
    void payinRefund_neverSendsPayout() {
        TransferExecutionEntity finished = awaitTerminal(quoteAndConfirm(QuoteAmountSide.SOURCE, "777.00").executionId());

        assertThat(finished.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);
        assertThat(finished.getExternalPayoutId()).isNull();
    }

    @Test
    void payoutFailure_isRecordedAfterFundsArrive() {
        TransferExecutionEntity finished = awaitTerminal(quoteAndConfirm(QuoteAmountSide.TARGET, "666.00").executionId());

        assertThat(finished.getStatus()).isEqualTo(TransferExecutionStatus.FAILED);
        assertThat(finished.getFundedAmount()).isPositive();
        assertThat(finished.getExternalPayoutId()).startsWith("po_");
    }

    @Test
    void payoutRefund_isRecordedAfterFundsArrive() {
        TransferExecutionEntity finished = awaitTerminal(quoteAndConfirm(QuoteAmountSide.TARGET, "777.00").executionId());

        assertThat(finished.getStatus()).isEqualTo(TransferExecutionStatus.REFUNDED);
        assertThat(finished.getExternalPayoutId()).startsWith("po_");
    }

    @Test
    void expiredOffer_cannotBeConfirmed() {
        QuoteResponse quote = quote(QuoteAmountSide.SOURCE, "100.00");
        providerQuoteRepository.findById(quote.offer().offerId()).ifPresent(offer -> {
            offer.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
            providerQuoteRepository.save(offer);
        });

        assertThatThrownBy(() -> confirm(quote))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("The offer has expired; request a new quote");
    }

    @Test
    void payoutQuoteExpiredDuringFunding_isRequotedAndNeverPaidAtWorsePriceSilently() {
        TransferExecutionResponse confirmed = quoteAndConfirm(QuoteAmountSide.SOURCE, "100.00");
        TransferExecutionEntity execution = executionRepository.findById(confirmed.executionId()).orElseThrow();
        execution.setPayoutQuoteExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        executionRepository.save(execution);

        TransferExecutionEntity settled = awaitStatus(confirmed.executionId(), Set.of(
                TransferExecutionStatus.COMPLETED, TransferExecutionStatus.FAILED,
                TransferExecutionStatus.REFUNDED, TransferExecutionStatus.REQUOTE_REQUIRED));
        if (settled.getStatus() == TransferExecutionStatus.REQUOTE_REQUIRED) {
            assertThat(settled.getPayoutQuoteReceiverAmount()).isLessThan(settled.getTargetAmount());
            assertThat(settled.getExternalPayoutId()).isNull();
            executionService.confirmRequote(fixture.user(), settled.getId(), new ConfirmOfferRequest(
                    settled.getSourceAmount(), settled.getPayoutQuoteReceiverAmount()));
            settled = awaitTerminal(settled.getId());
        }
        assertThat(settled.getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
        assertThat(settled.getPayoutQuoteAttempts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void duplicateConfirmationAndRetriedPayin_doNotCreateSecondTransfer() {
        QuoteResponse quote = quote(QuoteAmountSide.SOURCE, "100.00");

        TransferExecutionResponse first = confirm(quote);
        TransferExecutionResponse second = confirm(quote);
        TransferExecutionEntity execution = executionRepository.findById(first.executionId()).orElseThrow();
        BlindPayPayinResponse retried = blindPayGateway.createEvmPayin(execution.getExternalPayinQuoteId(),
                "transfer-execution:" + execution.getId() + ":payin");

        assertThat(second.executionId()).isEqualTo(first.executionId());
        assertThat(retried.id()).isEqualTo(execution.getExternalPayinId());
        assertThat(awaitTerminal(first.executionId()).getStatus()).isEqualTo(TransferExecutionStatus.COMPLETED);
    }

    private TransferExecutionResponse quoteAndConfirm(QuoteAmountSide side, String amount) {
        return confirm(quote(side, amount));
    }

    private QuoteResponse quote(QuoteAmountSide side, String amount) {
        QuoteResponse quote = quoteService.quote(fixture.user(), new QuoteRequest(
                fixture.beneficiary().getId(), QuoteDirection.PAYOUT, "BRL", TARGET_CURRENCY, "pix", PAYMENT_RAIL,
                new BigDecimal(amount), side, TOKEN, NETWORK, false, "V-Stable sandbox"));
        assertThat(quote.offer().provider()).isEqualTo(IntegrationProvider.BLINDPAY.name());
        return quote;
    }

    private TransferExecutionResponse confirm(QuoteResponse quote) {
        return executionService.confirm(fixture.user(), quote.offer().offerId(),
                new ConfirmOfferRequest(quote.offer().sourceAmount(), quote.offer().targetAmount()));
    }

    private TransferExecutionEntity awaitTerminal(UUID executionId) {
        return awaitStatus(executionId, Set.of(
                TransferExecutionStatus.COMPLETED, TransferExecutionStatus.FAILED,
                TransferExecutionStatus.REFUNDED, TransferExecutionStatus.RECONCILIATION_REQUIRED));
    }

    /** Avança a execução pela conciliação (os webhooks não chegam localmente) até o estado esperado. */
    private TransferExecutionEntity awaitStatus(UUID executionId, Set<TransferExecutionStatus> expected) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            TransferExecutionEntity execution = executionRepository.findById(executionId).orElseThrow();
            if (expected.contains(execution.getStatus())) {
                return execution;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Execution " + executionId + " still " + execution.getStatus()
                        + " (" + execution.getFailureCode() + ": " + execution.getFailureMessage() + ")");
            }
            sleep();
            processor.reconcile(executionId);
        }
    }

    private Fixture seed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        CompanyEntity company = companyRepository.save(CompanyEntity.builder()
                .legalName("V-Stable Sandbox " + unique).cnpj("sandbox-" + unique).country("Brasil").build());
        UserEntity user = userRepository.save(UserEntity.builder()
                .companyId(company.getId()).fullName("Sandbox User").email("sandbox-" + unique + "@example.com")
                .passwordHash("not-used").passwordSalt("not-used").build());
        BeneficiaryEntity beneficiary = beneficiaryRepository.save(BeneficiaryEntity.builder()
                .company(company).nickname("Sandbox beneficiary").accountHolderName("Sandbox beneficiary")
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT).currency(TARGET_CURRENCY).paymentRail(PAYMENT_RAIL)
                .build());
        providerAccountRepository.save(ProviderAccountEntity.builder()
                .company(company).provider(IntegrationProvider.BLINDPAY).externalCustomerId(CUSTOMER_ID)
                .status(BlindPayOnboardingStatus.APPROVED.name()).createdAt(now).updatedAt(now).build());
        beneficiaryProviderAccountRepository.save(BeneficiaryProviderAccountEntity.builder()
                .beneficiary(beneficiary).provider(IntegrationProvider.BLINDPAY).externalBankAccountId(BANK_ACCOUNT_ID)
                .status("APPROVED").createdAt(now).updatedAt(now).build());
        return new Fixture(user, beneficiary);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", ex);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
