package ages.vstable.backend.security;

import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.entity.*;
import ages.vstable.backend.entity.enums.*;
import ages.vstable.backend.external.blindpay.*;
import ages.vstable.backend.external.blindpay.dto.*;
import ages.vstable.backend.repository.*;
import ages.vstable.backend.service.blindpay.BlindPayComplianceService;
import ages.vstable.backend.service.quote.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Limites dos adapters; retorno positivo do provedor é uma simulação explícita, não aprovação real. */
@Tag("pentest-impact")
class ContentAndFeeImpactAssessmentTest extends ImpactEvidence {
    @Test
    @DisplayName("P86-19 CHAIN-04 arquivos truncados chegam ao gateway sem comprovar KYB ou execucao de codigo")
    void truncatedDocumentsReachMockGateway() {
        var gateway = mock(BlindPayGateway.class);
        var companies = mock(CompanyRepository.class);
        var accounts = mock(ProviderAccountRepository.class);
        var company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        var user = UserEntity.builder().companyId(company.getId()).build();
        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(ProviderAccountEntity.builder().company(company).tosAcceptedAt(OffsetDateTime.now()).build()));
        when(gateway.uploadFile(any(), anyString(), anyString(), any()))
                .thenReturn(new BlindPayUploadResponse("https://files.example.test/synthetic"));
        var service = new BlindPayComplianceService(gateway, new BlindPayProperties(), companies, accounts,
                mock(BlindPayTosSessionRepository.class), JsonMapper.builder().build());
        List<byte[]> files = List.of("%PDF".getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A},
                new byte[]{(byte)0xFF,(byte)0xD8,(byte)0xFF});
        for (byte[] bytes : files) {
            assertThatCode(() -> service.uploadDocument(user, BlindPayDocumentType.INCORPORATION, bytes)).doesNotThrowAnyException();
        }
        verify(gateway, times(3)).uploadFile(any(), anyString(), anyString(), any());
        observe("prefixosInertesEncaminhados", List.of("PDF 4 bytes", "PNG 8 bytes", "JPEG 3 bytes"));
        observe("alcance", "Gateway mock recebeu 3 arquivos; nao prova que provedor aceita documento ou aprova KYB, nem RCE");
    }

    @Test
    @DisplayName("P86-20 CHAIN-04 tarifa externa negativa atravessa adapter sem demonstrar manipulacao HTTP")
    void negativeProviderFeeCrossesAdapterBoundary() {
        var gateway = mock(BlindPayGateway.class);
        var provisioning = mock(BlindPayProvisioningService.class);
        var company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        var beneficiary = BeneficiaryEntity.builder().id(UUID.randomUUID()).company(company)
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT).currency("USD").paymentRail("wire").build();
        when(provisioning.ensureBankAccount(company, beneficiary)).thenReturn("impact-bank");
        when(gateway.createQuote(any(), anyString())).thenReturn(new BlindPayQuoteResponse("impact-quote",
                Instant.now().plusSeconds(300).toEpochMilli(), BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("9900"), new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("-1"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null));
        var context = new QuoteContext(UUID.randomUUID(), company, beneficiary, new QuoteRequest(beneficiary.getId(),
                QuoteDirection.PAYOUT, "USDB", "USD", "wallet", "wire", new BigDecimal("100"),
                QuoteAmountSide.SOURCE, "USDB", "base_sepolia", false, "Massa sintetica"), UUID.randomUUID());
        var result = new BlindPayQuoteProvider(gateway, provisioning).quote(context, "impact-idempotency");
        assertThat(result.totalFee()).isEqualByComparingTo("-0.01");
        observe("tarifaDoAdapter", "-0.01");
        observe("precondicao", "Resposta externa controlada no mock; nao foi demonstrado que USER consegue controlar resposta autentica do provedor");
    }
}
