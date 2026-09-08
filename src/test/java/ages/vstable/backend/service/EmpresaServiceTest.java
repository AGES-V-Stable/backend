package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.SituacaoCadastralResponse;
import ages.vstable.backend.entity.DocumentoComplianceEntity;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.TipoDocumento;
import ages.vstable.backend.exception.CadastroInvalidoException;
import ages.vstable.backend.exception.EmpresaNotFoundException;
import ages.vstable.backend.repository.DocumentoComplianceRepository;
import ages.vstable.backend.repository.EmpresaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmpresaServiceTest {

    @Mock
    private EmpresaRepository empresaRepository;

    @Mock
    private DocumentoComplianceRepository documentoComplianceRepository;

    @InjectMocks
    private EmpresaService empresaService;

    @Test
    void getSituacaoCadastral_shouldReturnResponse_whenEmpresaExists() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.APROVADO, StatusCompliance.APROVADO);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getRazaoSocial()).isEqualTo("Empresa Teste LTDA");
        assertThat(response.getCnpj()).isEqualTo("00.000.000/0001-00");
        assertThat(response.getStatusKyb()).isEqualTo(StatusCompliance.APROVADO);
        assertThat(response.getStatusAml()).isEqualTo(StatusCompliance.APROVADO);
        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.APROVADO);
        assertThat(response.getDocumentos()).isEmpty();
    }

    @Test
    void getSituacaoCadastral_shouldThrowEmpresaNotFoundException_whenEmpresaNotFound() {
        UUID id = UUID.randomUUID();
        when(empresaRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> empresaService.getSituacaoCadastral(id))
                .isInstanceOf(EmpresaNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getSituacaoCadastral_shouldThrowCadastroInvalidoException_whenStatusIsNull() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, null, StatusCompliance.APROVADO);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));

        assertThatThrownBy(() -> empresaService.getSituacaoCadastral(id))
                .isInstanceOf(CadastroInvalidoException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getSituacaoCadastral_shouldThrowCadastroInvalidoException_whenDocumentStatusIsNull() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.APROVADO, StatusCompliance.APROVADO);
        DocumentoComplianceEntity documento = DocumentoComplianceEntity.builder()
                .id(UUID.randomUUID())
                .empresa(empresa)
                .tipoDocumento(TipoDocumento.CONTRATO_SOCIAL)
                .nomeArquivo("contrato.pdf")
                .urlArquivo("https://storage.example/contrato.pdf")
                .status(null)
                .build();

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of(documento));

        assertThatThrownBy(() -> empresaService.getSituacaoCadastral(id))
                .isInstanceOf(CadastroInvalidoException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getSituacaoCadastral_shouldReturnStatusGeralRejeitado_whenKybIsRejeitado() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.REJEITADO, StatusCompliance.APROVADO);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.REJEITADO);
    }

    @Test
    void getSituacaoCadastral_shouldReturnStatusGeralRejeitado_whenAmlIsRejeitado() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.PENDENTE, StatusCompliance.REJEITADO);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.REJEITADO);
    }

    @Test
    void getSituacaoCadastral_shouldReturnStatusGeralEmAnalise_whenKybIsEmAnalise() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.EM_ANALISE, StatusCompliance.PENDENTE);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.EM_ANALISE);
    }

    @Test
    void getSituacaoCadastral_shouldReturnStatusGeralEmAnalise_whenAmlIsEmAnalise() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.APROVADO, StatusCompliance.EM_ANALISE);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.EM_ANALISE);
    }

    @Test
    void getSituacaoCadastral_shouldReturnStatusGeralPendente_whenBothArePendente() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.PENDENTE, StatusCompliance.PENDENTE);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.PENDENTE);
    }

    @Test
    void getSituacaoCadastral_shouldReturnStatusGeralRejeitado_whenBothAreRejeitado() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.REJEITADO, StatusCompliance.REJEITADO);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(StatusCompliance.REJEITADO);
    }

    @ParameterizedTest
    @MethodSource("statusGeralCombinations")
    void getSituacaoCadastral_shouldComputeStatusGeralForEveryCombination(
            StatusCompliance kyb,
            StatusCompliance aml,
            StatusCompliance expected) {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, kyb, aml);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of());

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getStatusGeral()).isEqualTo(expected);
    }

    private static Stream<Arguments> statusGeralCombinations() {
        return Stream.of(
                Arguments.of(StatusCompliance.PENDENTE, StatusCompliance.PENDENTE, StatusCompliance.PENDENTE),
                Arguments.of(StatusCompliance.PENDENTE, StatusCompliance.EM_ANALISE, StatusCompliance.EM_ANALISE),
                Arguments.of(StatusCompliance.PENDENTE, StatusCompliance.APROVADO, StatusCompliance.PENDENTE),
                Arguments.of(StatusCompliance.PENDENTE, StatusCompliance.REJEITADO, StatusCompliance.REJEITADO),
                Arguments.of(StatusCompliance.EM_ANALISE, StatusCompliance.PENDENTE, StatusCompliance.EM_ANALISE),
                Arguments.of(StatusCompliance.EM_ANALISE, StatusCompliance.EM_ANALISE, StatusCompliance.EM_ANALISE),
                Arguments.of(StatusCompliance.EM_ANALISE, StatusCompliance.APROVADO, StatusCompliance.EM_ANALISE),
                Arguments.of(StatusCompliance.EM_ANALISE, StatusCompliance.REJEITADO, StatusCompliance.REJEITADO),
                Arguments.of(StatusCompliance.APROVADO, StatusCompliance.PENDENTE, StatusCompliance.PENDENTE),
                Arguments.of(StatusCompliance.APROVADO, StatusCompliance.EM_ANALISE, StatusCompliance.EM_ANALISE),
                Arguments.of(StatusCompliance.APROVADO, StatusCompliance.APROVADO, StatusCompliance.APROVADO),
                Arguments.of(StatusCompliance.APROVADO, StatusCompliance.REJEITADO, StatusCompliance.REJEITADO),
                Arguments.of(StatusCompliance.REJEITADO, StatusCompliance.PENDENTE, StatusCompliance.REJEITADO),
                Arguments.of(StatusCompliance.REJEITADO, StatusCompliance.EM_ANALISE, StatusCompliance.REJEITADO),
                Arguments.of(StatusCompliance.REJEITADO, StatusCompliance.APROVADO, StatusCompliance.REJEITADO),
                Arguments.of(StatusCompliance.REJEITADO, StatusCompliance.REJEITADO, StatusCompliance.REJEITADO)
        );
    }

    @Test
    void getSituacaoCadastral_shouldIncludeDocumentos_whenDocumentosExist() {
        UUID id = UUID.randomUUID();
        EmpresaEntity empresa = buildEmpresa(id, StatusCompliance.EM_ANALISE, StatusCompliance.EM_ANALISE);
        DocumentoComplianceEntity doc = buildDocumento(empresa);

        when(empresaRepository.findById(id)).thenReturn(Optional.of(empresa));
        when(documentoComplianceRepository.findByEmpresaId(id)).thenReturn(List.of(doc));

        SituacaoCadastralResponse response = empresaService.getSituacaoCadastral(id);

        assertThat(response.getDocumentos()).hasSize(1);
        assertThat(response.getDocumentos().getFirst().getNomeArquivo()).isEqualTo("contrato_social.pdf");
        assertThat(response.getDocumentos().getFirst().getTipoDocumento()).isEqualTo(TipoDocumento.CONTRATO_SOCIAL);
        assertThat(response.getDocumentos().getFirst().getStatus()).isEqualTo(StatusCompliance.EM_ANALISE);
        assertThat(response.getDocumentos().getFirst().getTamanhoArquivoBytes()).isEqualTo(204800L);
    }

    private EmpresaEntity buildEmpresa(UUID id, StatusCompliance kyb, StatusCompliance aml) {
        EmpresaEntity empresa = new EmpresaEntity();
        empresa.setId(id);
        empresa.setRazaoSocial("Empresa Teste LTDA");
        empresa.setNomeFantasia("Empresa Teste");
        empresa.setCnpj("00.000.000/0001-00");
        empresa.setStatusKyb(kyb);
        empresa.setStatusAml(aml);
        return empresa;
    }

    private DocumentoComplianceEntity buildDocumento(EmpresaEntity empresa) {
        DocumentoComplianceEntity doc = new DocumentoComplianceEntity();
        doc.setId(UUID.randomUUID());
        doc.setEmpresa(empresa);
        doc.setTipoDocumento(TipoDocumento.CONTRATO_SOCIAL);
        doc.setNomeArquivo("contrato_social.pdf");
        doc.setUrlArquivo("https://storage.example.com/docs/contrato_social.pdf");
        doc.setTamanhoArquivoBytes(204800L);
        doc.setStatus(StatusCompliance.EM_ANALISE);
        doc.setEnviadoEm(OffsetDateTime.now());
        return doc;
    }
}
