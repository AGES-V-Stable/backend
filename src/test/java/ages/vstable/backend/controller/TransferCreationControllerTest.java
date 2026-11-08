package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transfer.CreateTransferResponse;
import ages.vstable.backend.dto.transfer.ExchangeRateInfo;
import ages.vstable.backend.dto.transfer.FeeInfo;
import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transfer.MoneyAmount;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferMethod;
import ages.vstable.backend.exception.AveniaIntegrationException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.service.TransferService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import ages.vstable.backend.dto.transfer.CreateTransferRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransferController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class TransferCreationControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    private static final UUID BENEFICIARY_ID = UUID.randomUUID();

    private final UserEntity principal = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OWNER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String validPayload() throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 125000.00);
                put("amountType", "SOURCE");
                put("sourceCurrency", "BRL");
                put("destinationCurrency", "USD");
                put("beneficiaryId", BENEFICIARY_ID.toString());
                put("paymentMethod", "PIX");
            }
        });
    }

    private TransferQuoteResponse sampleResponse() {
        return new TransferQuoteResponse(
                new MoneyAmount(new BigDecimal("125000.00"), "BRL"),
                new MoneyAmount(new BigDecimal("24235.14"), "USD"),
                new ExchangeRateInfo("USD", "BRL", new BigDecimal("5.16")),
                new FeeInfo(new BigDecimal("0.45"), new BigDecimal("562.50"), "BRL"),
                new MoneyAmount(new BigDecimal("125000.00"), "BRL"));
    }

    @Test
    void post_quoteValida_retorna200ComBreakdownSemIdentificadoresDaAvenia() throws Exception {
        when(transferService.quote(any(), any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.amount").value(125000.00))
                .andExpect(jsonPath("$.source.currency").value("BRL"))
                .andExpect(jsonPath("$.destination.amount").value(24235.14))
                .andExpect(jsonPath("$.destination.currency").value("USD"))
                .andExpect(jsonPath("$.exchangeRate.rate").value(5.16))
                .andExpect(jsonPath("$.fee.percentage").value(0.45))
                .andExpect(jsonPath("$.fee.amount").value(562.50))
                .andExpect(jsonPath("$.total.amount").value(125000.00))
                .andExpect(jsonPath("$.quoteToken").doesNotExist())
                .andExpect(jsonPath("$.quoteId").doesNotExist())
                .andExpect(jsonPath("$.ticketId").doesNotExist());
    }

    @Test
    void post_semAmount_retorna400() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amountType", "SOURCE");
            }
        });

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_semAmountType_retorna400() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 100.00);
            }
        });

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_moedaDeDestinoNaoDeterminavel_retorna422() throws Exception {
        when(transferService.quote(any(), any(), any())).thenThrow(
                new UnprocessableEntityException("Não foi possível determinar a moeda de destino da transferência"));

        String payload = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 100.00);
                put("amountType", "SOURCE");
            }
        });

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void post_quoteComBeneficiarioEMetodo_repassaOperacaoAoService() throws Exception {
        when(transferService.quote(any(), any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isOk());

        ArgumentCaptor<TransferQuoteRequest> captor = ArgumentCaptor.forClass(TransferQuoteRequest.class);
        verify(transferService).quote(captor.capture(), any(), any());
        assertThat(captor.getValue().getBeneficiaryId()).isEqualTo(BENEFICIARY_ID);
        assertThat(captor.getValue().getPaymentMethod()).isEqualTo(TransferMethod.PIX);
    }

    @Test
    void post_quoteMetodoDePagamentoInvalido_retorna400SemChamarService() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 100.00);
                put("amountType", "SOURCE");
                put("beneficiaryId", BENEFICIARY_ID.toString());
                put("paymentMethod", "INTERNAL");
            }
        });

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());

        verify(transferService, never()).quote(any(), any(), any());
    }

    @Test
    void post_quoteCombinacaoNaoSuportada_retorna422() throws Exception {
        when(transferService.quote(any(), any(), any())).thenThrow(
                new UnprocessableEntityException("A Avenia não oferece BRL via PIX para USD via WIRE"));

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void post_quoteBeneficiarioDeOutraEmpresa_retorna404() throws Exception {
        when(transferService.quote(any(), any(), any()))
                .thenThrow(new NotFoundException("Beneficiário não encontrado"));

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_falhaNaAvenia_retorna503QuandoRetryable() throws Exception {
        when(transferService.quote(any(), any(), any())).thenThrow(
                AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isServiceUnavailable());
    }

    private String createTransferPayload(UUID beneficiaryId) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 125000.00);
                put("amountType", "SOURCE");
                put("sourceCurrency", "BRL");
                put("destinationCurrency", "USD");
                put("paymentMethod", "PIX");
                put("beneficiaryId", beneficiaryId.toString());
                put("description", "Pagamento de importação");
                put("totpCode", "123456");
            }
        });
    }

    @Test
    void post_transferenciaValida_retorna200ComStatusSemIdentificadoresDaAvenia() throws Exception {
        when(transferService.create(any(), any(), any()))
                .thenReturn(new CreateTransferResponse(TransactionStatus.PROCESSING));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.quoteToken").doesNotExist())
                .andExpect(jsonPath("$.quoteId").doesNotExist())
                .andExpect(jsonPath("$.ticketId").doesNotExist());
    }

    @Test
    void post_semBeneficiaryId_retorna400() throws Exception {
        String payload = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 100.00);
                put("amountType", "SOURCE");
                put("paymentMethod", "PIX");
            }
        });

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_externalIdAcimaDoLimiteDaAvenia_retorna400SemChamarService() throws Exception {
        UUID beneficiaryId = UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(new HashMap<>() {
            {
                put("amount", 100.00);
                put("amountType", "SOURCE");
                put("paymentMethod", "PIX");
                put("beneficiaryId", beneficiaryId.toString());
                put("description", "x".repeat(69));
            }
        });

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_beneficiarioInexistente_retorna404() throws Exception {
        when(transferService.create(any(), any(), any()))
                .thenThrow(new NotFoundException("Beneficiário não encontrado"));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_falhaNaAveniaAoCriarTransferencia_retorna503QuandoRetryable() throws Exception {
        when(transferService.create(any(), any(), any())).thenThrow(
                AveniaIntegrationException.communication("Falha ao comunicar com a Avenia", null));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void post_transferenciaComTotpCode_repassaTotpCodeAoService() throws Exception {
        when(transferService.create(any(), any(), any()))
                .thenReturn(new CreateTransferResponse(TransactionStatus.PROCESSING));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).create(captor.capture(), any(), any());
        assertThat(captor.getValue().getTotpCode()).isEqualTo("123456");
    }

    @Test
    void post_totpInvalido_retorna401() throws Exception {
        when(transferService.create(any(), any(), any()))
                .thenThrow(new BadCredentialsException("Invalid 2FA verification code"));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void post_totpAusenteOuUsuarioSem2fa_retorna422() throws Exception {
        when(transferService.create(any(), any(), any()))
                .thenThrow(new UnprocessableEntityException("Usuário não possui 2FA ativo"));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_CONTENT"));
    }

    @Test
    void post_totpBloqueadoPorTentativasExcedidas_retorna423() throws Exception {
        when(transferService.create(any(), any(), any()))
                .thenThrow(new LockedException("Too many failed 2FA attempts. Try again later."));

        mockMvc.perform(post("/v1/transfers")
                .with(user(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void post_semAutenticacao_retorna401SemChamarService() throws Exception {
        mockMvc.perform(post("/v1/transfers/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transferService);
    }

    @Test
    void post_quoteOperador_retorna200ComEmpresaDoVinculo() throws Exception {
        UserEntity operator = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OPERATOR);
        when(transferService.quote(any(), any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(operator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isOk());

        verify(transferService).quote(any(), eq(operator.getId()), eq(operator.getMembership().getCompanyId()));
    }

    @Test
    void post_quoteVisualizador_retorna403PermissionDeniedSemChamarService() throws Exception {
        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.VIEWER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(transferService);
    }

    @Test
    void post_quoteVinculoRemovido_retorna403AcessoRevogadoSemChamarService() throws Exception {
        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(TestCompanyUsers.removedMember(UUID.randomUUID(), CompanyRole.OWNER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(transferService);
    }

    @Test
    void post_quoteSemVinculo_retorna403AcessoNegadoSemChamarService() throws Exception {
        UserEntity semVinculo = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(post("/v1/transfers/quote")
                .with(user(semVinculo))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(transferService);
    }

    @Test
    void post_transferenciaOperador_retorna200ComEmpresaDoVinculo() throws Exception {
        UserEntity operator = TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.OPERATOR);
        when(transferService.create(any(), any(), any()))
                .thenReturn(new CreateTransferResponse(TransactionStatus.PROCESSING));

        mockMvc.perform(post("/v1/transfers")
                .with(user(operator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(transferService).create(any(), eq(operator.getId()), eq(operator.getMembership().getCompanyId()));
    }

    @Test
    void post_transferenciaVisualizador_retorna403PermissionDeniedSemChamarService() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                .with(user(TestCompanyUsers.member(UUID.randomUUID(), CompanyRole.VIEWER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(transferService);
    }

    @Test
    void post_transferenciaVinculoRemovido_retorna403AcessoRevogadoSemChamarService() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                .with(user(TestCompanyUsers.removedMember(UUID.randomUUID(), CompanyRole.OPERATOR)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(transferService);
    }

    @Test
    void post_transferenciaSemVinculo_retorna403AcessoNegadoSemChamarService() throws Exception {
        UserEntity semVinculo = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(post("/v1/transfers")
                .with(user(semVinculo))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createTransferPayload(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(transferService);
    }
}
