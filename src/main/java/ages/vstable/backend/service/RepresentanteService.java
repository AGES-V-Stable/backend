package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.RepresentanteCreateRequest;
import ages.vstable.backend.dto.representante.RepresentanteResponse;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepresentanteService {

    private final UsuarioRepository usuarioRepository;

    public Optional<RepresentanteResponse> findById(UUID id) {
        return usuarioRepository.findById(id)
                .map(this::toResponse);
    }

    public RepresentanteResponse create(RepresentanteCreateRequest request) {

        if (usuarioRepository.existsByCpf(request.getCpf())) {
            throw new IllegalArgumentException(
                    "A representante with this CPF already exists"
            );
        }

        UsuarioEntity usuario = new UsuarioEntity();

        usuario.setEmpresaId(request.getEmpresaId());
        usuario.setNomeCompleto(request.getNomeCompleto());
        usuario.setCpf(request.getCpf());
        usuario.setDataNascimento(request.getDataNascimento());
        usuario.setEmail(request.getEmail());
        usuario.setTelefone(request.getTelefone());
        usuario.setCargo(request.getCargo());
        usuario.setPaisResidencia(request.getPaisResidencia());

        return toResponse(usuarioRepository.save(usuario));
    }

    private RepresentanteResponse toResponse(UsuarioEntity entity) {
        RepresentanteResponse response = new RepresentanteResponse();

        response.setId(entity.getId());
        response.setEmpresaId(entity.getEmpresaId());
        response.setNomeCompleto(entity.getNomeCompleto());
        response.setCpf(entity.getCpf());
        response.setDataNascimento(entity.getDataNascimento());
        response.setEmail(entity.getEmail());
        response.setTelefone(entity.getTelefone());
        response.setCargo(entity.getCargo());
        response.setPaisResidencia(entity.getPaisResidencia());
        response.setCriadoEm(entity.getCriadoEm());
        response.setAtualizadoEm(entity.getAtualizadoEm());

        return response;
    }
}
