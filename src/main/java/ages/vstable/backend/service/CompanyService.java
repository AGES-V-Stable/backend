package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.dto.company.CompanyCreateRequest;
import ages.vstable.backend.dto.company.CompanyResponse;
import ages.vstable.backend.entity.CompanyEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyDataValidator companyDataValidator;

    public List<CompanyResponse> findAll() {
        return companyRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public CompanyResponse create(CompanyCreateRequest request) {
        CompanyNormalizedData dados = companyDataValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(), request.getCep(),
                request.getCidade(), request.getEstado());

        if (companyRepository.existsByCnpj(dados.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        CompanyEntity empresa = new CompanyEntity();

        applyDados(empresa, dados);
        empresa.setNomeFantasia(normalizeOptional(request.getNomeFantasia()));

        empresa.setStatusKyb(StatusCompliance.PENDENTE);
        empresa.setStatusAml(StatusCompliance.PENDENTE);
        empresa.setSaldoDisponivelBrl(BigDecimal.ZERO);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        empresa.setCriadoEm(now);
        empresa.setAtualizadoEm(now);

        return toResponse(saveOrConflict(empresa));
    }

    private CompanyResponse toResponse(CompanyEntity entity) {
        CompanyResponse response = new CompanyResponse();

        response.setId(entity.getId());
        response.setRazaoSocial(entity.getRazaoSocial());
        response.setNomeFantasia(entity.getNomeFantasia());
        response.setCnpj(entity.getCnpj());
        response.setPais(entity.getPais());
        response.setCep(entity.getCep());
        response.setCidade(entity.getCidade());
        response.setEstado(entity.getEstado());
        response.setStatusKyb(entity.getStatusKyb());
        response.setStatusAml(entity.getStatusAml());
        response.setSaldoDisponivelBrl(entity.getSaldoDisponivelBrl());
        response.setCriadoEm(entity.getCriadoEm());
        response.setAtualizadoEm(entity.getAtualizadoEm());

        return response;
    }

    private void applyDados(CompanyEntity empresa, CompanyNormalizedData dados) {
        empresa.setRazaoSocial(dados.razaoSocial());
        empresa.setCnpj(dados.cnpj());
        empresa.setPais(dados.pais());
        empresa.setCep(dados.cep());
        empresa.setCidade(dados.cidade());
        empresa.setEstado(dados.estado());
    }

    private String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private CompanyEntity saveOrConflict(CompanyEntity empresa) {
        try {
            return companyRepository.saveAndFlush(empresa);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("CNPJ já cadastrado");
        }
    }
}
