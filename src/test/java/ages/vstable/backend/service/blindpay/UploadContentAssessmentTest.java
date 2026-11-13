package ages.vstable.backend.service.blindpay;

import ages.vstable.backend.dto.blindpay.BlindPayDocumentType;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.ProviderAccountEntity;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.IntegrationProvider;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import ages.vstable.backend.external.blindpay.dto.BlindPayUploadResponse;
import ages.vstable.backend.repository.BlindPayTosSessionRepository;
import ages.vstable.backend.repository.CompanyRepository;
import ages.vstable.backend.repository.ProviderAccountRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Prefixos mínimos e inertes: nenhum arquivo malicioso ou conteúdo pessoal. */
@Tag("security-assessment")
class UploadContentAssessmentTest {
    @ParameterizedTest(name = "TC-API-05 arquivo truncado {0}")
    @MethodSource("truncatedFiles")
    void aFileSignatureAloneMustNotBeAcceptedAsACompleteDocument(String format, byte[] content) {
        BlindPayGateway gateway = mock(BlindPayGateway.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        ProviderAccountRepository accounts = mock(ProviderAccountRepository.class);
        CompanyEntity company = CompanyEntity.builder().id(UUID.randomUUID()).build();
        UserEntity user = UserEntity.builder().companyId(company.getId()).build();
        ProviderAccountEntity account = ProviderAccountEntity.builder().company(company)
                .tosAcceptedAt(OffsetDateTime.now()).build();
        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndProvider(company.getId(), IntegrationProvider.BLINDPAY))
                .thenReturn(Optional.of(account));
        when(gateway.uploadFile(any(), anyString(), anyString(), any()))
                .thenReturn(new BlindPayUploadResponse("https://files.example.test/synthetic"));
        BlindPayComplianceService service = new BlindPayComplianceService(gateway, new BlindPayProperties(),
                companies, accounts, mock(BlindPayTosSessionRepository.class), JsonMapper.builder().build());

        assertThatThrownBy(() -> service.uploadDocument(user, BlindPayDocumentType.INCORPORATION, content))
                .as("Um prefixo " + format + " isolado nao forma um documento valido")
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
    }

    static Stream<Arguments> truncatedFiles() {
        return Stream.of(
                Arguments.of("PDF", "%PDF".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("PNG", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
                Arguments.of("JPEG", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
    }
}
