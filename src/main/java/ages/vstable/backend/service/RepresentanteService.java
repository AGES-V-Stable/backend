package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.RepresentanteResponse;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RepresentanteService {

    private final UsuarioRepository usuarioRepository;

    public List<RepresentanteResponse> findAll() {
        return usuarioRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private RepresentanteResponse toResponse(UsuarioEntity entity) {
        RepresentanteResponse response = new RepresentanteResponse();

        response.setId(entity.getId());
        response.setEmpresaId(entity.getEmpresaId());
        response.setNomeCompleto(entity.getNomeCompleto());
        response.setEmail(entity.getEmail());
        response.setCriadoEm(entity.getCriadoEm());
        response.setAtualizadoEm(entity.getAtualizadoEm());

        return response;
    }
}
