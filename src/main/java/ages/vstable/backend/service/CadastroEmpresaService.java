package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.CadastroEmpresaRequest;
import ages.vstable.backend.dto.empresa.CadastroEmpresaResponse;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.entity.enums.StatusOnboarding;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.repository.EmpresaRepository;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import ages.vstable.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CadastroEmpresaService {

    private static final int ETAPA_EMPRESA = 2;
    private static final int ETAPA_COMPLIANCE = 3;
    private static final String PROXIMA_ETAPA = "compliance";

    private final EmpresaRepository empresaRepository;
    private final ProgressoCadastroRepository progressoCadastroRepository;
    private final UsuarioRepository usuarioRepository;
    private final EmpresaDadosValidator empresaDadosValidator;

    @Transactional
    public CadastroEmpresaResponse create(UUID progressoCadastroId, CadastroEmpresaRequest request) {
        ProgressoCadastroEntity progresso = progressoCadastroRepository.findByIdForUpdate(progressoCadastroId)
                .orElseThrow(() -> new NotFoundException("Progresso de cadastro não encontrado"));

        validateProgress(progresso);

        UsuarioEntity usuario = usuarioRepository.findById(progresso.getUsuarioId())
                .orElseThrow(() -> new IllegalStateException(
                        "Usuário referenciado pelo progresso de cadastro não encontrado"));

        EmpresaDadosNormalizados dados = empresaDadosValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(), request.getCep(),
                request.getCidade(), request.getEstado());

        if (empresaRepository.existsByCnpj(dados.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EmpresaEntity empresa = createEmpresa(dados, now);

        try {
            empresa = empresaRepository.saveAndFlush(empresa);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        usuario.setEmpresaId(empresa.getId());
        usuario.setAtualizadoEm(now);
        usuarioRepository.save(usuario);

        progresso.setEmpresaId(empresa.getId());
        progresso.setEtapaAtual(ETAPA_COMPLIANCE);
        progresso.setAtualizadoEm(now);
        progressoCadastroRepository.save(progresso);

        return CadastroEmpresaResponse.builder()
                .empresaId(empresa.getId())
                .progressoCadastroId(progresso.getId())
                .etapaAtual(progresso.getEtapaAtual())
                .proximaEtapa(PROXIMA_ETAPA)
                .atualizadoEm(progresso.getAtualizadoEm())
                .build();
    }

    private void validateProgress(ProgressoCadastroEntity progresso) {
        if (progresso.getStatusGeral() != StatusOnboarding.RASCUNHO
                || !Integer.valueOf(ETAPA_EMPRESA).equals(progresso.getEtapaAtual())
                || progresso.getEmpresaId() != null) {
            throw new ConflictException("Cadastro não está disponível para inclusão da empresa");
        }
    }

    private EmpresaEntity createEmpresa(EmpresaDadosNormalizados dados, OffsetDateTime now) {
        EmpresaEntity empresa = new EmpresaEntity();
        empresa.setRazaoSocial(dados.razaoSocial());
        empresa.setCnpj(dados.cnpj());
        empresa.setPais(dados.pais());
        empresa.setCep(dados.cep());
        empresa.setCidade(dados.cidade());
        empresa.setEstado(dados.estado());
        empresa.setStatusKyb(StatusCompliance.PENDENTE);
        empresa.setStatusAml(StatusCompliance.PENDENTE);
        empresa.setSaldoDisponivelBrl(BigDecimal.ZERO);
        empresa.setCriadoEm(now);
        empresa.setAtualizadoEm(now);
        return empresa;
    }
}
