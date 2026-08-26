package ages.vstable.backend.service;

import ages.vstable.backend.dto.empresa.EmpresaCreateRequest;
import ages.vstable.backend.dto.empresa.EmpresaResponse;
import ages.vstable.backend.dto.empresa.EmpresaUpdateRequest;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.enums.StatusCompliance;
import ages.vstable.backend.repository.EmpresaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmpresaService {

    private final EmpresaRepository empresaRepository;

    public List<EmpresaResponse> findAll() {
        return empresaRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<EmpresaResponse> findById(UUID id) {
        return empresaRepository.findById(id)
                .map(this::toResponse);
    }

    public Optional<EmpresaResponse> findByCnpj(String cnpj) {
        return empresaRepository.findByCnpj(cnpj)
                .map(this::toResponse);
    }

    public EmpresaResponse create(EmpresaCreateRequest request) {

        if (empresaRepository.existsByCnpj(request.getCnpj())) {
            throw new IllegalArgumentException(
                    "An empresa with this CNPJ already exists"
            );
        }

        EmpresaEntity empresa = new EmpresaEntity();

        empresa.setRazaoSocial(request.getRazaoSocial());
        empresa.setNomeFantasia(request.getNomeFantasia());
        empresa.setCnpj(request.getCnpj());

        empresa.setStatusKyb(StatusCompliance.PENDENTE);
        empresa.setStatusAml(StatusCompliance.PENDENTE);
        empresa.setSaldoDisponivelBrl(BigDecimal.ZERO);

        return toResponse(empresaRepository.save(empresa));
    }

    public EmpresaResponse update(
            UUID id,
            EmpresaUpdateRequest request
    ) {
        EmpresaEntity empresa = empresaRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException("Empresa not found")
                );

        if (!empresa.getCnpj().equals(request.getCnpj())
                && empresaRepository.existsByCnpj(request.getCnpj())) {
            throw new IllegalArgumentException(
                    "An empresa with this CNPJ already exists"
            );
        }

        empresa.setRazaoSocial(request.getRazaoSocial());
        empresa.setNomeFantasia(request.getNomeFantasia());
        empresa.setCnpj(request.getCnpj());

        return toResponse(empresaRepository.save(empresa));
    }

    public void deleteById(UUID id) {
        if (!empresaRepository.existsById(id)) {
            throw new IllegalArgumentException("Empresa not found");
        }

        empresaRepository.deleteById(id);
    }

    public boolean existsById(UUID id) {
        return empresaRepository.existsById(id);
    }

    private EmpresaResponse toResponse(EmpresaEntity entity) {
        EmpresaResponse response = new EmpresaResponse();

        response.setId(entity.getId());
        response.setRazaoSocial(entity.getRazaoSocial());
        response.setNomeFantasia(entity.getNomeFantasia());
        response.setCnpj(entity.getCnpj());
        response.setStatusKyb(entity.getStatusKyb());
        response.setStatusAml(entity.getStatusAml());
        response.setSaldoDisponivelBrl(entity.getSaldoDisponivelBrl());
        response.setCriadoEm(entity.getCriadoEm());
        response.setAtualizadoEm(entity.getAtualizadoEm());

        return response;
    }
}